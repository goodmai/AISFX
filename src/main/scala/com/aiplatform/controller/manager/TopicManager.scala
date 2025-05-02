// src/main/scala/com/aiplatform/controller/manager/TopicManager.scala
package com.aiplatform.controller.manager

import com.aiplatform.model.{ Dialog, Topic}
import com.aiplatform.service.AIService
import org.slf4j.LoggerFactory
import scala.util.{Try, Success, Failure}
import scala.concurrent.{ExecutionContext, Future}
import java.time.Instant
import scala.util.control.NonFatal

/**
 * Управляет логикой топиков: создание, удаление, получение списка, выбор активного.
 * Использует StateManager для чтения и обновления состояния.
 *
 * @param stateManager Менеджер состояния приложения.
 * @param aiService Сервис для взаимодействия с AI (используется для генерации заголовков).
 * @param ec ExecutionContext для асинхронных операций.
 */
class TopicManager(stateManager: StateManager, aiService: AIService)(implicit ec: ExecutionContext) {
  private val logger = LoggerFactory.getLogger(getClass)

  // Параметры для генерации заголовка
  private val titleGenerationModel = "gemini-1.5-flash-latest" // Или другая подходящая модель
  private val titleGenerationPromptTemplate = "Кратко (2-5 слов) опиши суть следующего запроса, чтобы использовать как заголовок в истории диалогов:\n\"{{INPUT}}\"\nЗаголовок:"

  // --- Методы чтения состояния (не изменяют его) ---

  /**
   * Получает список топиков для указанной категории, отсортированный по последнему обновлению (новые сверху).
   */
  def getTopicsForCategory(category: String): List[Topic] = {
    stateManager.getState.topics
      .filter(_.category == category)
      .sortBy(_.lastUpdatedAt)(Ordering[Instant].reverse)
  }

  /**
   * Возвращает все топики, отсортированные по последнему обновлению.
   */
  def getAllTopicsSorted: List[Topic] = {
    stateManager.getState.topics.sortBy(_.lastUpdatedAt)(Ordering[Instant].reverse)
  }

  /**
   * Находит топик по ID.
   */
  def findTopicById(topicId: String): Option[Topic] = {
    stateManager.getState.topics.find(_.id == topicId)
  }

  // --- Методы изменения состояния ---

  /**
   * Создает новый топик в указанной категории и обновляет состояние приложения.
   *
   * @param category Категория нового топика.
   * @param initialTitle Начальный заголовок (по умолчанию "Новый топик").
   * @return Success(newTopic), если создание и сохранение прошли успешно, иначе Failure(exception).
   */
  def createNewTopic(category: String, initialTitle: String = "Новый топик"): Try[Topic] = {
    logger.info(s"Attempting to create new topic in category '$category' with initial title '$initialTitle'.")
    val newTopic = Topic.createNew(category = category, initialTitle = initialTitle)

    // Выполняем обновление состояния через StateManager
    val updateResult: Try[Unit] = stateManager.updateState { currentState =>
      // Логика обновления состояния
      val updatedTopics = newTopic :: currentState.topics // Добавляем новый топик в начало списка
      currentState.copy(
        topics = updatedTopics,
        activeTopicId = Some(newTopic.id), // Новый топик становится активным
        lastActiveTopicPerCategory = currentState.lastActiveTopicPerCategory + (category -> newTopic.id) // Обновляем карту последнего активного
      )
    }

    // Преобразуем результат Try[Unit] в Try[Topic]
    updateResult.map(_ => newTopic) // Если updateState вернул Success, возвращаем Success(newTopic)
      .recoverWith { case NonFatal(e) =>
        logger.error(s"Failed to create or save new topic in category '$category'.", e)
        Failure(e) // Если updateState вернул Failure, пробрасываем его
      }
  }

