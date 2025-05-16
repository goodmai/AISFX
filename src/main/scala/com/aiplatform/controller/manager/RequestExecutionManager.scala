// src/main/scala/com/aiplatform/controller/manager/RequestExecutionManager.scala
package com.aiplatform.controller.manager

import com.aiplatform.model.{Dialog, PromptPreset, Topic} // Убедимся, что PromptPreset импортирован
import com.aiplatform.service.{AIService, InlineData}
import org.slf4j.LoggerFactory
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

/**
 * Менеджер для инкапсуляции логики подготовки, выполнения AI запроса
 * и обновления состояния после получения ответа.
 *
 * AISFX:
 * - Является Manager'ом, координирующим работу других компонентов (StateManager, TopicManager, PresetManager, AIService).
 * - Инкапсулирует сложную логику выполнения запроса.
 * - Использует `Future` для асинхронных операций.
 * - Обрабатывает ошибки на разных этапах и возвращает `Future.failed` при необходимости.
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
                             )(
                               implicit
                               ec: ExecutionContext
                             ) {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Подготавливает данные для AI запроса: ID топика, историю диалогов,
   * флаг нового топика, имя модели для использования и финальный текст промпта.
   *
   * Этапы:
   * 1. Определяет или создает целевой топик на основе `categoryHint`.
   * 2. Определяет активный пресет для данной категории.
   * 3. Выбирает модель AI (из пресета или глобальную).
   * 4. Формирует финальный промпт, подставляя пользовательский ввод в шаблон пресета.
   * 5. Возвращает все необходимые данные или ошибку подготовки.
   *
   * @param originalRequestText Исходный текст запроса от пользователя.
   * @param categoryHint        Подсказка по категории (None для "Global").
   * @return Success с кортежем (ID топика, История диалогов, Флаг нового топика, Имя модели, Финальный промпт, Активный пресет),
   * или Failure с ошибкой подготовки.
   */
  private def prepareRequestData(
                                  originalRequestText: String,
                                  categoryHint: Option[String],
                                  fileContext: Option[String] = None
                                ): Try[(String, List[Dialog], Boolean, String, String, PromptPreset)] = Try { // Добавлен PromptPreset в результат

    val currentState = stateManager.getState
    val targetCategory = categoryHint.getOrElse("Global")

    // 1. Определяем или создаем топик
    val (targetTopicId: String, topicHistory: List[Dialog], isNewTopic: Boolean) =
      topicManager.determineActiveTopicForCategory(targetCategory)
        .flatMap(topicManager.findTopicById) match {
        case Some(existingTopic) =>
          logger.debug(s"Using existing topic '${existingTopic.id}' (last active or newest) for category '$targetCategory'. Dialogs: ${existingTopic.dialogs.size}")
          (existingTopic.id, existingTopic.dialogs, existingTopic.dialogs.isEmpty)
        case None =>
          logger.debug(s"No existing topic found for category '$targetCategory'. Creating new topic.")
          topicManager.createNewTopic(targetCategory) match {
            case Success(newTopic) =>
              logger.info(s"Successfully created new topic '${newTopic.id}' for category '$targetCategory'.")
              (newTopic.id, newTopic.dialogs, true)
            case Failure(e) =>
              logger.error(s"Failed to create new topic for category '$targetCategory'.", e)
              throw new Exception(s"Ошибка создания топика: ${e.getMessage()}", e)
          }
      }

    // 2. Определяем пресет
    val activePreset: PromptPreset = presetManager.findActivePresetForButton(targetCategory)
    logger.debug(s"Active preset for category '$targetCategory': '${activePreset.name}'")

    // 3. Определяем модель для использования
    val modelToUse: String = activePreset.modelOverride.getOrElse(currentState.globalAiModel)
    if (modelToUse.trim.isEmpty) {
      throw new Exception("Не удалось определить модель AI для запроса (пустое имя модели).")
    }
    if (currentState.availableModels.nonEmpty && !currentState.availableModels.exists(_.name == modelToUse)) {
      logger.warn(
        s"Model '$modelToUse' (from preset '${activePreset.name}' or global) is not in the list of available models. Proceeding anyway."
      )
    }
    // 4. Формируем финальный промпт с учетом контекста файлов, если он есть
    val requestTextWithContext: String = fileContext match {
      case Some(context) if context.nonEmpty =>
        logger.debug("Adding file context to request. Context length: {}", context.length)
        val sanitizedContext = context.replace("\u0000", "")  // Remove null bytes that can cause issues
        
        // Log entire context at TRACE level for debugging
        if (logger.isTraceEnabled) {
          logger.trace(s"File context being added to request:\n$sanitizedContext")
        }
        
        s"""Request:
           |$originalRequestText
           |
           |Context:
           |$sanitizedContext""".stripMargin
      case _ => originalRequestText
    }
    
    // Log the final request text with context at debug level (truncated)
    if (logger.isDebugEnabled) {
      val previewText = if (requestTextWithContext.length > 1000) {
        requestTextWithContext.substring(0, 1000) + "... (truncated)"
      } else {
        requestTextWithContext
      }
      logger.debug(s"Request text with context:\n$previewText")
    }
    
    // Используем запрос с контекстом для создания финального промпта
    val finalPrompt: String = targetCategory match {
      case "Global" => requestTextWithContext // Для "Global" используем текст как есть, но с контекстом
      case _ if activePreset.prompt.contains("{{INPUT}}") =>
        activePreset.prompt.replace("{{INPUT}}", requestTextWithContext)
      case _ => // Если нет плейсхолдера, просто добавляем текст пользователя после промпта пресета
        s"${activePreset.prompt}\n\n$requestTextWithContext"
    }

    logger.debug(
      s"Request data prepared. TopicID: $targetTopicId, HistorySize: ${topicHistory.size}, isNewTopic: $isNewTopic, Model: $modelToUse, Preset: ${activePreset.name}"
    )
    (targetTopicId, topicHistory, isNewTopic, modelToUse, finalPrompt, activePreset) // Возвращаем и активный пресет
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
                     imageDataOpt: Option[InlineData] = None,
                     fileContext: Option[String] = None
                   ): Future[(String, Dialog)] = {

    prepareRequestData(originalRequestText, categoryHint, fileContext) match {
      case Failure(prepError) =>
        logger.error(s"Request preparation failed: ${prepError.getMessage()}", prepError)
        Future.failed(prepError)

      // Достаем activePreset из результата prepareRequestData
      case Success((targetTopicId, topicHistory, isNewTopic, modelUsed, finalPrompt, activePreset)) =>
        logger.info(
          s"Submitting AI request. Model: '$modelUsed', Topic ID: '$targetTopicId', isNewTopic: $isNewTopic, Image included: ${imageDataOpt.isDefined}, File context included: ${fileContext.isDefined}, Preset: '${activePreset.name}'"
        )

        Try(aiService.updateModel(modelUsed)).failed.foreach { e =>
          logger.warn(s"Non-critical error updating model in AIService to '$modelUsed' before request: ${e.getMessage()}")
        }

        aiService.process(
            prompt = finalPrompt,
            apiKey = apiKey,
            temperature = Some(activePreset.temperature), // Передаем как Option
            topP = Some(activePreset.topP),               // Передаем как Option
            topK = Some(activePreset.topK),               // Передаем как Option
            maxOutputTokens = activePreset.maxOutputTokens, // Это уже Option[Int] из PromptPreset
            history = topicHistory,
            imageData = imageDataOpt
          ) ().flatMap { aiResponse =>
            logger.info(s"AI call successful for topic ID '$targetTopicId'. Response length: ${aiResponse.length}")
            val resultDialog = Dialog(
              title = "AI Response", // Заголовок диалога, можно сделать более осмысленным
              request = originalRequestText,
              response = aiResponse,
              model = modelUsed
            )

            topicManager.addDialogToTopic(targetTopicId, resultDialog) match {
              case Success(_) =>
                logger.debug(s"Dialog successfully added to topic '$targetTopicId'.")
                if (isNewTopic && originalRequestText.nonEmpty) {
                  logger.debug(s"Initiating background title generation for new topic '$targetTopicId'.")
                  // Асинхронная генерация заголовка, не блокируем основной результат
                  topicManager.generateAndSetTopicTitle(targetTopicId, originalRequestText, apiKey)
                    .failed.foreach { titleError =>
                      logger.warn(s"Background title generation failed for topic $targetTopicId", titleError)
                    }
                }
                Future.successful((targetTopicId, resultDialog))
              case Failure(stateError) =>
                logger.error(s"Failed to add dialog to topic '$targetTopicId' after successful AI response.", stateError)
                Future.failed(new Exception(s"Ошибка сохранения диалога: ${stateError.getMessage()}", stateError))
            }
          }
          .recoverWith {
            case NonFatal(aiError) =>
              logger.error(s"AI request processing failed for topic '$targetTopicId'. Error: ${aiError.getMessage()}", aiError)
              Future.failed(new Exception(s"Ошибка от AI сервиса: ${aiError.getMessage()}", aiError))
          }
    }
  }
}