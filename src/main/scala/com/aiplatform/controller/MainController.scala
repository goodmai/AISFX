// src/main/scala/com/aiplatform/controller/MainController.scala
package com.aiplatform.controller

import com.aiplatform.controller.manager.{FileManager, PresetManager, RequestExecutionManager, StateManager, TopicManager}
import com.aiplatform.model.* // Импортируем все модели
import com.aiplatform.service.{AIService, CredentialsService, ModelFetchingService, InlineData}
import com.aiplatform.view.{CurrentSettings, DialogUtils, Footer, Header, HistoryPanel, ResponseArea, SettingsView}
import org.apache.pekko.actor.typed.ActorSystem
import scalafx.application.Platform
import scalafx.scene.control.ButtonType
import scalafx.scene.layout.BorderPane
import scalafx.scene.Parent
import org.slf4j.LoggerFactory
import scalafx.stage.Stage
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong} // Убираем AtomicLong, если fontApplyCounter удален
import java.io.{ByteArrayOutputStream, File}
import javafx.scene.image.Image
import javafx.embed.swing.SwingFXUtils
import javax.imageio.ImageIO


/**
 * Основной контроллер приложения. Координирует взаимодействие между UI (View),
 * сервисами и менеджерами данных/состояния.
 * Внедрены флаги для предотвращения рекурсивных вызовов synchronizeUIState.
 * Логика динамической установки шрифтов удалена.
 * Убран вызов synchronizeUIState из performInitialUISetup.
 *
 * @param system Неявная система акторов Pekko.
 */
class MainController(implicit system: ActorSystem[?]) {

  private val logger = LoggerFactory.getLogger(getClass)
  private implicit val ec: ExecutionContext = system.executionContext

  // --- Менеджеры и сервисы ---
  private val stateManager = new StateManager()
  private val aiService = new AIService()(system.classicSystem)
  private val topicManager = new TopicManager(stateManager, aiService)
  private val presetManager = new PresetManager(stateManager)
  private val requestExecutionManager = new RequestExecutionManager(
    stateManager, topicManager, presetManager, aiService
  )
  private val modelFetchingService = new ModelFetchingService()(system, ec)
  private var fileManager: Option[FileManager] = None

  // --- Ссылки на UI компоненты ---
  private var mainStage: Option[Stage] = None
  private var rootPane: Option[BorderPane] = None
  private var historyPanelRef: Option[HistoryPanel.type] = None
  private var headerRef: Option[Header] = None
  private var footerRef: Option[Footer] = None

  // --- Состояние UI ---
  private val isRequestInProgress = new AtomicBoolean(false)
  // Флаг для предотвращения цикла обратной связи от HistoryPanel -> setActiveTopic -> HistoryPanel.selectTopic
  private val isProgrammaticSelectionFlag = new AtomicBoolean(false)
  private var pendingImageData: Option[InlineData] = None

  // --- Флаги для предотвращения рекурсии ---
  private val isSyncingUI = new AtomicBoolean(false) // Флаг для synchronizeUIState
  // private val fontApplyScheduled = new AtomicBoolean(false) // <<< УДАЛЕНО >>>

  // --- Счетчики для отладки ---
  private val syncCounter = new AtomicLong(0)
  // private val fontApplyCounter = new AtomicLong(0) // <<< УДАЛЕНО >>>

  /** Публичный getter для флага программного выбора. */
  def getIsProgrammaticSelection: Boolean = isProgrammaticSelectionFlag.get


  //<editor-fold desc="Инициализация и Жизненный Цикл">

  initializeController() // Вызываем инициализацию при создании контроллера

  /** Асинхронная инициализация контроллера (загрузка моделей). */
  private def initializeController(): Unit = {
    logger.info("Controller initialization started.")
    // Асинхронно загружаем модели и обновляем состояние
    fetchModelsAndUpdateState().andThen { case result =>
      // После завершения загрузки (успех или неудача)
      updateAiServiceWithCurrentModel() // Обновляем модель в AIService
      // Не вызываем synchronizeUIState здесь, MainApp вызовет performInitialUISetup позже
      result match {
        case Success(_) => logger.info("Initial model fetch and AI service update complete.")
        case Failure(e) => logger.error("Initial model fetch failed, continuing initialization.", e) // Не прерываем запуск
      }
    }
  }