  /**
   * Удаляет топик по ID и определяет следующий активный топик для его категории.
   *
   * @param topicId ID топика для удаления.
   * @return Success(Some(nextActiveId)) если удаление успешно и найден следующий активный,
   * Success(None) если удаление успешно, но нет следующего активного,
   * Failure(exception) если топик не найден или произошла ошибка сохранения.
   */
  def deleteTopic(topicId: String): Try[Option[String]] = {
    // Получаем текущее состояние *до* вызова updateState
    val currentState = stateManager.getState
    var nextActiveTopicIdDetermined: Option[String] = None // Для хранения определенного ID

    // Находим топик для удаления и его категорию
    currentState.topics.find(_.id == topicId) match {
      case Some(topicToDelete) =>
        logger.info(s"Preparing to delete topic: ${topicToDelete.title} (ID: $topicId, Category: ${topicToDelete.category})")
        val categoryOfDeletedTopic = topicToDelete.category
        val wasActive = currentState.activeTopicId.contains(topicId)

        // Определяем следующий активный ID *заранее*
        if (wasActive) {
          val remainingTopicsInCategory = currentState.topics.filter(t => t.id != topicId && t.category == categoryOfDeletedTopic)
          nextActiveTopicIdDetermined = currentState.lastActiveTopicPerCategory.get(categoryOfDeletedTopic)
            .filter(_ != topicId) // Убедимся, что это не удаляемый ID
            .filter(id => remainingTopicsInCategory.exists(_.id == id)) // Проверяем, что топик еще существует
            .orElse { // Если в карте нет или он удален, берем самый новый из оставшихся
              remainingTopicsInCategory.sortBy(_.lastUpdatedAt)(Ordering[Instant].reverse).headOption.map(_.id)
            }
          logger.debug(s"Determined next active topic ID for category '$categoryOfDeletedTopic' after deletion: ${nextActiveTopicIdDetermined.getOrElse("None")}")
        } else {
          // Если удаляемый не был активным, активный ID не меняется
          nextActiveTopicIdDetermined = currentState.activeTopicId
          logger.debug(s"Deleted topic was not active. Current active topic remains: ${nextActiveTopicIdDetermined.getOrElse("None")}")
        }

        // Обновляем состояние через StateManager
        val updateResult: Try[Unit] = stateManager.updateState { state =>
          // Логика обновления состояния (теперь проще, т.к. nextActiveId уже определен)
          val updatedAllTopics = state.topics.filterNot(_.id == topicId)
          // Обновляем карту lastActiveTopicPerCategory, удаляя запись для удаленного топика, если она там была
          val updatedLastActiveMap = state.lastActiveTopicPerCategory.filterNot { case (_, tId) => tId == topicId }

          // Возвращаем новое состояние
          state.copy(
            topics = updatedAllTopics,
            activeTopicId = nextActiveTopicIdDetermined, // Используем заранее определенный ID
            lastActiveTopicPerCategory = updatedLastActiveMap
          )
        }

        // Преобразуем результат Try[Unit] в Try[Option[String]]
        updateResult.map(_ => nextActiveTopicIdDetermined) // Возвращаем определенный ранее ID
          .recoverWith { case NonFatal(e) =>
            logger.error(s"Failed to delete or save state for topic ID '$topicId'.", e)
            Failure(e)
          }

      case None =>
        // Топик не найден
        val errorMsg = s"Topic with ID $topicId not found for deletion."
        logger.warn(errorMsg)
        Failure(new NoSuchElementException(errorMsg))
    }
  }

  /**
   * Добавляет диалог в указанный топик и обновляет время последнего изменения.
   * Делает этот топик активным.
   *
   * @param topicId ID топика, куда добавить диалог.
   * @param dialog Объект диалога для добавления.
   * @return Success(()) если обновление и сохранение успешно, иначе Failure(exception).
   */
  def addDialogToTopic(topicId: String, dialog: Dialog): Try[Unit] = {
    stateManager.updateState { currentState =>
      var foundTopicCategory: Option[String] = None // Для захвата категории
      val updatedTopics = currentState.topics.map { topic =>
        if (topic.id == topicId) {
          foundTopicCategory = Some(topic.category) // Захватываем категорию
          topic.copy(
            dialogs = topic.dialogs :+ dialog, // Добавляем в конец
            lastUpdatedAt = Instant.now() // Обновляем время
          )
        } else {
          topic
        }
      }

      // Если топик не был найден (не должно случиться, если ID валиден)
      if (foundTopicCategory.isEmpty && currentState.topics.exists(_.id == topicId)) {
        logger.error(s"Consistency error: Topic with ID $topicId exists but was not updated in map operation.")
        // Можно либо бросить исключение, либо продолжить с осторожностью
      }

      // Обновляем lastActive для категории этого топика, если она была найдена
      val updatedLastActiveMap = foundTopicCategory match {
        case Some(cat) => currentState.lastActiveTopicPerCategory + (cat -> topicId)
        case None =>
          logger.warn(s"Could not determine category for topic ID $topicId while adding dialog. lastActiveTopicPerCategory might be stale.")
          currentState.lastActiveTopicPerCategory // Не меняем карту, если категория неизвестна
      }

      // Топик, куда добавили диалог, становится активным
      currentState.copy(
        topics = updatedTopics,
        activeTopicId = Some(topicId), // Устанавливаем активный ID
        lastActiveTopicPerCategory = updatedLastActiveMap
      )
    }.recoverWith { case NonFatal(e) =>
      logger.error(s"Failed to add dialog to topic '$topicId' or save state.", e)
      Failure(e)
    }
  }

