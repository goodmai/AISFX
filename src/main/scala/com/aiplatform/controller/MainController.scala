package com.aiplatform.controller

import org.apache.pekko.actor.typed.ActorSystem
import com.aiplatform.model.*
import com.aiplatform.repository.StateRepository
import com.aiplatform.service.{AIService, ModelFetchingService}
import com.aiplatform.view.{CurrentSettings, DialogUtils, Header, HistoryPanel, RequestArea, ResponseArea, SettingsView}
import com.typesafe.config.ConfigFactory
import scalafx.application.Platform
import scalafx.scene.control.ButtonType
import scalafx.scene.layout.BorderPane
import scalafx.scene.Parent
import org.slf4j.LoggerFactory
import scalafx.stage.Stage
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal
import java.time.Instant

// Компаньон-объект MainController
object MainController {
  val titleGenerationModel = "gemini-1.5-flash-latest" // Модель для генерации заголовков
  val titleGenerationPrompt = "Кратко (2-5 слов) опиши суть следующего запроса, чтобы использовать как заголовок в истории диалогов:\n\"{{INPUT}}\"\nЗаголовок:" // Промпт для заголовков

  // Фабричный метод для создания контроллера
  def apply()(implicit system: ActorSystem[_]): MainController =
    new MainController()
}

// Основной класс контроллера
class MainController(implicit system: ActorSystem[_]) {

  private val logger = LoggerFactory.getLogger(getClass)
  private implicit val ec: ExecutionContext = system.executionContext

  // --- Состояние Приложения (AppState) ---
  private var _appState: AppState = Try(StateRepository.loadState()) match {
    case Success(state) =>
      logger.info("Application state loaded successfully.")
      val validatedState = validateAndFixLoadedState(state) // Валидация и исправление загруженного состояния
      logger.info(s"Initial active category after validation: ${determineInitialCategory(validatedState)}, Active Topic ID: ${validatedState.activeTopicId.getOrElse("None")}")
      validatedState
    case Failure(e) =>
      logger.error("Failed to load application state from repository, using initial state.", e)
      AppState.initialState // Используем дефолтное состояние при ошибке загрузки
  }
  // Публичный getter для appState (только для чтения), если нужен внешнему коду (стараться избегать)
  // def appState: AppState = _appState

  // --- Конфигурация и Сервисы ---
  private val config = ConfigFactory.load()
  private val aiService = new AIService()(system.classicSystem) // Передаем classic system
  private val modelFetchingService = new ModelFetchingService()(system, ec) // Передаем typed system

  // --- API Ключ и Ссылки на UI ---
  private var currentApiKey: String = Try(config.getString("ai.gemini.api-key")).recover {
    case e: com.typesafe.config.ConfigException.Missing => logger.warn("API key 'ai.gemini.api-key' not found in application.conf."); ""
    case NonFatal(e) => logger.error("Failed to read API key from config", e); ""
  }.getOrElse("")

  private var mainStage: Option[Stage] = None // Ссылка на главное окно
  private var historyPanelRef: Option[HistoryPanel.type] = None // Ссылка на объект HistoryPanel
  private var headerRef: Option[Header] = None // Ссылка на объект Header
  // Активное имя категории (кнопки хедера)
  private var activeCategoryName: String = Header.buttonNames.head // Инициализация первой кнопкой

  // --- Активный Пресет ---
  // Ленивое вычисление активного пресета на основе текущей категории
  private def activePreset: PromptPreset = findActivePresetForButton(activeCategoryName)

  // --- Инициализация Контроллера ---
  fetchModelsAndUpdateState() // Загрузка моделей при старте
  activeCategoryName = determineInitialCategory(_appState) // Определяем начальную категорию на основе состояния
  updateAiServiceWithCurrentModel() // Установка модели в сервисе при старте
  logger.info(s"Controller initialized. Initial active category set to: '$activeCategoryName'")


  // --- Валидация Загруженного Состояния ---
  private def validateAndFixLoadedState(loadedState: AppState): AppState = {
    // 1. Определить начальную категорию на основе загруженного состояния
    val initialCategory = determineInitialCategory(loadedState)
    logger.debug(s"Validating loaded state. Determined initial category: '$initialCategory'")

    // 2. Отфильтровать топики для этой начальной категории
    val topicsForInitialCategory = loadedState.topics.filter(_.category == initialCategory)

    // 3. Проверить, принадлежит ли загруженный activeTopicId этой категории
    val validActiveTopicIdForCategory = loadedState.activeTopicId.filter { id =>
      topicsForInitialCategory.exists(_.id == id)
    }

    // 4. Определить финальный activeTopicId:
    //    - Сначала пробуем валидный ID из шага 3.
    //    - Потом пробуем последний активный для этой категории из карты `lastActiveTopicPerCategory`.
    //    - Потом пробуем самый свежий топик в этой категории.
    //    - Иначе None.
    val finalActiveTopicId = validActiveTopicIdForCategory
      .orElse {
        loadedState.lastActiveTopicPerCategory.get(initialCategory)
          .filter(id => topicsForInitialCategory.exists(_.id == id)) // Проверяем существование в списке
          .map { id => logger.debug(s"Using last active topic ID '$id' from map for category '$initialCategory'"); id }
      }
      .orElse {
        topicsForInitialCategory
          .sortBy(_.lastUpdatedAt)(Ordering[Instant].reverse) // Сортируем по убыванию даты
          .headOption // Берем самый свежий
          .map { topic => logger.debug(s"Using most recent topic ID '${topic.id}' for category '$initialCategory'"); topic.id }
      }

    if (loadedState.activeTopicId != finalActiveTopicId) {
      logger.warn(s"Loaded activeTopicId '${loadedState.activeTopicId.getOrElse("None")}' is invalid or does not belong to the initial category '$initialCategory'. Resetting active topic to '${finalActiveTopicId.getOrElse("None")}'.")
    }

    // 5. Проверить `lastActiveTopicPerCategory`: удалить записи, ссылающиеся на несуществующие топики
    val allTopicIds = loadedState.topics.map(_.id).toSet // Множество всех существующих ID
    val cleanedLastActiveMap = loadedState.lastActiveTopicPerCategory.filter { case (category, topicId) =>
      val exists = allTopicIds.contains(topicId)
      if (!exists) logger.warn(s"Removing entry ('$category' -> '$topicId') from lastActiveTopicPerCategory map as topic does not exist.")
      exists
    }

    // Возвращаем исправленное состояние
    loadedState.copy(
      activeTopicId = finalActiveTopicId,
      lastActiveTopicPerCategory = cleanedLastActiveMap
    )
  }