  /**
   * Создает основной UI приложения. Вызывается из MainApp.
   */
  def createUI(ownerStage: Stage): Parent = {
    logger.info("Creating application UI structure...")
    this.mainStage = Some(ownerStage) // Сохраняем ссылку на Stage

    // --- Создание компонентов View ---
    val headerComponent = new Header(onHeaderButtonClicked = handleHeaderAction)
    this.headerRef = Some(headerComponent)
    val headerPanel = headerComponent.createHeaderNode()

    val footerComponent = new Footer(
      onSend = processUserInput,
      onNewTopic = startNewTopic,
      onAttachFileClick = () => fileManager.foreach(_.attachTextFile()),
      onAttachCodeClick = () => fileManager.foreach(_.attachFolderContext()),
      onFileDropped = handleFileDropped,
      onDirectoryDropped = handleDirectoryDropped,
      onImagePasted = handleImagePasted
    )
    this.footerRef = Some(footerComponent)
    val footerPanel = footerComponent.createFooterNode()

    // FileManager создается здесь, передаем Stage и Option[Footer]
    this.fileManager = Some(new FileManager(ownerStage, this.footerRef))

    val responsePanel = ResponseArea.create() // ResponseArea - объект-компаньон
    this.historyPanelRef = Some(HistoryPanel) // HistoryPanel - объект-компаньон
    val historyPanelNode = HistoryPanel.create(this) // Передаем ссылку на текущий контроллер

    // --- Сборка основного Layout ---
    val centerArea = new BorderPane { styleClass.add("center-pane"); center = responsePanel }
    val root = new BorderPane {
      styleClass.add("main-pane")
      top = headerPanel
      left = historyPanelNode
      center = centerArea
      bottom = footerPanel
    }
    this.rootPane = Some(root) // Сохраняем ссылку

    logger.info("Application UI structure created and returned from createUI.")
    root // Возвращаем корневой узел
  }

  /**
   * Метод для выполнения начальной настройки UI ПОСЛЕ его создания и показа.
   * Вызывается из MainApp через Platform.runLater.
   * БОЛЬШЕ НЕ ВЫЗЫВАЕТ synchronizeUIState.
   */
  def performInitialUISetup(): Unit = {
    logger.info(">>> Performing Initial UI Setup...")
    // synchronizeUIState() // <<< УДАЛЕН ВЫЗОВ СИНХРОНИЗАЦИИ >>>
    logger.info(">>> Initial UI Setup tasks completed (no synchronization called).")
  }

  /* <<< Метод applyFontSettingsToVisibleElements полностью удален >>> */

  /** Выполняет действия при завершении работы приложения. */
  def shutdown(): Unit = {
    logger.info("Shutting down AI Platform application...")
    // Принудительно сохраняем состояние
    stateManager.forceSaveState().failed.foreach { e =>
      logger.error("Failed to save state during shutdown.", e)
    }
    // Освобождаем ресурсы AIService
    aiService.shutdown()
    logger.info("AI Service backend resources released.")
    logger.info("Shutdown complete.")
  }

  //</editor-fold>

  //<editor-fold desc="Синхронизация Состояния и UI">