  /**
   * Устанавливает активный топик по ID. Обновляет lastActiveTopicPerCategory.
   *
   * @param topicIdOpt ID топика для активации (None для снятия активации).
   * @return Success(()) если обновление и сохранение успешно, иначе Failure(exception).
   */
  def setActiveTopic(topicIdOpt: Option[String]): Try[Unit] = {
    stateManager.updateState { currentState =>
      val currentActiveId = currentState.activeTopicId
      // Обновляем только если ID действительно изменился
      if (currentActiveId != topicIdOpt) {
        logger.info(s"Setting active topic ID from ${currentActiveId.getOrElse("None")} to ${topicIdOpt.getOrElse("None")}")
        // Находим категорию нового активного топика (если он есть)
        val topicCategoryOpt = topicIdOpt.flatMap(id => currentState.topics.find(_.id == id).map(_.category))

        // Обновляем карту lastActive только если выбран конкретный топик
        val updatedLastActiveMap = topicCategoryOpt match {
          case Some(category) => currentState.lastActiveTopicPerCategory + (category -> topicIdOpt.get)
          case None => currentState.lastActiveTopicPerCategory // Не меняем, если сбрасываем активный
        }
        currentState.copy(
          activeTopicId = topicIdOpt,
          lastActiveTopicPerCategory = updatedLastActiveMap
        )
      } else {
        logger.trace(s"Requested active topic ID (${topicIdOpt.getOrElse("None")}) is the same as current. No state change.")
        currentState // Состояние не изменилось, возвращаем его же
      }
    }.recoverWith { case NonFatal(e) =>
      logger.error(s"Failed to set active topic to '${topicIdOpt.getOrElse("None")}' or save state.", e)
      Failure(e)
    }
  }

  /**
   * Генерирует заголовок для топика на основе первого запроса и обновляет состояние.
   * Выполняется асинхронно.
   *
   * @param topicId ID топика для генерации заголовка.
   * @param firstRequest Текст первого запроса пользователя в этом топике.
   * @param apiKey API ключ для вызова AI.
   * @return Future[Try[Unit]], где Success(()) означает успешное обновление заголовка (или отсутствие необходимости в нем),
   * а Failure содержит ошибку генерации или сохранения.
   */
  def generateAndSetTopicTitle(topicId: String, firstRequest: String, apiKey: String): Future[Try[Unit]] = {
    logger.info(s"Attempting to generate title for topic ID '$topicId'...")
    val titlePrompt = titleGenerationPromptTemplate.replace("{{INPUT}}", firstRequest)

    // Сохраняем предыдущую модель ПЕРЕД временной сменой
    // aiService.updateModel теперь возвращает СТАРОЕ имя модели (String)
    val previousModel: String = aiService.updateModel(titleGenerationModel)
    logger.debug(s"Temporarily switched model to '$titleGenerationModel' for title generation. Previous was '$previousModel'.")

    // Выполняем запрос на генерацию заголовка
    aiService.process(titlePrompt, apiKey, Some(0.2), Some(0.9), Some(20), List.empty)
      .flatMap { generatedTitleRaw => // Используем flatMap для цепочки Future[Try[Unit]]
        // Обрабатываем результат
        val generatedTitle = generatedTitleRaw.trim.replaceAll("[\"']", "")
        if (generatedTitle.nonEmpty) {
          logger.info(s"Generated title for topic $topicId: '$generatedTitle'. Updating state.")
          // Обновляем состояние с новым заголовком через StateManager
          // stateManager.updateState возвращает Try[Unit]
          val updateTry = stateManager.updateState { currentState =>
            currentState.copy(
              topics = currentState.topics.map { t =>
                if (t.id == topicId) t.copy(title = generatedTitle) else t
              }
            )
          }
          Future.successful(updateTry) // Оборачиваем результат Try[Unit] в Future
        } else {
          logger.warn(s"Generated title for topic $topicId was empty. No state update.")
          Future.successful(Success(())) // Считаем успехом, просто ничего не обновили
        }
      }
      .recoverWith {
        // Обрабатываем ошибки генерации (из aiService.process)
        case NonFatal(e) =>
          logger.warn(s"Title generation failed for topic $topicId.", e)
          Future.successful(Failure(e)) // Возвращаем ошибку как Failure внутри Future
      }
      .andThen { // Выполняется ПОСЛЕ завершения Future (успех или неудача)
        // Восстанавливаем предыдущую модель в AIService НЕЗАВИСИМО от результата
        case result => // result это Try[Try[Unit]] или Failure, но нам не важно
          logger.debug(s"Restoring previous AI model to '$previousModel' after title generation attempt.")
          // Восстанавливаем предыдущую модель, используя сохраненное имя (String)
          aiService.updateModel(previousModel)
          // Логируем итоговый результат операции
          result match {
            case Success(Success(_)) => logger.info(s"Title update/generation for topic $topicId completed successfully.")
            case Success(Failure(updateErr)) => logger.error(s"Title generated for topic $topicId, but failed to update state.", updateErr)
            case Failure(genError) => logger.error(s"Title generation failed for topic $topicId.", genError)
            case _ => // На всякий случай
          }
      }
  }


  // --- Вспомогательные методы (могут быть приватными) ---

  /**
   * Определяет ID топика, который должен стать активным при переключении на категорию.
   * Сначала ищет последний активный в карте, затем самый новый в категории.
   */
  def determineActiveTopicForCategory(category: String): Option[String] = {
    val state = stateManager.getState
    val topicsInCategory = state.topics.filter(_.category == category)

    state.lastActiveTopicPerCategory.get(category) // Ищем в карте
      .filter(id => topicsInCategory.exists(_.id == id)) // Проверяем, что он существует в текущем списке топиков
      .orElse { // Если нет или не найден в карте, берем самый новый
        topicsInCategory
          .sortBy(_.lastUpdatedAt)(Ordering[Instant].reverse)
          .headOption.map(_.id)
      }
  }
}