  // --- Определение Начальной Категории ---
  private def determineInitialCategory(state: AppState): String = {
    // Приоритет: Категория активного топика (если он есть и его категория валидна)
    state.activeTopicId
      .flatMap(id => state.topics.find(_.id == id).map(_.category)) // Получаем категорию активного топика
      .filter(cat => Header.buttonNames.contains(cat)) // Проверяем, что это валидная кнопка
      .getOrElse {
        // Иначе: Первая кнопка из списка (кроме "Settings")
        Header.buttonNames.find(_ != "Settings").getOrElse {
          logger.error("Could not determine initial category: Header.buttonNames is empty or only contains 'Settings'. Falling back to 'Default'.")
          "Default" // Самый крайний случай
        }
      }
  }


  // --- Обновление Модели в AIService ---
  private def updateAiServiceWithCurrentModel(): Unit = {
    // Определяем модель: пресет активной категории -> глобальная -> первая доступная
    val presetModelOpt = activePreset.modelOverride
    val globalModelOpt = Option(_appState.globalAiModel).filter(_.nonEmpty)
    val firstAvailableOpt = _appState.availableModels.headOption.map(_.name)

    val modelToUse = presetModelOpt.orElse(globalModelOpt).orElse(firstAvailableOpt).getOrElse {
      logger.error("Cannot determine AI model: No preset override, no global model, no available models. Using hardcoded fallback.")
      "gemini-1.5-flash-latest" // Абсолютный fallback
    }

    // Проверяем, существует ли модель в списке доступных (если список не пуст)
    if (_appState.availableModels.nonEmpty && !_appState.availableModels.exists(_.name == modelToUse)) {
      val fallbackModel = _appState.availableModels.head.name
      logger.warn(s"Model '$modelToUse' is selected but not found in available models. Falling back to '$fallbackModel'.")
      aiService.updateModel(fallbackModel)
    } else if (modelToUse.nonEmpty) {
      logger.info(s"Setting AI service model to: '$modelToUse' (Preset: ${presetModelOpt.isDefined}, Global: ${globalModelOpt.isDefined})")
      aiService.updateModel(modelToUse)
    } else {
      logger.error("Failed to set any model in AIService: Determined model name is empty.")
    }
  }


  // --- Методы Поиска Пресетов ---
  private def findPresetByName(name: String): Option[PromptPreset] = {
    _appState.customPresets.find(_.name.equalsIgnoreCase(name))
      .orElse(_appState.defaultPresets.find(_.name.equalsIgnoreCase(name)))
  }

  private def findActivePresetForButton(buttonName: String): PromptPreset = {
    _appState.buttonMappings.get(buttonName)
      .flatMap(findPresetByName)
      .getOrElse {
        logger.warn(s"No preset mapped or found for category '$buttonName'. Falling back to the first default preset.")
        _appState.defaultPresets.headOption.orElse(_appState.customPresets.headOption).getOrElse {
          logger.error("CRITICAL: No presets (default or custom) defined. Using hardcoded fallback.")
          PromptPreset("Fallback", "{{INPUT}}", isDefault = true) // Fallback должен быть isDefault=true?
        }
      }
  }