  /**
   * Полностью синхронизирует UI с текущим состоянием AppState.
   * Использует флаг isSyncingUI для предотвращения рекурсивных/параллельных вызовов.
   */
  def synchronizeUIState(): Unit = {
    val callId = syncCounter.incrementAndGet()

    // Проверяем и устанавливаем флаг "синхронизация выполняется" атомарно
    if (!isSyncingUI.compareAndSet(false, true)) {
      logger.warn(s"[$callId] synchronizeUIState aborted: already syncing (flag was true).")
      return
    }

    // Используем try/finally для гарантированного сброса флага
    logger.info(s"[$callId] >>> Starting UI synchronization...")
    try {
      // Гарантируем выполнение в FX потоке
      if (!Platform.isFxApplicationThread) {
        logger.warn(s"[$callId] synchronizeUIState called from non-FX thread. Rescheduling with Platform.runLater.")
        isSyncingUI.set(false)
        Platform.runLater {
          logger.debug(s"[$callId] Retrying synchronizeUIState inside Platform.runLater...")
          synchronizeUIState()
        }
        return
      }

      // Мы в FX потоке и флаг isSyncingUI установлен в true
      logger.info(s"[$callId] Synchronizing UI state on thread: ${Thread.currentThread().getName}")
      val state = currentAppState
      val currentCategory = activeCategoryName
      val activeTopicIdOpt = state.activeTopicId
      logger.debug(s"[$callId] State: ActiveCategory='$currentCategory', ActiveTopicID='${activeTopicIdOpt.getOrElse("None")}'")

      // Обновляем UI компоненты
      logger.debug(s"[$callId] Updating Header...")
      headerRef.foreach(_.setActiveButton(currentCategory))
      logger.debug(s"[$callId] Updating History Panel...")
      updateHistoryPanel(currentCategory, activeTopicIdOpt)
      logger.debug(s"[$callId] Updating Response Area...")
      updateResponseArea(activeTopicIdOpt, currentCategory)
      logger.debug(s"[$callId] Updating Footer State...")
      updateFooterState(isRequestInProgress.get())

      logger.info(s"[$callId] <<< UI state synchronization finished.")

    } catch {
      case NonFatal(e) =>
        logger.error(s"[$callId] synchronizeUIState failed with exception.", e)
    } finally {
      isSyncingUI.set(false)
      logger.trace(s"[$callId] Reset isSyncingUI flag to false.")
    }
  }

  /** Обновляет список топиков в HistoryPanel. */
  private def updateHistoryPanel(category: String, activeTopicIdOpt: Option[String]): Unit = {
    historyPanelRef.foreach { panel =>
      val topics = topicManager.getTopicsForCategory(category)
      logger.debug(s"Updating history panel: Category='$category', Topics=${topics.size}, ActiveID=${activeTopicIdOpt.getOrElse("None")}")
      panel.updateTopics(topics, activeTopicIdOpt)
    }
  }

  /** Обновляет ResponseArea на основе активного топика. */
  private def updateResponseArea(activeTopicIdOpt: Option[String], currentCategory: String): Unit = {
    val topicOpt = activeTopicIdOpt.flatMap(topicManager.findTopicById)
    val dialogsToShow = topicOpt.map(_.dialogs).getOrElse(List.empty)
    logger.debug(s"Updating response area: ActiveID=${activeTopicIdOpt.getOrElse("None")}, Dialogs=${dialogsToShow.size}")
    if (activeTopicIdOpt.isDefined) {
      ResponseArea.displayTopicDialogs(dialogsToShow)
      if (dialogsToShow.isEmpty) ResponseArea.showStatus("Введите ваш первый запрос в этом топике.")
    } else {
      if (topicManager.getTopicsForCategory(currentCategory).isEmpty) ResponseArea.showError("Создайте новый топик (+) для начала работы в этой категории.")
      else ResponseArea.showError("Выберите топик из списка слева.")
    }
  }

  /** Блокирует/разблокирует Footer. */
  private def updateFooterState(locked: Boolean): Unit = {
    footerRef.foreach(_.setLocked(locked))
    logger.trace(s"Footer state updated. Locked: $locked")
  }

  //</editor-fold>

  //<editor-fold desc="Обработка Действий Пользователя (UI Events)">

  /** Обрабатывает нажатия кнопок в Header. */
  def handleHeaderAction(buttonName: String): Unit = {
    logger.info("Header action triggered for button: {}", buttonName)
    if (!isRequestInProgress.get()) {
      if (buttonName == "Settings") {
        showSettingsWindow()
      } else if (Header.categoryButtonNames.contains(buttonName)) {
        val currentCategory = activeCategoryName
        if (buttonName != currentCategory) {
          logger.info(s"Switching active category from '$currentCategory' to '$buttonName'")
          val nextActiveTopicIdOpt = topicManager.determineActiveTopicForCategory(buttonName)
          setActiveTopic(nextActiveTopicIdOpt)
        } else {
          logger.debug("Category button '{}' re-clicked. No category change.", buttonName)
        }
      } else {
        logger.warn("Unhandled header button action: {}", buttonName)
      }
    } else {
      logger.warn("Ignoring header action while request is in progress.")
    }
  }

