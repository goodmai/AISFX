// src/main/scala/com/aiplatform/controller/manager/RequestExecutionManager.scala
package com.aiplatform.controller.manager

import com.aiplatform.model.{ Dialog, PromptPreset, Topic}
import com.aiplatform.service.{AIService, InlineData}
import org.slf4j.LoggerFactory
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

/**
 * Менеджер для инкапсуляции логики подготовки, выполнения AI запроса
 * и обновления состояния после получения ответа.
 *
 * @param stateManager Менеджер состояния приложения.
 * @param topicManager Менеджер управления топиками.
 * @param presetManager Менеджер управления пресетами.
 * @param aiService Сервис для взаимодействия с AI.
 * @param ec ExecutionContext для асинхронных операций.
 */
class RequestExecutionManager(
                               stateManager: StateManager,
                               topicManager: TopicManager,
                               presetManager: PresetManager,
                               aiService: AIService
                             )(implicit ec: ExecutionContext) {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Подготавливает данные для AI запроса (топик, история, модель, промпт).
   * Эта часть выполняется синхронно перед асинхронным вызовом AI.
   * ИСПРАВЛЕНО: Логика выбора/создания топика для корректного переиспользования.
   *
   * @param originalRequestText Исходный текст запроса от пользователя.
   * @param categoryHint        Подсказка по категории (None для Global).
   * @return Success с кортежем (ID топика, История диалогов, Флаг нового топика, Имя модели, Финальный промпт),
   * или Failure с ошибкой подготовки.
   */
  private def prepareRequestData(
                                  originalRequestText: String,
                                  categoryHint: Option[String]
                                ): Try[(String, List[Dialog], Boolean, String, String)] = Try {

    val currentState = stateManager.getState // Получаем актуальное состояние один раз
    val targetCategory = categoryHint.getOrElse("Global") // Используем "Global" если categoryHint = None

    // 1. Определяем или создаем топик
    // Сначала пытаемся найти последний активный топик в целевой категории.
    // determineActiveTopicForCategory вернет самый свежий, если в карте нет/устарел.
    val (targetTopicId: String, topicHistory: List[Dialog], isNewTopic: Boolean) =
      topicManager.determineActiveTopicForCategory(targetCategory)
        .flatMap(topicManager.findTopicById) match {
        case Some(existingTopic) =>
          // Нашли подходящий существующий топик (самый свежий в категории)
          logger.debug(s"Using existing topic '${existingTopic.id}' (last active or newest) for category '$targetCategory'. Empty: ${existingTopic.dialogs.isEmpty}")
          // Считаем "новым" (для генерации заголовка) только если он пустой
          (existingTopic.id, existingTopic.dialogs, existingTopic.dialogs.isEmpty)

        case None =>
          // В этой категории еще нет топиков, создаем новый
          logger.debug(s"No existing topic found for category '$targetCategory'. Creating new topic.")
          topicManager.createNewTopic(targetCategory) match {
            case Success(newTopic) =>
              logger.info(s"Successfully created new topic '${newTopic.id}' for category '$targetCategory'.")
              (newTopic.id, newTopic.dialogs, true) // Новый топик всегда "новый"
            case Failure(e) =>
              logger.error(s"Failed to create new topic for category '$targetCategory'.", e)
              throw new Exception(s"Ошибка создания топика: ${e.getMessage}", e) // Прерываем подготовку
          }
      }

    // 2. Определяем пресет, модель и финальный промпт (логика без изменений)
    val preset: PromptPreset = presetManager.findActivePresetForButton(targetCategory)
    // Используем globalAiModel из состояния, полученного в начале
    val modelToUse: String = preset.modelOverride.getOrElse(currentState.globalAiModel)
    val finalPrompt: String = targetCategory match {
      case "Global" => originalRequestText // Для Global используем текст как есть
      case _ if preset.prompt.contains("{{INPUT}}") => preset.prompt.replace("{{INPUT}}", originalRequestText)
      case _ => s"${preset.prompt}\n\n${originalRequestText}" // Если нет плейсхолдера, добавляем текст в конец
    }


    // 3. Валидация модели (логика без изменений)
    if (modelToUse.trim.isEmpty) {
      throw new Exception("Не удалось определить модель AI для запроса (пустое имя).")
    }
    if (currentState.availableModels.nonEmpty && !currentState.availableModels.exists(_.name == modelToUse)) {
      logger.warn(s"Model '$modelToUse' determined for request is not in the list of available models. Proceeding anyway.")
    }

    logger.debug(s"Request data prepared. TopicID: $targetTopicId, HistorySize: ${topicHistory.size}, isNewTopic: $isNewTopic, Model: $modelToUse")
    (targetTopicId, topicHistory, isNewTopic, modelToUse, finalPrompt)
  }


  /**
   * Основной метод для обработки запроса пользователя.
   * Выполняет подготовку, вызывает AIService асинхронно,
   * обновляет состояние (добавляет диалог, инициирует генерацию заголовка).
   *
   * @param originalRequestText Исходный текст запроса от пользователя.
   * @param categoryHint        Подсказка по категории (None для Global).
   * @param apiKey              API ключ.
   * @param imageDataOpt        Опциональные данные изображения для мультимодального запроса.
   * @return Future, содержащий пару (ID топика, созданный Диалог) в случае успеха,
   * или Future.failed в случае ошибки на любом этапе.
   */
  def submitRequest(
                     originalRequestText: String,
                     categoryHint: Option[String],
                     apiKey: String,
                     // Добавлен параметр для изображения
                     imageDataOpt: Option[InlineData] = None
                   ): Future[(String, Dialog)] = {

    // 1. Синхронная подготовка данных
    prepareRequestData(originalRequestText, categoryHint) match {
      case Failure(prepError) =>
        logger.error(s"Request preparation failed: ${prepError.getMessage}", prepError)
        Future.failed(prepError)

      case Success((targetTopicId, topicHistory, isNewTopic, modelUsed, finalPrompt)) =>
        // 2. Асинхронный вызов AI Service
        logger.info(s"Submitting AI request. Model: '$modelUsed', Topic ID: '$targetTopicId', isNewTopic: $isNewTopic, Image included: ${imageDataOpt.isDefined}")

        // Устанавливаем модель в AIService. Логируем предупреждение при ошибке, но не прерываем запрос.
        Try(aiService.updateModel(modelUsed)).failed.foreach { e =>
          logger.warn(s"Non-critical error updating model in AIService to '$modelUsed' before request: ${e.getMessage}")
        }

        // Вызываем AIService.process, передавая данные изображения
        aiService.process(finalPrompt, apiKey, None, None, None, topicHistory, imageDataOpt)
          .flatMap { aiResponse => // Обработка успешного ответа AI
            logger.info(s"AI call successful for topic ID '$targetTopicId'. Response length: ${aiResponse.length}")
            val resultDialog = Dialog(
              // Заголовок диалога больше не используется, оставляем плейсхолдер или убираем
              title = "AI Response", // Placeholder
              request = originalRequestText, // Сохраняем оригинальный текст запроса
              response = aiResponse,
              model = modelUsed // Сохраняем использованную модель
            )

            // 3. Обновляем состояние: добавляем диалог (синхронно с Try)
            topicManager.addDialogToTopic(targetTopicId, resultDialog) match {
              case Success(_) =>
                logger.debug(s"Dialog successfully added to topic '$targetTopicId'.")
                // 4. Инициируем генерацию заголовка (асинхронно, "fire-and-forget")
                // Генерируем заголовок только если это действительно был новый пустой топик И текст запроса не пустой
                if (isNewTopic && originalRequestText.nonEmpty) {
                  logger.debug(s"Initiating background title generation for new topic '$targetTopicId'.")
                  topicManager.generateAndSetTopicTitle(targetTopicId, originalRequestText, apiKey)
                    .failed.foreach { titleError => // Логируем ошибку генерации заголовка
                      logger.warn(s"Background title generation failed for topic $targetTopicId", titleError)
                    }
                }
                // Возвращаем успешный результат основного запроса
                Future.successful((targetTopicId, resultDialog))

              case Failure(stateError) =>
                logger.error(s"Failed to add dialog to topic '$targetTopicId' after successful AI response.", stateError)
                Future.failed(new Exception(s"Ошибка сохранения диалога: ${stateError.getMessage}", stateError))
            }
          }
          .recoverWith { // Обработка ошибок от aiService.process или из flatMap
            case NonFatal(aiError) =>
              logger.error(s"AI request processing failed for topic '$targetTopicId'.", aiError)
              Future.failed(new Exception(s"Ошибка от AI сервиса: ${aiError.getMessage}", aiError))
          }
    }
  }
}