  // --- Загрузка Списка Моделей AI ---
  private def fetchModelsAndUpdateState(): Unit = {
    if (currentApiKey.nonEmpty) {
      logger.info("Attempting to fetch available AI models...")
      modelFetchingService.fetchAvailableModels(currentApiKey).onComplete {
        case Success(fetchedModels) if fetchedModels.nonEmpty =>
          logger.info(s"Successfully fetched ${fetchedModels.size} AI models.")
          Platform.runLater {
            val sortedFetchedModels = fetchedModels.sortBy(_.displayName)
            val currentGlobalModel = _appState.globalAiModel
            val currentAvailableModels = _appState.availableModels
            // Проверяем, существует ли текущая глобальная модель в новом списке
            val currentGlobalModelExists = sortedFetchedModels.exists(_.name == currentGlobalModel)
            // Выбираем новую глобальную модель: либо текущую (если она есть), либо первую из нового списка
            val newGlobalModel = if (currentGlobalModelExists) currentGlobalModel else sortedFetchedModels.headOption.map(_.name).getOrElse("")

            // Обновляем состояние только если список моделей изменился ИЛИ глобальная модель должна измениться
            if (currentAvailableModels != sortedFetchedModels || _appState.globalAiModel != newGlobalModel) {
              logger.info(s"Updating available models list (new size: ${sortedFetchedModels.size}) or global model (new: '$newGlobalModel').")
              _appState = _appState.copy(
                availableModels = sortedFetchedModels,
                globalAiModel = newGlobalModel
              )
              updateAiServiceWithCurrentModel() // Обновляем модель в сервисе
              saveState() // Сохраняем изменения
              // TODO: Обновить UI настроек, если оно открыто (передать новый список моделей)
            } else {
              logger.debug("Fetched models and global model are the same as current. No state update needed.")
              // На всякий случай убедимся, что сервис использует правильную модель
              updateAiServiceWithCurrentModel()
            }
          }
        case Success(_) => logger.warn("Fetched model list is empty. Keeping existing models in state (if any).")
        case Failure(exception) =>
          logger.error("Failed to fetch AI models.", exception)
          mainStage.foreach(s => Platform.runLater(() => DialogUtils.showError(s"Не удалось загрузить список моделей AI:\n${exception.getMessage}", s)))
      }
    } else {
      logger.warn("Cannot fetch models on startup: API Key is not configured.")
      mainStage.foreach(s => Platform.runLater(() => DialogUtils.showWarning("API ключ не настроен. Список моделей AI может быть неактуален.", s)))
    }
  }


  /**
   * Обрабатывает текстовый запрос пользователя.
   */
  def processRequest(requestText: String): Boolean = {
    logger.debug("Processing request: '{}' in category '{}' with preset '{}'", requestText.take(50), activeCategoryName, activePreset.name)
    validateInput(requestText) match {
      case Some(error) =>
        logger.warn("Invalid input: {}", error)
        Platform.runLater(() => ResponseArea.showError(error))
        false // Запрос не принят

      case None => // Ввод валиден
        if (currentApiKey.isEmpty) {
          logger.error("Cannot process request: API Key is not set.")
          Platform.runLater(() => ResponseArea.showError("API ключ не настроен в Настройках."))
          false // Запрос не принят
        } else { // Ключ есть
          Platform.runLater(() => ResponseArea.showLoadingIndicator()) // Показываем статус "Обработка..."

          // Определяем/создаем активный топик ДЛЯ ТЕКУЩЕЙ КАТЕГОРИИ
          val (targetTopicId: String, topicHistory: List[Dialog], isNewTopic: Boolean) = _appState.activeTopicId match {
            case Some(id) => _appState.topics.find(t => t.id == id && t.category == activeCategoryName) match {
              case Some(topic) => // Активный топик найден и принадлежит текущей категории
                logger.debug(s"Using existing active topic: ${topic.title} (ID: $id) in category '$activeCategoryName'")
                (id, topic.dialogs, false)
              case None => // Активный ID есть, но топик не найден или не принадлежит категории -> создаем новый
                logger.warn(s"Active topic ID '$id' not found or does not belong to category '$activeCategoryName'. Creating a new topic.")
                createNewTopicAndUpdateState() // Выносим создание в отдельный метод
            }
            case None => // Нет активного топика -> создаем новый
              logger.info(s"No active topic found for category '$activeCategoryName'. Creating a new one.")
              createNewTopicAndUpdateState() // Выносим создание в отдельный метод
          }

          // Формирование промпта и определение модели
          val finalPrompt = activePreset.prompt.replace("{{INPUT}}", requestText)
          val modelToUse = activePreset.modelOverride.getOrElse(_appState.globalAiModel)
          aiService.updateModel(modelToUse) // Устанавливаем модель перед запросом
          logger.trace("Starting main AI call. Model: '{}', Preset: '{}', Topic ID: '{}', History Size: {}", modelToUse, activePreset.name, targetTopicId, topicHistory.size)

          // Асинхронный вызов AIService
          val mainResponseFuture: Future[String] = aiService.process(finalPrompt, currentApiKey, Some(activePreset.temperature), Some(activePreset.topP), Some(activePreset.topK), topicHistory)

          // Асинхронная цепочка обработки: ответ AI -> генерация заголовка
          val processingPipeline: Future[(Dialog, String)] = for {
            mainResponse <- mainResponseFuture.recoverWith { // Обработка ошибок Future
              case NonFatal(e: Throwable) =>
                logger.error(s"Main AI request failed.", e)
                Future.failed(new Exception(s"Ошибка AI (${activePreset.name}/${modelToUse}): ${e.getMessage}", e))
            }
            _ = logger.info("Main AI call successful.")

            // Создаем объект Dialog
            dialog = Dialog(
              title = if(isNewTopic) s"Запрос: ${requestText.take(30)}..." else "Ход диалога", // Временный заголовок
              request = requestText,
              response = mainResponse,
              model = modelToUse // Сохраняем модель, использованную для ответа
            )

            // Генерируем заголовок для НОВОГО топика асинхронно (только для первого сообщения)
            generatedTitleOpt <- if (isNewTopic && topicHistory.isEmpty) {
              logger.info("Generating title for the new topic ID '{}'...", targetTopicId)
              val titlePrompt = MainController.titleGenerationPrompt.replace("{{INPUT}}", requestText)
              aiService.updateModel(MainController.titleGenerationModel) // Временно используем быструю модель
              aiService.process(titlePrompt, currentApiKey, Some(0.2), Some(0.9), Some(20), List.empty) // Без истории
                .map(title => Some(title.trim.replaceAll("[\"']", ""))) // Убираем кавычки из заголовка
                .recover { case NonFatal(e: Throwable) => logger.warn(s"Title generation failed for new topic $targetTopicId.", e); None }
            } else {
              Future.successful(None) // Не генерируем заголовок
            }

            // Восстанавливаем модель, которая использовалась для основного запроса
            _ = aiService.updateModel(modelToUse)

            // Обновляем заголовок топика в AppState (если он был сгенерирован)
            // Это происходит синхронно внутри map/flatMap цепочки Future
            _ = generatedTitleOpt.foreach { title =>
              if (title.nonEmpty) {
                logger.info(s"Updating title for topic $targetTopicId to '$title'")
                _appState = _appState.copy(
                  topics = _appState.topics.map { t => if (t.id == targetTopicId) t.copy(title = title) else t }
                )
                // Состояние еще не сохранено, сохранится после завершения всего pipeline
              } else {
                logger.warn(s"Generated title for topic $targetTopicId was empty. Keeping default title.")
              }
            }

          } yield (dialog, targetTopicId) // Возвращаем созданный Dialog и ID топика

          // Обработка результата всей асинхронной цепочки
          processingPipeline.onComplete {
            case Success((newDialog, updatedTopicId)) => // Вся цепочка успешно завершилась
              logger.info(s"Successfully processed request and potential title gen for topic ID '$updatedTopicId'.")
              // Обновляем состояние AppState: добавляем диалог в нужный топик
              val updatedTopics = _appState.topics.map { topic =>
                if (topic.id == updatedTopicId) { // Находим нужный топик
                  topic.copy( // Создаем обновленную копию топика
                    dialogs = topic.dialogs :+ newDialog, // Добавляем новый диалог
                    lastUpdatedAt = Instant.now() // Обновляем время последнего изменения
                  )
                } else {
                  topic // Оставляем другие топики без изменений
                }
              }
              // Сортируем *все* топики, чтобы сохранить общий порядок по дате
              val sortedAllTopics = updatedTopics.sortBy(_.lastUpdatedAt)(Ordering[Instant].reverse)
              // Обновляем состояние приложения
              _appState = _appState.copy(
                topics = sortedAllTopics, // Обновляем список и сортируем
                activeTopicId = Some(updatedTopicId), // Убедимся, что этот топик активен
                // Обновляем карту последнего активного топика для текущей категории
                lastActiveTopicPerCategory = _appState.lastActiveTopicPerCategory + (activeCategoryName -> updatedTopicId)
              )

              // Обновляем UI в потоке JavaFX
              Platform.runLater { () =>
                ResponseArea.addDialogTurn(newDialog.request, newDialog.response) // Отображаем новый ход диалога
                updateHistoryPanel() // Обновляем панель истории (отфильтрованный список)
                // HistoryPanel сам выделит активный топик, так как ID передается
                RequestArea.clearInput() // Очищаем поле ввода
              }
              // Сохраняем обновленное состояние в файл
              saveState()

            case Failure(exception: Throwable) => // Если где-то в цепочке Future произошла ошибка
              logger.error(s"Processing pipeline failed for topic ID '$targetTopicId'", exception)
              // Показываем ошибку пользователю в UI
              Platform.runLater(() => ResponseArea.showError(s"Ошибка обработки запроса: ${exception.getMessage}"))
          }
          true // Запрос был принят в обработку (Future запущен)
        }
    }
  }