  /** Обрабатывает отправку текста из Footer. */
  private def processUserInput(inputText: String): Unit = {
    val trimmedText = inputText.trim
    logger.debug("Processing user input: '{}'", trimmedText)
    if (!isRequestInProgress.get()) {
      val imageDataToSend = pendingImageData; pendingImageData = None
      if (trimmedText.isEmpty && imageDataToSend.isEmpty) {
        logger.warn("Attempted to send empty input (no text and no pending image).")
        showErrorAlert("Запрос не может быть пустым (введите текст или вставьте изображение).")
      } else {
        validateInputLength(trimmedText).orElse(if (imageDataToSend.isEmpty && trimmedText.isEmpty) Some("Пустой запрос") else None) match {
          case Some(error) => logger.warn("Input validation failed: {}", error); showErrorAlert(error)
          case None => getApiKey() match {
            case None => logger.error("API Key missing."); showErrorAlert("API ключ не найден. Добавьте его в настройках.")
            case Some(apiKey) =>
              val categoryHint = if (activeCategoryName == "Global") None else Some(activeCategoryName)
              executeRequest(trimmedText, categoryHint, apiKey, imageDataToSend)
          }
        }
      }
    } else {
      logger.warn("Attempted to send request while another is in progress.")
      showErrorAlert("Пожалуйста, дождитесь завершения предыдущего запроса.")
    }
  }

  /** Инициирует создание нового топика. */
  def startNewTopic(): Unit = {
    val category = activeCategoryName
    logger.info(s"User requested to start a new topic in category '$category'.")
    if (!isRequestInProgress.get()) {
      pendingImageData = None
      topicManager.createNewTopic(category) match {
        case Success(newTopic) =>
          logger.info(s"New topic '${newTopic.id}' created successfully. Setting active.")
          setActiveTopic(Some(newTopic.id))
          Platform.runLater { footerRef.foreach(_.clearInput()) }
        case Failure(e) =>
          logger.error(s"Failed to create new topic in category '$category'.", e)
          showErrorAlert(s"Не удалось создать новый топик: ${e.getMessage}")
      }
    } else logger.warn("Ignoring 'New Topic' action: request in progress.")
  }

  /**
   * Устанавливает активный топик.
   * Вызывает synchronizeUIState после успешного обновления состояния.
   * Использует isProgrammaticSelectionFlag для предотвращения цикла с HistoryPanel.
   */
  def setActiveTopic(topicIdOpt: Option[String]): Unit = {
    if (isProgrammaticSelectionFlag.get()) {
      logger.trace(s"Skipping setActiveTopic call for ${topicIdOpt.getOrElse("None")} due to programmatic selection flag.")
      return
    }

    pendingImageData = None
    val currentActiveId = currentAppState.activeTopicId
    if (topicIdOpt != currentActiveId) {
      logger.debug(s"Request to change active topic from ${currentActiveId.getOrElse("None")} to ${topicIdOpt.getOrElse("None")}")
      val previousCategory = activeCategoryName

      topicManager.setActiveTopic(topicIdOpt) match {
        case Success(_) =>
          logger.debug("Active topic state updated successfully in StateManager.")
          val newCategory = activeCategoryName
          if (previousCategory != newCategory) {
            logger.info(s"Category changed from '$previousCategory' to '$newCategory'. Updating AI service model.")
            updateAiServiceWithCurrentModel()
          }
          Platform.runLater {
            logger.debug("Scheduling UI synchronization and HistoryPanel selection after user setActiveTopic...")
            synchronizeUIState() // Синхронизируем UI

            try {
              if (isProgrammaticSelectionFlag.compareAndSet(false, true)) {
                logger.debug("Setting programmatic selection flag TRUE before HistoryPanel.selectTopic")
                historyPanelRef.foreach(_.selectTopic(topicIdOpt.orNull))
              } else {
                logger.warn("Could not set programmatic selection flag (already true?) before HistoryPanel.selectTopic")
              }
            } finally {
              isProgrammaticSelectionFlag.set(false)
              logger.debug("Reset programmatic selection flag to FALSE after HistoryPanel.selectTopic")
            }
            logger.debug("UI synchronization and HistoryPanel selection scheduled.")
          }
        case Failure(e) =>
          logger.error(s"Failed to set active topic to ${topicIdOpt.getOrElse("None")}.", e)
          showErrorAlert(s"Ошибка при выборе топика: ${e.getMessage}")
      }
    } else {
      logger.trace(s"setActiveTopic called for already active topic ID: ${topicIdOpt.getOrElse("None")}. Skipping update.")
    }
  }