  // Вспомогательный метод для создания нового топика и обновления состояния
  private def createNewTopicAndUpdateState(): (String, List[Dialog], Boolean) = {
    val newTopic = Topic.createNew(category = activeCategoryName) // Создаем с текущей категорией
    val updatedTopics = newTopic :: _appState.topics // Добавляем в общий список
    val sortedAllTopics = updatedTopics.sortBy(_.lastUpdatedAt)(Ordering[Instant].reverse) // Сортируем
    _appState = _appState.copy(
      topics = sortedAllTopics,
      activeTopicId = Some(newTopic.id), // Делаем активным
      lastActiveTopicPerCategory = _appState.lastActiveTopicPerCategory + (activeCategoryName -> newTopic.id) // Запоминаем
    )
    logger.info(s"New topic created (ID: ${newTopic.id}) and set active for category '$activeCategoryName'.")
    (newTopic.id, List.empty, true) // Возвращаем ID, пустую историю, флаг 'новый'
  }

  /** Обрабатывает нажатие кнопок в хедере. */
  def handleHeaderAction(buttonName: String): Unit = {
    logger.info("Header action triggered for button: {}", buttonName)
    buttonName match {
      case "Settings" => showSettingsWindow() // Открываем настройки
      case category if category != activeCategoryName => // Если нажата ДРУГАЯ кнопка категории
        logger.info(s"Switching category from '$activeCategoryName' to '$category'")
        activeCategoryName = category // Обновляем активную категорию

        // Пересчитываем активный пресет и обновляем модель в сервисе
        val newActivePreset = activePreset
        logger.info("New active preset for category '{}' is '{}'", category, newActivePreset.name)
        updateAiServiceWithCurrentModel()

        // Определяем, какой топик сделать активным в НОВОЙ категории
        val topicsInNewCategory = _appState.topics.filter(_.category == category)
        val targetTopicIdOpt = _appState.lastActiveTopicPerCategory.get(category) // Пытаемся взять последний активный
          .filter(id => topicsInNewCategory.exists(_.id == id)) // Проверяем, что он существует
          .orElse { // Иначе берем самый свежий в этой категории
            topicsInNewCategory.sortBy(_.lastUpdatedAt)(Ordering[Instant].reverse).headOption.map(_.id)
          }

        logger.debug(s"Topics in new category '$category': ${topicsInNewCategory.size}. Target active topic ID: ${targetTopicIdOpt.getOrElse("None")}")

        // Устанавливаем новый активный топик (или None) и обновляем UI
        Platform.runLater {
          setActiveTopic(targetTopicIdOpt.orNull) // setActiveTopic обновит ResponseArea
          updateHistoryPanel() // Обновляем HistoryPanel (покажет топики новой категории)
          // Очищаем поле ввода при смене категории?
          // RequestArea.clearInput()
        }
        // Сохраняем состояние (т.к. activeTopicId мог измениться в setActiveTopic)
        saveState()


      case _ => // Нажата та же самая кнопка категории (не Settings)
        logger.debug("Category button '{}' re-clicked (no state change).", buttonName)
    }
  }