  /** Обрабатывает запрос на удаление топика. */
  def deleteTopic(topicId: String): Unit = {
    logger.debug(s"Delete requested for topic ID: $topicId")
    if (!isRequestInProgress.get()) {
      if (currentAppState.activeTopicId.contains(topicId)) pendingImageData = None
      topicManager.findTopicById(topicId) match {
        case Some(topicToDelete) =>
          DialogUtils.showConfirmation(s"Удалить топик '${topicToDelete.title}'?", ownerWindow = mainStage).foreach {
            case ButtonType.OK =>
              logger.info(s"User confirmed deletion of topic: ${topicToDelete.title} (ID: $topicId)")
              topicManager.deleteTopic(topicId) match {
                case Success(nextActiveIdOpt) =>
                  logger.info(s"Topic '$topicId' deleted successfully. Next active topic ID: ${nextActiveIdOpt.getOrElse("None")}.")
                  setActiveTopic(nextActiveIdOpt) // Синхронизирует UI
                case Failure(e) =>
                  logger.error(s"Failed to delete topic '$topicId'.", e)
                  showErrorAlert(s"Ошибка удаления топика: ${e.getMessage}")
              }
            case _ => logger.debug(s"Deletion cancelled by user for topic $topicId.")
          }
        case None =>
          logger.warn(s"Attempted to delete a non-existent topic with ID: $topicId")
          showErrorAlert(s"Невозможно удалить: топик с ID $topicId не найден.")
      }
    } else {
      logger.warn("Ignoring 'Delete Topic' action while request is in progress.")
    }
  }

  //</editor-fold>

  //<editor-fold desc="Обработка Файлов и Изображений">

  /** Обработчик перетаскивания файла (вызывается из Footer). */
  private def handleFileDropped(file: File): Unit = {
    logger.info(s"Handling dropped file: ${file.getName}")
    if (!isRequestInProgress.get()) {
      fileManager.foreach(_.readFileAndAppend(file))
    } else {
      showErrorAlert("Невозможно обработать файл во время выполнения запроса.")
    }
  }

  /** Обработчик перетаскивания папки (вызывается из Footer). */
  private def handleDirectoryDropped(dir: File): Unit = {
    logger.info(s"Handling dropped directory: ${dir.getName}")
    if (!isRequestInProgress.get()) {
      fileManager.foreach(_.listDirectoryAndAppend(dir))
    } else {
      showErrorAlert("Невозможно обработать папку во время выполнения запроса.")
    }
  }

  /** Обработчик вставки изображения из буфера обмена (вызывается из Footer). */
  private def handleImagePasted(image: Image): Unit = {
    logger.info(s"Handling pasted image (size: ${image.getWidth}x${image.getHeight}).")
    if (!isRequestInProgress.get()) {
      Try {
        val format = if (image.getUrl != null && (image.getUrl.toLowerCase.endsWith(".jpg") || image.getUrl.toLowerCase.endsWith(".jpeg"))) "jpeg" else "png"
        val mimeType = s"image/$format"
        logger.debug(s"Encoding image as $mimeType")
        val buffer = imageToBytes(image, format)
        val base64Data = java.util.Base64.getEncoder.encodeToString(buffer)
        pendingImageData = Some(InlineData(mimeType = mimeType, data = base64Data))
        logger.info(s"Image encoded to Base64 ($mimeType, size: ${base64Data.length}). Ready for next request.")
      }.recover {
        case NonFatal(e) =>
          logger.error("Failed to process pasted image.", e)
          showErrorAlert(s"Не удалось обработать вставленное изображение: ${e.getMessage}")
          pendingImageData = None
      }
    } else {
      showErrorAlert("Невозможно обработать изображение во время выполнения другого запроса.")
    }
  }

  /** Преобразует JavaFX Image в массив байт заданного формата (png/jpeg). */
  private def imageToBytes(image: Image, format: String): Array[Byte] = {
    val outputStream = new ByteArrayOutputStream()
    ImageIO.write(SwingFXUtils.fromFXImage(image, null), format, outputStream)
    outputStream.toByteArray
  }

  //</editor-fold>