  /** Создает новый пустой топик для ТЕКУЩЕЙ активной категории. */
  def startNewTopic(): Unit = {
    logger.info(s"User requested to start a new topic in category '$activeCategoryName'.")
    val (newTopicId, _, _) = createNewTopicAndUpdateState() // Создаем и обновляем состояние

    // Обновляем UI
    Platform.runLater {
      RequestArea.clearInput()
      ResponseArea.clearDialog() // Очищаем область диалога (т.к. топик новый и пустой)
      updateHistoryPanel() // Обновляем список, HistoryPanel выделит новый активный топик
    }
    saveState() // Сохраняем состояние
  }

  /**
   * Устанавливает активный топик по его ID.
   * Вызывается из HistoryPanel при выборе или из других методов контроллера.
   * @param topicId ID топика или null для снятия выбора.
   */
  def setActiveTopic(topicId: String): Unit = {
    val newActiveIdOpt: Option[String] = Option(topicId)
    val currentActiveIdOpt = _appState.activeTopicId

    // Находим сам объект топика по ID, если ID не null
    val targetTopicOpt: Option[Topic] = newActiveIdOpt.flatMap(id => _appState.topics.find(_.id == id))

    // Меняем состояние только если ID действительно изменился
    if (currentActiveIdOpt != newActiveIdOpt) {
      logger.info(s"Setting active topic ID to: ${newActiveIdOpt.getOrElse("None")}")

      // Определяем диалоги для отображения
      val dialogsToShow = targetTopicOpt.map(_.dialogs).getOrElse(List.empty)

      // Обновляем AppState
      _appState = _appState.copy(activeTopicId = newActiveIdOpt)

      // Обновляем карту последнего активного топика, если топик выбран и принадлежит текущей категории
      targetTopicOpt.filter(_.category == activeCategoryName).foreach { topic =>
        _appState = _appState.copy(
          lastActiveTopicPerCategory = _appState.lastActiveTopicPerCategory + (activeCategoryName -> topic.id)
        )
        logger.debug(s"Updated last active topic for category '$activeCategoryName' to ${topic.id}")
      }

      // Обновляем UI (ResponseArea и выделение в HistoryPanel)
      Platform.runLater {
        ResponseArea.displayTopicDialogs(dialogsToShow)
        historyPanelRef.foreach(_.selectTopic(newActiveIdOpt.orNull)) // Обновляем выделение в списке

        // Показываем сообщение, если активный топик сброшен
        if (newActiveIdOpt.isEmpty) {
          if (_appState.topics.exists(_.category == activeCategoryName)) {
            ResponseArea.showError("Выберите топик из списка.")
          } else {
            ResponseArea.showError("Создайте новый топик (+) для начала работы.")
          }
        }
      }
      saveState() // Сохраняем изменение активного топика
    } else {
      logger.trace(s"Requested active topic ID (${newActiveIdOpt.getOrElse("None")}) is the same as current. No change.")
    }
  }


  /** Удаляет топик по его ID. */
  def deleteTopic(topicId: String): Unit = {
    _appState.topics.find(_.id == topicId) match {
      case Some(topicToDelete) =>
        mainStage match {
          case Some(stage) =>
            // Запрашиваем подтверждение у пользователя
            DialogUtils.showConfirmation(s"Удалить топик '${topicToDelete.title}' (категория: ${topicToDelete.category})?", ownerWindow = stage, header="Удаление топика") match {
              case Some(ButtonType.OK) => // Пользователь подтвердил
                logger.info(s"User confirmed deletion of topic: ${topicToDelete.title} (ID: $topicId)")
                val categoryOfDeletedTopic = topicToDelete.category
                val wasActive = _appState.activeTopicId.contains(topicId) // Был ли удаляемый топик активным?

                // Удаляем топик из общего списка
                val updatedAllTopics = _appState.topics.filterNot(_.id == topicId)
                val remainingTopicsInCategory = updatedAllTopics.filter(_.category == categoryOfDeletedTopic)

                // Определяем новый активный топик (если удаляли активный)
                val newActiveTopicIdOpt: Option[String] = if (wasActive) {
                  // Пытаемся взять последний активный для этой категории из карты (если он остался)
                  _appState.lastActiveTopicPerCategory.get(categoryOfDeletedTopic)
                    .filter(id => remainingTopicsInCategory.exists(_.id == id))
                    .orElse { // Иначе берем самый свежий из оставшихся в категории
                      remainingTopicsInCategory.sortBy(_.lastUpdatedAt)(Ordering[Instant].reverse).headOption.map(_.id)
                    }
                } else {
                  _appState.activeTopicId // Оставляем текущий активный ID без изменений
                }

                // Обновляем карту lastActiveTopicPerCategory
                val updatedLastActiveMap = _appState.lastActiveTopicPerCategory.filterNot { case (_, tId) => tId == topicId } // Удаляем запись об удаленном
                val finalLastActiveMap = newActiveTopicIdOpt match {
                  // Если нашли новый активный топик для ЭТОЙ ЖЕ категории, обновляем карту
                  case Some(newActiveId) if remainingTopicsInCategory.exists(_.id == newActiveId) =>
                    updatedLastActiveMap + (categoryOfDeletedTopic -> newActiveId)
                  // Если в категории не осталось топиков или новый активный топик из другой категории, удаляем запись
                  case _ => updatedLastActiveMap - categoryOfDeletedTopic
                }


                // Обновляем состояние приложения
                _appState = _appState.copy(
                  topics = updatedAllTopics.sortBy(_.lastUpdatedAt)(Ordering[Instant].reverse), // Обновляем общий список
                  activeTopicId = newActiveTopicIdOpt, // Устанавливаем новый активный ID
                  lastActiveTopicPerCategory = finalLastActiveMap // Обновляем карту
                )
                saveState() // Сохраняем изменения

                // Обновляем UI
                Platform.runLater {
                  updateHistoryPanel() // Обновляем список истории (он отфильтруется по activeCategoryName)
                  // Устанавливаем новый активный топик (или сбрасываем, если None)
                  // setActiveTopic позаботится об обновлении ResponseArea и выделении в HistoryPanel
                  setActiveTopic(newActiveTopicIdOpt.orNull)
                }
                logger.info(s"Topic $topicId deleted. New active topic ID set to: ${newActiveTopicIdOpt.getOrElse("None")}")

              case _ => logger.debug(s"Deletion cancelled by user for topic $topicId.")
            }
          case None => logger.error("Cannot show confirmation dialog for topic deletion: main application stage is not available.")
        }
      case None => logger.warn(s"Attempted to delete a non-existent topic with ID: $topicId")
    }
  }

  // --- Окно Настроек ---
  private def showSettingsWindow(): Unit = {
    mainStage match {
      case Some(currentOwnerStage) =>
        logger.debug("Showing settings window...")
        val settings = CurrentSettings(
          apiKey = currentApiKey, // Передаем текущий ключ
          model = _appState.globalAiModel,
          fontFamily = _appState.fontFamily,
          fontSize = _appState.fontSize,
          availableModels = _appState.availableModels, // Передаем актуальный список моделей
          buttonMappings = _appState.buttonMappings,
          defaultPresets = _appState.defaultPresets,
          customPresets = _appState.customPresets
        )
        val settingsView = new SettingsView(currentOwnerStage, this, settings)
        settingsView.showAndWait() // Показываем окно и ждем закрытия

        // После закрытия окна (неважно, сохранили или отменили):
        // Обновляем активный пресет (мэппинги или сами пресеты могли измениться)
        val _ = activePreset
        // Обновляем модель в AI сервисе (глобальная модель или модель пресета могли измениться)
        updateAiServiceWithCurrentModel()
        logger.debug(s"Settings window closed. Active category: '$activeCategoryName', active preset: '${activePreset.name}'")
      case None => logger.error("Cannot open settings window: main application stage is not available.")
    }
  }

  // --- Управление Пресетами ---
  def saveCustomPreset(preset: PromptPreset): Unit = {
    // Проверка уникальности имени (среди стандартных и ДРУГИХ пользовательских)
    val isNameTaken = (_appState.defaultPresets ++ _appState.customPresets.filterNot(_.name.equalsIgnoreCase(preset.name)))
      .exists(_.name.equalsIgnoreCase(preset.name))
    if (isNameTaken) throw new IllegalArgumentException(s"Имя пресета '${preset.name}' уже используется.")

    val index = _appState.customPresets.indexWhere(_.name.equalsIgnoreCase(preset.name))
    val updatedList = if (index >= 0) {
      logger.info("Updating existing custom preset '{}'", preset.name)
      _appState.customPresets.updated(index, preset.copy(isDefault = false)) // Обновляем, isDefault = false
    } else {
      logger.info("Adding new custom preset '{}'", preset.name)
      _appState.customPresets :+ preset.copy(isDefault = false) // Добавляем, isDefault = false
    }
    // Обновляем состояние отсортированным списком
    _appState = _appState.copy(customPresets = updatedList.sortBy(_.name.toLowerCase))
    saveState() // Сохраняем
    logger.info("Custom preset list updated.")
    // Обновляем пресет/модель, если изменился активный
    if (activePreset.name.equalsIgnoreCase(preset.name)) {
      val _ = activePreset
      updateAiServiceWithCurrentModel()
    }
  }