  //<editor-fold desc="Выполнение AI Запроса и Обработка Результатов">

  /**
   * Запускает выполнение AI запроса. Учитывает pendingImageData.
   */
  private def executeRequest(
                              originalRequestText: String,
                              categoryHint: Option[String],
                              apiKey: String,
                              imageDataOpt: Option[InlineData]
                            ): Unit = {

    if (isRequestInProgress.compareAndSet(false, true)) {
      var turnId: String = ""
      Platform.runLater {
        updateFooterState(locked = true)
        val displayText = if (imageDataOpt.isDefined && originalRequestText.nonEmpty) {
          s"$originalRequestText\n[Изображение добавлено]"
        } else if (imageDataOpt.isDefined) {
          "[Изображение добавлено]"
        } else {
          originalRequestText
        }
        turnId = ResponseArea.addRequestTurn(displayText)

        if (turnId.nonEmpty && !turnId.startsWith("error-")) {
          footerRef.foreach(_.clearInput())
          ResponseArea.showLoadingIndicatorForRequest(turnId)
          logger.debug(s"UI prepared for request. Turn ID: $turnId. Image included: ${imageDataOpt.isDefined}")
          submitRequestAsync(originalRequestText, categoryHint, apiKey, imageDataOpt, turnId)
        } else {
          val errorMsg = s"Внутренняя ошибка UI при создании хода запроса (ID: $turnId)."
          logger.error(errorMsg)
          showErrorAlert(errorMsg)
          isRequestInProgress.set(false)
          updateFooterState(locked = false)
        }
      }
    } else {
      logger.warn("executeRequest called while another request is already in progress. Aborting.")
      showErrorAlert("Предыдущий запрос еще выполняется.")
    }
  }

  /** Асинхронно отправляет запрос к AI и обрабатывает результат */
  private def submitRequestAsync(
                                  originalRequestText: String,
                                  categoryHint: Option[String],
                                  apiKey: String,
                                  imageDataOpt: Option[InlineData],
                                  turnId: String
                                ): Unit = {
    logger.info(s"Submitting request via RequestExecutionManager. TurnID: $turnId, CategoryHint: $categoryHint, Image: ${imageDataOpt.isDefined}")
    val resultFuture: Future[(String, Dialog)] = requestExecutionManager.submitRequest(
      originalRequestText, categoryHint, apiKey, imageDataOpt
    )

    resultFuture.onComplete { resultTry =>
      Platform.runLater {
        isRequestInProgress.set(false)
        updateFooterState(locked = false)

        resultTry match {
          case Success((topicId, resultDialog)) =>
            handleSuccessfulAiResponse(topicId, resultDialog, turnId)
          case Failure(exception) =>
            handleFailedAiResponse(exception, turnId)
        }
      }
      pendingImageData = None
    }
  }


  /** Обрабатывает успешный ответ от AI. Вызывается из FX потока. */
  private def handleSuccessfulAiResponse(topicId: String, resultDialog: Dialog, turnId: String): Unit = {
    logger.info(s"Request successful for topic ID '$topicId'. Updating UI for turn ID '$turnId'.")
    ResponseArea.addResponseTurn(turnId, resultDialog.response)
    val currentCategory = activeCategoryName
    updateHistoryPanel(currentCategory, Some(topicId))

    try {
      if (isProgrammaticSelectionFlag.compareAndSet(false, true)) {
        logger.debug("Setting programmatic selection flag TRUE before HistoryPanel.selectTopic (on success)")
        historyPanelRef.foreach(_.selectTopic(topicId))
      } else {
        logger.warn("Could not set programmatic selection flag (already true?) before HistoryPanel.selectTopic (on success)")
      }
    } finally {
      isProgrammaticSelectionFlag.set(false)
      logger.debug("Reset programmatic selection flag to FALSE after HistoryPanel.selectTopic (on success)")
    }
  }

  /** Обрабатывает ошибку выполнения AI запроса. Вызывается из FX потока. */
  private def handleFailedAiResponse(exception: Throwable, turnId: String): Unit = {
    logger.error(s"Request failed for turn ID '$turnId': ${exception.getMessage}", exception)
    val errorMsg = s"Ошибка: ${exception.getMessage}"
    ResponseArea.showErrorForRequest(turnId, errorMsg)
    showErrorAlert(s"Ошибка выполнения запроса:\n${exception.getMessage}")
  }