  def saveDefaultPreset(preset: PromptPreset): Unit = {
    // Проверяем, что имя не конфликтует с пользовательскими
    val isNameTakenByCustom = _appState.customPresets.exists(_.name.equalsIgnoreCase(preset.name))
    if (isNameTakenByCustom) throw new IllegalArgumentException(s"Имя '${preset.name}' уже используется пользовательским пресетом.")

    // Находим индекс стандартного пресета для обновления (имя стандартного менять нельзя)
    val index = _appState.defaultPresets.indexWhere(_.name.equalsIgnoreCase(preset.name))
    if (index < 0) throw new NoSuchElementException(s"Стандартный пресет '${preset.name}' не найден для обновления.")

    logger.info("Updating existing default preset '{}'", preset.name)
    // Обновляем элемент в списке, убедившись, что isDefault = true
    val updatedList = _appState.defaultPresets.updated(index, preset.copy(isDefault = true))
    _appState = _appState.copy(defaultPresets = updatedList.sortBy(_.name.toLowerCase)) // Сохраняем отсортированный список
    saveState() // Сохраняем
    logger.info("Default preset list updated.")
    // Обновляем пресет/модель, если изменился активный
    if (activePreset.name.equalsIgnoreCase(preset.name)) {
      val _ = activePreset
      updateAiServiceWithCurrentModel()
    }
  }


  def deleteCustomPreset(presetName: String): Unit = {
    logger.info("Attempting to delete custom preset '{}'", presetName)
    _appState.customPresets.find(_.name.equalsIgnoreCase(presetName)) match {
      case Some(_) => // Пресет найден
        val updatedList = _appState.customPresets.filterNot(_.name.equalsIgnoreCase(presetName)) // Удаляем из списка
        // Обновляем мэппинги, удаляя ссылки на удаленный пресет
        val updatedMappings = _appState.buttonMappings.filterNot { case (_, mappedPresetName) =>
          mappedPresetName.equalsIgnoreCase(presetName)
        }
        // Обновляем состояние
        _appState = _appState.copy(
          customPresets = updatedList,
          buttonMappings = updatedMappings
        )
        saveState() // Сохраняем
        logger.info("Custom preset '{}' and associated button mappings deleted.", presetName)
        // Обновляем активный пресет и модель, так как они могли измениться
        val _ = activePreset
        updateAiServiceWithCurrentModel()
      case None => // Пресет не найден
        val errorMsg = s"Пользовательский пресет '$presetName' не найден для удаления."
        logger.warn(s"Delete custom preset failed: $errorMsg")
        throw new NoSuchElementException(errorMsg)
    }
  }

  // --- Обновление Настроек из Окна Настроек ---
  def updateApiKey(newKey: String): Unit = {
    val trimmedKey = newKey.trim
    if (trimmedKey.isEmpty) throw new IllegalArgumentException("API ключ не может быть пустым.")
    if (trimmedKey != currentApiKey) {
      logger.info("Updating API Key (in memory only). Length: {}", trimmedKey.length)
      currentApiKey = trimmedKey
      fetchModelsAndUpdateState() // Перезапрашиваем модели с новым ключом
    } else {
      logger.debug("API Key submitted from settings is the same as the current one.")
    }
  }

  def updateGlobalAIModel(newModelName: String): Unit = {
    val trimmedModelName = newModelName.trim
    if (trimmedModelName.isEmpty) throw new IllegalArgumentException("Имя глобальной модели не может быть пустым.")

    if (_appState.globalAiModel != trimmedModelName) { // Обновляем только если имя модели изменилось
      // Проверяем, существует ли модель с таким именем в списке доступных
      if (_appState.availableModels.exists(_.name == trimmedModelName)) {
        logger.info("Updating global AI model from '{}' to '{}'", _appState.globalAiModel, trimmedModelName)
        _appState = _appState.copy(globalAiModel = trimmedModelName) // Обновляем в состоянии
        updateAiServiceWithCurrentModel() // Обновляем модель в AI сервисе
        saveState() // Сохраняем состояние
      } else { // Если модель не найдена
        val errorMsg = s"Выбранная глобальная модель '$trimmedModelName' не найдена в списке доступных моделей."
        logger.error(s"Update global model failed: $errorMsg")
        throw new IllegalArgumentException(errorMsg) // Выбрасываем исключение
      }
    } else {
      logger.debug("Global AI model submitted from settings is the same as the current one.")
    }
  }

  def updateButtonMappings(newMappings: Map[String, String]): Unit = {
    // Проверяем валидность всех пресетов в новых мэппингах
    val allPresetNamesLower = (_appState.defaultPresets.map(_.name) ++ _appState.customPresets.map(_.name)).map(_.toLowerCase).toSet
    val invalidMappings = newMappings.filterNot { case (_, presetName) => allPresetNamesLower.contains(presetName.toLowerCase) }

    if (invalidMappings.isEmpty) { // Если все мэппинги валидны
      if (newMappings != _appState.buttonMappings) { // Обновляем только если есть изменения
        logger.info("Updating button-preset mappings based on settings.")
        _appState = _appState.copy(buttonMappings = newMappings) // Обновляем состояние
        saveState() // Сохраняем состояние
        // Обновляем активный пресет и модель в сервисе
        val _ = activePreset
        updateAiServiceWithCurrentModel()
        logger.debug(s"Button mappings updated. Current active category: '$activeCategoryName', new active preset for it: '${activePreset.name}'")
      } else {
        logger.debug("Button mappings submitted from settings are the same as the current ones.")
      }
    } else { // Если есть невалидные мэппинги
      val errors = invalidMappings.map { case (btn, preset) => s"Кнопка '$btn' -> Пресет '$preset' (не найден)" }.mkString("\n")
      logger.error(s"Update button mappings failed due to invalid presets:\n$errors")
      throw new IllegalArgumentException(s"Невозможно обновить назначения кнопок:\n$errors") // Выбрасываем исключение
    }
  }

  def updateFontSettings(fontFamily: String, fontSize: Int): Unit = {
    if (fontFamily != _appState.fontFamily || fontSize != _appState.fontSize) { // Обновляем только если есть изменения
      logger.info("Updating font settings to Family: '{}', Size: {}", fontFamily, fontSize)
      _appState = _appState.copy(fontFamily = fontFamily, fontSize = fontSize) // Обновляем состояние
      applyFontSettingsToVisibleElements(fontFamily, fontSize) // Применяем стиль (если реализовано)
      saveState() // Сохраняем состояние
    } else {
      logger.debug("Font settings submitted from settings are the same as the current ones.")
    }
  }

  // --- Создание UI ---
  def createUI(ownerStage: Stage): Parent = {
    logger.info("Creating application UI...")
    this.mainStage = Some(ownerStage) // Сохраняем ссылку на главное окно

    // Создаем компоненты UI
    val headerComponent = new Header(onHeaderButtonClicked = handleHeaderAction)
    this.headerRef = Some(headerComponent) // Сохраняем ссылку на Header
    val headerPanel = headerComponent.createHeaderNode() // Верхняя панель с кнопками
    val requestPanel = RequestArea.create(this) // Нижняя панель ввода
    val responsePanel = ResponseArea.create() // Центральная панель вывода
    this.historyPanelRef = Some(HistoryPanel) // Сохраняем ссылку на объект панели истории
    val historyPanelNode = HistoryPanel.create(this) // Левая панель истории

    // Применяем настройки шрифта (если нужно/реализовано)
    applyFontSettingsToVisibleElements(_appState.fontFamily, _appState.fontSize)

    // Инициализируем UI начальными данными после его создания
    Platform.runLater {
      logger.debug("Performing initial UI state synchronization.")
      // Устанавливаем активную кнопку в хедере на основе начальной категории
      headerRef.foreach(_.setActiveButton(activeCategoryName)) // Вызываем метод setActiveButton
      // Обновляем панель истории с учетом начальной категории
      updateHistoryPanel()
      // Загружаем диалоги активного топика (если он есть для начальной категории)
      val initialDialogs = _appState.activeTopicId
        .flatMap(id => _appState.topics.find(t => t.id == id && t.category == activeCategoryName)) // Проверяем категорию
        .map(_.dialogs)
        .getOrElse(List.empty)
      ResponseArea.displayTopicDialogs(initialDialogs)

      // Показываем сообщение, если нет активного топика для начальной категории
      if (_appState.activeTopicId.isEmpty) {
        if (_appState.topics.exists(_.category == activeCategoryName)) { // Есть ли топики в этой категории?
          ResponseArea.showError("Выберите топик из списка.")
        } else { // В этой категории нет топиков
          ResponseArea.showError("Создайте новый топик (+) для начала работы.")
        }
      } else {
        logger.info(s"Initial active topic ID: ${_appState.activeTopicId.get}")
      }
    }

    // Собираем основной макет приложения
    val centerArea = new BorderPane { // Центральная область
      styleClass.add("center-pane")
      center = responsePanel // Основное место - панель ответа
      bottom = requestPanel  // Внизу - панель ввода
    }
    // Корневой BorderPane всего окна
    new BorderPane {
      styleClass.add("main-pane")
      top = headerPanel         // Хедер сверху
      left = historyPanelNode   // Панель истории слева
      center = centerArea       // Центральная область
    }
  }

  // --- Вспомогательные Методы ---
  /** Обновляет панель истории, фильтруя топики по текущей активной категории. */
  private def updateHistoryPanel(): Unit = {
    historyPanelRef.foreach { panel =>
      // Фильтруем ВСЕ топики по текущей активной категории
      val topicsForCurrentCategory = _appState.topics.filter(_.category == activeCategoryName)
        .sortBy(_.lastUpdatedAt)(Ordering[Instant].reverse) // Сортируем для отображения
      logger.debug(s"Updating history panel for category '$activeCategoryName'. Topics count: ${topicsForCurrentCategory.size}. Active topic ID (globally): ${_appState.activeTopicId.getOrElse("None")}")
      // Передаем отфильтрованный список и ГЛОБАЛЬНЫЙ activeTopicId
      // HistoryPanel сам выберет нужный элемент, если он есть в отфильтрованном списке
      panel.updateTopics(topicsForCurrentCategory, _appState.activeTopicId)
    }
  }

  private def applyFontSettingsToVisibleElements(family: String, size: Int): Unit = {
    // Рекомендуется использовать CSS для стилизации
    logger.info(s"Apply font settings requested: Family=$family, Size=$size. (Styling via CSS is recommended)")
  }

  def saveState(): Unit = {
    logger.trace("Saving application state...") // Trace, т.к. вызывается часто
    StateRepository.saveState(_appState) // Вызов репозитория для сохранения
  }

  private def validateInput(text: String): Option[String] = {
    val trimmed = text.trim
    if (trimmed.isEmpty) Some("Запрос не может быть пустым.")
    else if (trimmed.length > 16000) Some(s"Запрос слишком длинный (${trimmed.length}/16000 символов).") // Лимит примерный
    else None // Валидация пройдена
  }

  def shutdown(): Unit = {
    logger.info("Shutting down AI Platform application...")
    saveState() // Сохраняем состояние перед выходом
    aiService.shutdown() // Освобождаем ресурсы AI сервиса
    logger.info("AI Service backend resources released.")
  }

} // Конец класса MainController