  //</editor-fold>

  //<editor-fold desc="Настройки и их Обновление">

  /** Показывает модальное окно настроек. */
  private def showSettingsWindow(): Unit = {
    mainStage.foreach { owner =>
      logger.debug("Showing settings window...")
      val state = currentAppState
      val currentKey = getApiKey().getOrElse("")

      val settings = CurrentSettings(
        apiKey = currentKey,
        model = state.globalAiModel,
        // fontFamily и fontSize удалены из CurrentSettings
        availableModels = state.availableModels,
        buttonMappings = presetManager.getButtonMappings,
        defaultPresets = presetManager.getDefaultPresets,
        customPresets = presetManager.getCustomPresets
      )

      val settingsView = new SettingsView(owner, this, settings)
      settingsView.showAndWait()

      logger.debug("Settings window closed. Updating relevant states/UI.")
      updateAiServiceWithCurrentModel()
      synchronizeUIState() // Синхронизируем UI
    }
  }

  /** Обновляет API ключ. Вызывается из SettingsView. */
  def updateApiKey(newApiKey: String): Unit = {
    val trimmedKey = newApiKey.trim
    val currentStoredKey = getApiKey()
    val keyChanged = currentStoredKey.getOrElse("") != trimmedKey

    if (keyChanged) {
      logger.info("API Key change detected. Updating storage and fetching models...")
      val saveOrDeleteAction: Try[Unit] = if (trimmedKey.isEmpty) CredentialsService.deleteApiKey() else CredentialsService.saveApiKey(trimmedKey)

      saveOrDeleteAction match {
        case Success(_) =>
          logger.info(s"API Key ${if (trimmedKey.isEmpty) "deleted" else "saved"} successfully.")
          fetchModelsAndUpdateState().andThen { case _ =>
            updateAiServiceWithCurrentModel()
            Platform.runLater(synchronizeUIState())
          }
        case Failure(e) =>
          logger.error("Failed to save or delete API key.", e)
          showErrorAlert(s"Не удалось ${if (trimmedKey.isEmpty) "удалить" else "сохранить"} API ключ: ${e.getMessage}")
      }
    } else {
      logger.debug("API Key submitted is the same as the stored one. No update needed.")
    }
  }

  /** Обновляет глобальную модель AI. Вызывается из SettingsView. */
  def updateGlobalAIModel(newModelName: String): Try[Unit] = {
    val trimmedModelName = newModelName.trim
    if (trimmedModelName.isEmpty) {
      val msg = "Имя глобальной модели не может быть пустым."
      logger.error(msg)
      return Failure(new IllegalArgumentException(msg))
    }
    val result = stateManager.updateState { currentState =>
      if (currentState.globalAiModel == trimmedModelName) currentState
      else if (currentState.availableModels.exists(_.name == trimmedModelName)) {
        logger.info(s"Updating global AI model to '$trimmedModelName'.")
        currentState.copy(globalAiModel = trimmedModelName)
      } else {
        val availableNames = currentState.availableModels.map(m => s"'${m.name}'").mkString(", ")
        val errorMsg = s"Модель '$trimmedModelName' не найдена среди доступных: [$availableNames]."
        logger.error(s"Cannot set global model: $errorMsg")
        throw new IllegalArgumentException(errorMsg)
      }
    }
    result.foreach(_ => updateAiServiceWithCurrentModel())
    result
  }

  /* <<< Метод updateFontSettings полностью удален >>> */

  // Делегирование управления пресетами PresetManager
  def saveCustomPreset(preset: PromptPreset): Try[Unit] = presetManager.saveCustomPreset(preset)
  def saveDefaultPreset(preset: PromptPreset): Try[Unit] = presetManager.saveDefaultPreset(preset)
  def deleteCustomPreset(presetName: String): Try[Unit] = presetManager.deleteCustomPreset(presetName)
  def updateButtonMappings(newMappings: Map[String, String]): Try[Unit] = presetManager.updateButtonMappings(newMappings)

  //</editor-fold>

  //<editor-fold desc="Вспомогательные Методы">

  /** Возвращает текущий API ключ из CredentialsService. */
  private def getApiKey(): Option[String] = CredentialsService.loadApiKey()

  /** Возвращает текущее состояние AppState. */
  private def currentAppState: AppState = stateManager.getState

  /** Определяет имя активной категории. */
  private def activeCategoryName: String = {
    currentAppState.activeTopicId
      .flatMap(topicManager.findTopicById)
      .map(_.category)
      .filter(Header.categoryButtonNames.contains)
      .getOrElse(Header.categoryButtonNames.headOption.getOrElse("Global"))
  }

  /** Асинхронно загружает модели и обновляет состояние. */
  private def fetchModelsAndUpdateState(): Future[Unit] = {
    getApiKey() match {
      case Some(apiKey) if apiKey.nonEmpty =>
        logger.info("Attempting to fetch available AI models...")
        modelFetchingService.fetchAvailableModels(apiKey).transformWith {
          case Success(fetchedModels) =>
            logger.info(s"Successfully fetched ${fetchedModels.size} AI models.")
            val sortedFetchedModels = fetchedModels.sortBy(_.displayName)
            val updateTry = stateManager.updateState { currentState =>
              if (currentState.availableModels != sortedFetchedModels) {
                val currentGlobalModel = currentState.globalAiModel
                val currentGlobalModelExists = sortedFetchedModels.exists(_.name == currentGlobalModel)
                val newGlobalModel = if (currentGlobalModelExists) currentGlobalModel else sortedFetchedModels.headOption.map(_.name).getOrElse("")
                logger.info(s"Updating available models list in state. New global model set to '$newGlobalModel'.")
                // Убраны поля шрифта из AppState
                currentState.copy(availableModels = sortedFetchedModels, globalAiModel = newGlobalModel)
              } else {
                logger.debug("Fetched models are the same as current in state. No state update needed.")
                currentState
              }
            }
            Future.fromTry(updateTry)
          case Failure(fetchError) =>
            logger.error("Failed to fetch AI models.", fetchError)
            val clearTry = stateManager.updateState(s => if(s.availableModels.nonEmpty) s.copy(availableModels = List.empty, globalAiModel = "") else s)
            Future.fromTry(clearTry.recoverWith{ case _ => Failure(fetchError)})
        }
      case _ =>
        logger.warn("Cannot fetch models: API Key is not available.")
        val clearTry = stateManager.updateState(s => if(s.availableModels.nonEmpty) s.copy(availableModels = List.empty, globalAiModel = "") else s)
        Future.fromTry(clearTry)
    }
  }


  /** Обновляет модель в AIService. */
  private def updateAiServiceWithCurrentModel(): Unit = {
    val state = currentAppState
    val category = activeCategoryName
    val preset = presetManager.findActivePresetForButton(category)
    val modelToUseOpt: Option[String] = preset.modelOverride
      .orElse(Option(state.globalAiModel).filter(_.nonEmpty))
      .orElse(state.availableModels.headOption.map(_.name))

    modelToUseOpt match {
      case Some(modelToUse) =>
        if (state.availableModels.nonEmpty && !state.availableModels.exists(_.name == modelToUse)) {
          val fallbackModel = state.availableModels.head.name
          logger.warn(s"Model '$modelToUse' (determined for category '$category') not found in available models. Falling back to '$fallbackModel'.")
          Try(aiService.updateModel(fallbackModel)).failed.foreach(e => logger.error(s"Failed to update AIService model to fallback '$fallbackModel'", e))
        } else {
          logger.info(s"Setting AI service model to: '$modelToUse' (for category '$category')")
          Try(aiService.updateModel(modelToUse)).failed.foreach(e => logger.error(s"Failed to update AIService model to '$modelToUse'", e))
        }
      case None =>
        logger.error("Failed to determine any AI model to use. AIService model not updated.")
    }
  }

  /** Валидирует длину текста запроса. */
  private def validateInputLength(text: String): Option[String] = {
    val maxLength = 30000 // Примерный лимит
    if (text.length > maxLength) {
      Some(f"Запрос слишком длинный (${text.length}%,d / $maxLength%,d символов).")
    } else {
      None
    }
  }

  /** Показывает стандартное диалоговое окно с сообщением об ошибке. */
  private def showErrorAlert(message: String): Unit = {
    DialogUtils.showError(message, mainStage)
  }
  //</editor-fold>

}