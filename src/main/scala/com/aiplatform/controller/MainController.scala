// src/main/scala/com/aiplatform/controller/MainController.scala
package com.aiplatform.controller

import com.aiplatform.controller.manager.{
  FileManager,
  PresetManager,
  RequestExecutionManager,
  StateManager,
  TopicManager
}
import com.aiplatform.model.*
import com.aiplatform.service.{AIService, CredentialsService, FileTreeService, ModelFetchingService, InlineData}
import com.aiplatform.view.{
  CurrentSettings,
  DialogUtils,
  Footer,
  Header,
  HistoryPanel,
  ResponseArea,
  SettingsView,
  FileTreeView
}
import org.apache.pekko.actor.typed.ActorSystem
import scalafx.application.Platform
import scalafx.scene.control.{ButtonType, SplitPane}
import scalafx.scene.layout.BorderPane
import scalafx.scene.Parent
import org.slf4j.LoggerFactory
import scalafx.stage.Stage
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try, Using}
import scala.util.control.NonFatal
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import java.io.{ByteArrayOutputStream, File}
import javafx.scene.image.Image as JFXImage
import javafx.embed.swing.SwingFXUtils
import javax.imageio.ImageIO
import scalafx.geometry.Orientation


class MainController(
                      implicit
                      system: ActorSystem[?]
                    ) {

  private val logger = LoggerFactory.getLogger(getClass)
  implicit private val ec: ExecutionContext = system.executionContext

  private val stateManager = new StateManager()
  val aiService = new AIService()(system.classicSystem)
  private val topicManager = new TopicManager(stateManager, aiService)
  private val presetManager = new PresetManager(stateManager)
  private val requestExecutionManager = new RequestExecutionManager(
    stateManager,
    topicManager,
    presetManager,
    aiService
  )
  private val modelFetchingService = new ModelFetchingService()(system, ec)
  private lazy val fileTreeService = new FileTreeService(this.fileManager.get) // Added FileTreeService definition
  private var fileManager: Option[FileManager] = None

  private var mainStage: Option[Stage] = None
  private var rootPane: Option[BorderPane] = None
  private var headerRef: Option[Header] = None
  private var footerRef: Option[Footer] = None
  private var fileTreeViewInstance: Option[FileTreeView] = None

  private val isRequestInProgress = new AtomicBoolean(false)
  private val isProgrammaticSelectionFlag = new AtomicBoolean(false)
  private var pendingImageData: Option[InlineData] = None
  private var pendingFileContext: StringBuilder = new StringBuilder() // For text-based file context from drag-drop.
  // Structured file context (FileTreeContext) is stored in AppState.fileContext.

  private val isSyncingUI = new AtomicBoolean(false)
  private val syncCounter = new AtomicLong(0)

  // --- Public methods for View components ---
  def getMainStage: Option[Stage] = this.mainStage

  def appendToInputArea(text: String): Unit = {
    Platform.runLater {
      footerRef.foreach(_.appendText(text))
    }
  }

  def getIsProgrammaticSelection: Boolean = isProgrammaticSelectionFlag.get()
  // --- End of public methods ---


  initializeController()

  private def initializeController(): Unit = {
    logger.info("Controller initialization started.")
    val initFuture = fetchModelsAndUpdateState().map { _ =>
      updateAiServiceWithCurrentModel()
    }
    initFuture.onComplete {
      case Success(_) =>
        logger.info("Initial model fetch and AI service setup complete. Scheduling initial UI sync.")
        Platform.runLater(() => performInitialUISetup())
      case Failure(e) =>
        logger.error("Initial setup (fetchModels/updateAiService) failed. Scheduling UI sync with potentially stale data.", e)
        Platform.runLater(() => performInitialUISetup())
    }
  }

  def createUI(ownerStage: Stage): Parent = {
    logger.info("MainController: Creating application UI structure...")
    this.mainStage = Some(ownerStage)
    this.fileManager = Some(new FileManager(ownerStage, this.footerRef))

    val headerComponent = new Header(onHeaderButtonClicked = handleHeaderAction)
    this.headerRef = Some(headerComponent)
    val headerNode = headerComponent.createHeaderNode()

    val footerComponent = new Footer(
      onSend = processUserInput,
      onNewTopic = startNewTopic,
      // Placeholder actions for onAttachFileClick and onAttachCodeClick
      onAttachFileClick = () => logger.info("Attach File button clicked - placeholder action."),
      onAttachCodeClick = () => logger.info("Attach Code button clicked - placeholder action."),
      onFileDropped = handleFileDropped,
      onDirectoryDropped = handleDirectoryDropped,
      onImagePasted = handleImagePasted
    )
    this.footerRef = Some(footerComponent)
    val footerNode = footerComponent.createFooterNode()

    val responseNode = ResponseArea.create()
    val historyPanelNode = HistoryPanel.create(this)

    // Assuming FileTreeView constructor is now parameterless as per its definition. (Note: It's not, it takes dependencies)
    // If it needs ftManager or this (MainController), it must be injected.
    val fileTreeViewComponent = new FileTreeView(this.fileManager.get, this, fileTreeService) // Pass FileTreeService
    this.fileTreeViewInstance = Some(fileTreeViewComponent)
    val fileTreeViewNode = fileTreeViewComponent.viewNode

    val leftSplitPane = new SplitPane {
      orientation = Orientation.Vertical
      items.addAll(historyPanelNode, fileTreeViewNode)
      dividerPositions = 0.25
    }
    leftSplitPane.maxWidth = 350
    leftSplitPane.prefWidth = 300

    val centerArea = new BorderPane {
      styleClass.add("center-pane")
      center = responseNode
    }

    val root = new BorderPane {
      styleClass.add("main-pane")
      top = headerNode
      left = leftSplitPane
      center = centerArea
      bottom = footerNode
    }
    this.rootPane = Some(root)
    logger.info("Application UI structure created.")
    root
  }

  def performInitialUISetup(): Unit = {
    logger.info(">>> Performing Initial UI Setup (after async init)...")
    synchronizeUIState()
    logger.info(">>> Initial UI Setup tasks completed.")
  }

  def shutdown(): Unit = {
    logger.info("Shutting down MainController and services...")
    stateManager.forceSaveState().failed.foreach(e => logger.error("Failed to save state during shutdown.", e))
    aiService.shutdown()
    logger.info("AI Service backend resources released.")
    logger.info("MainController shutdown complete.")
  }

  def synchronizeUIState(): Unit = {
    val callId = syncCounter.incrementAndGet()
    if (!isSyncingUI.compareAndSet(false, true)) {
      logger.warn(s"[$callId] synchronizeUIState aborted: already syncing.")
      return
    }
    try {
      if (!Platform.isFxApplicationThread) {
        logger.warn(s"[$callId] synchronizeUIState called from non-FX thread. Rescheduling.")
        isSyncingUI.set(false)
        Platform.runLater(() => synchronizeUIState())
        return
      }

      logger.info(s"[$callId] >>> Starting UI synchronization on thread: ${Thread.currentThread().getName}...")
      val state = currentAppState
      val currentCategory = activeCategoryName
      val activeTopicIdOpt = state.activeTopicId

      logger.debug(s"[$callId] State details: ActiveCategory='$currentCategory', ActiveTopicID='${activeTopicIdOpt.getOrElse("None")}'")

      headerRef.foreach(_.setActiveButton(currentCategory))
      updateHistoryPanel(currentCategory, activeTopicIdOpt)
      updateResponseArea(activeTopicIdOpt, currentCategory)
      updateFooterState(isRequestInProgress.get())

      logger.info(s"[$callId] <<< UI state synchronization finished.")
    } catch {
      case NonFatal(e) => logger.error(s"[$callId] synchronizeUIState failed with exception.", e)
    } finally {
      isSyncingUI.set(false)
    }
  }

  private def updateHistoryPanel(categoryForHistory: String, activeTopicIdOpt: Option[String]): Unit = {
    val topics = topicManager.getTopicsForCategory(categoryForHistory)
    logger.debug(
      s"Updating history panel: Category='$categoryForHistory', Topics=${topics.size}, ActiveID=${activeTopicIdOpt.getOrElse("None")}"
    )
    HistoryPanel.updateTopics(topics, activeTopicIdOpt)

    activeTopicIdOpt match {
      case Some(idToSelect) => HistoryPanel.selectTopic(idToSelect)
      // Call selectTopic with null or empty string to clear selection,
      // as HistoryPanel.selectTopic handles this by clearing the ListView selection.
      case None => HistoryPanel.selectTopic("")
    }
  }

  private def updateResponseArea(activeTopicIdOpt: Option[String], currentCategory: String): Unit = {
    val topicOpt = activeTopicIdOpt.flatMap(topicManager.findTopicById)
    val dialogsToShow = topicOpt.map(_.dialogs).getOrElse(List.empty)
    logger.debug(
      s"Updating response area: ActiveID=${activeTopicIdOpt.getOrElse("None")}, Dialogs=${dialogsToShow.size}, Category='$currentCategory'"
    )
    if (activeTopicIdOpt.isDefined && topicOpt.isDefined) {
      ResponseArea.displayTopicDialogs(dialogsToShow)
      if (dialogsToShow.isEmpty) {
        ResponseArea.showStatus("This is a new topic. Enter your first query or add context.")
      }
    } else {
      ResponseArea.clearDialog()
      val topicsInCurrentCategory = topicManager.getTopicsForCategory(currentCategory)
      if (topicsInCurrentCategory.isEmpty) {
        ResponseArea.showError(s"No topics in category '$currentCategory'. Start a new one (+) or choose another category.")
      } else {
        ResponseArea.showStatus(s"Select a topic from the list on the left for category '$currentCategory' or start a new one (+).")
      }
    }
  }

  private def updateFooterState(locked: Boolean): Unit = {
    footerRef.foreach(_.setLocked(locked))
    logger.trace(s"Footer state updated. Locked: $locked")
  }

  def handleHeaderAction(buttonName: String): Unit = {
    logger.info("Header action triggered for button: {}", buttonName)
    if (isRequestInProgress.get()) {
      logger.warn("Ignoring header action: request in progress.")
      DialogUtils.showInfo("Please wait for the current request to complete.", ownerWindow = mainStage) // Translated
      return
    }
    if (buttonName == "Settings") {
      showSettingsWindow()
    } else if (Header.categoryButtonNames.contains(buttonName)) {
      // Determine the logical current category based on actual application state,
      // not necessarily what the header UI element currently shows.
      val logicalCurrentCategory = currentAppState.activeTopicId
        .flatMap(topicManager.findTopicById)
        .map(_.category)
        .getOrElse(activeCategoryNameFromHeaderFallback()) // Fallback if no active topic

      if (buttonName != logicalCurrentCategory) {
        logger.info(s"User requested category switch from '$logicalCurrentCategory' to '$buttonName'")
        // No longer call setActiveButton on header directly here.
        // setActiveTopic will handle updating the header after state changes.
        val nextActiveTopicIdOpt = topicManager.determineActiveTopicForCategory(buttonName)
        setActiveTopic(nextActiveTopicIdOpt)
      } else {
        logger.debug(s"Category button '$buttonName' re-clicked, but logical current category is already '$buttonName'. Forcing resync or state is inconsistent.")
        // If logicalCurrentCategory is already buttonName, user might be trying to "refresh" or
        // there was a previous UI inconsistency. Resyncing by calling setActiveTopic for this category.
        val nextActiveTopicIdOpt = topicManager.determineActiveTopicForCategory(buttonName)
        setActiveTopic(nextActiveTopicIdOpt) // Resync based on the clicked category.
      }
    } else {
      logger.warn("Unhandled header button action: {}", buttonName)
    }
  }

  private def processUserInput(inputText: String): Unit = {
    val trimmedText = inputText.trim
    logger.debug("Processing user input: '{}'", trimmedText)
    if (isRequestInProgress.get()) {
      logger.warn("Attempted to send request while another is in progress.")
      DialogUtils.showError("Please wait for the previous request to complete.", ownerWindow = mainStage) // Translated
      return
    }
    val imageDataToSend = pendingImageData
    pendingImageData = None
    // File contexts (both types) are cleared in submitRequestAsync after the request is formed.
    if (trimmedText.isEmpty && imageDataToSend.isEmpty) {
      logger.warn("Attempted to send empty input (no text and no pending image).")
      DialogUtils.showError("Request cannot be empty (enter text, attach files, or paste an image).", ownerWindow = mainStage) // Translated
      return
    }
    validateInputLength(trimmedText) match {
      case Some(error) =>
        logger.warn("Input validation failed: {}", error)
        DialogUtils.showError(error, ownerWindow = mainStage)
      case None =>
        getApiKey() match {
          case None =>
            logger.error("API Key missing.")
            DialogUtils.showError("API key not found. Please add it in settings.", ownerWindow = mainStage) // Translated
          case Some(apiKey) =>
            val categoryForRequest = activeCategoryName
            val categoryHint = if (categoryForRequest == "Global") None else Some(categoryForRequest)
            executeRequest(trimmedText, categoryHint, apiKey, imageDataToSend)
        }
    }
  }

  def startNewTopic(): Unit = {
    val category = activeCategoryName
    logger.info(s"User requested to start a new topic in category '$category'.")
    if (isRequestInProgress.get()) {
      logger.warn("Ignoring 'New Topic' action: request in progress.")
      return
    }
    pendingImageData = None
    clearFileContext() // Clears the StringBuilder based pendingFileContext
    clearStructuredFileContext() // Clears the AppState.fileContext
    topicManager.createNewTopic(category) match {
      case Success(newTopic) =>
        logger.info(s"New topic '${newTopic.id}' created successfully. Setting active.")
        setActiveTopic(Some(newTopic.id))
        Platform.runLater(footerRef.foreach(_.clearInput()))
      case Failure(e) =>
        logger.error(s"Failed to create new topic in category '$category'.", e)
        DialogUtils.showError(s"Failed to create new topic: ${e.getMessage}", ownerWindow = mainStage) // Translated
    }
  }

  def setActiveTopic(topicIdOpt: Option[String]): Unit = {
    if (isProgrammaticSelectionFlag.get() && currentAppState.activeTopicId == topicIdOpt) {
      logger.trace(s"Skipping setActiveTopic for ${topicIdOpt.getOrElse("None")} due to programmatic flag and no actual change.")
      return
    }
    val prevActiveTopicId = currentAppState.activeTopicId
    if (prevActiveTopicId == topicIdOpt) {
      logger.trace(s"setActiveTopic called for already active topic ID: ${topicIdOpt.getOrElse("None")}. Forcing UI sync.")
      Platform.runLater(synchronizeUIState())
      return
    }
    logger.info(s"Attempting to set active topic from ${prevActiveTopicId.getOrElse("None")} to ${topicIdOpt.getOrElse("None")}")
    if (prevActiveTopicId != topicIdOpt) {
      pendingImageData = None
      clearStructuredFileContext() // Clear structured context if topic actually changes
    }

    val categoryBeforeChange = activeCategoryName

    topicManager.setActiveTopic(topicIdOpt) match {
      case Success(_) =>
        logger.info(s"Active topic ID set to ${topicIdOpt.getOrElse("None")} in StateManager.")
        val categoryAfterChange = topicIdOpt.flatMap(topicManager.findTopicById).map(_.category).getOrElse(categoryBeforeChange)

        if (categoryBeforeChange != categoryAfterChange) {
          logger.info(s"Category effectively changed from '$categoryBeforeChange' to '$categoryAfterChange'. Updating Header and AI model.")
          headerRef.foreach(_.setActiveButton(categoryAfterChange))
          updateAiServiceWithCurrentModel()
        } else {
          // If category did not change, but header might be out of sync (e.g. if activeCategoryNameFromHeaderFallback was used initially)
          // ensure header reflects the true category of the active topic.
          // The previous check `headerRef.exists(_.activeCategoryNameProperty.value != categoryAfterChange)`
          // is replaced by relying on the Header's new getCurrentActiveButtonName method.
          if (headerRef.exists(_.getCurrentActiveButtonName != categoryAfterChange)) {
            logger.info(s"Topic selected in category '$categoryAfterChange', but header shows '${headerRef.get.getCurrentActiveButtonName}'. Syncing header.")
            headerRef.foreach(_.setActiveButton(categoryAfterChange))
          }
        }

        Platform.runLater {
          logger.debug("Scheduling UI synchronization after setActiveTopic.")
          isProgrammaticSelectionFlag.set(true)
          try {
            synchronizeUIState()
          } finally {
            isProgrammaticSelectionFlag.set(false)
            logger.trace("Reset isProgrammaticSelectionFlag to false after UI sync in setActiveTopic.")
          }
        }
      case Failure(e) =>
        logger.error(s"Failed to set active topic to ${topicIdOpt.getOrElse("None")}.", e)
        DialogUtils.showError(s"Error selecting topic: ${e.getMessage}", ownerWindow = mainStage) // Translated
    }
  }

  def deleteTopic(topicId: String): Unit = {
    logger.debug(s"Delete requested for topic ID: $topicId")
    if (isRequestInProgress.get()) {
      logger.warn("Ignoring 'Delete Topic' action: request in progress.")
      return
    }
    if (currentAppState.activeTopicId.contains(topicId)) {
      pendingImageData = None
      // If the topic being deleted is active, its structured context should be cleared.
      clearStructuredFileContext()
    }

    topicManager.findTopicById(topicId) match {
      case Some(topicToDelete) =>
        DialogUtils.showConfirmation(s"Удалить топик '${topicToDelete.title}'?", ownerWindow = mainStage).foreach {
          case ButtonType.OK =>
            logger.info(s"User confirmed deletion of topic: ${topicToDelete.title} (ID: $topicId)")
            val categoryOfDeletedTopic = topicToDelete.category
            topicManager.deleteTopic(topicId) match {
              case Success(nextActiveIdOpt) =>
                logger.info(
                  s"Topic '$topicId' deleted. TopicManager suggests next active ID: ${nextActiveIdOpt.getOrElse("None")}."
                )
                // If no next topic is suggested AND the category of the deleted topic is now empty,
                // then start a new topic in that category. startNewTopic will call setActiveTopic.
                if (nextActiveIdOpt.isEmpty && topicManager.getTopicsForCategory(categoryOfDeletedTopic).isEmpty) {
                  Platform.runLater { // Defer to ensure state is settled from delete
                    logger.info(s"Category '$categoryOfDeletedTopic' is empty after deletion and no other topic became active. Creating new topic in this category.")
                    // Ensure the header reflects the category where the new topic will be created.
                    headerRef.foreach(_.setActiveButton(categoryOfDeletedTopic))
                    startNewTopic() // This will internally call setActiveTopic for the new topic.
                  }
                } else {
                  // Otherwise, set the suggested next active topic (which might be None, handled by setActiveTopic).
                  setActiveTopic(nextActiveIdOpt)
                }
              case Failure(e) =>
                logger.error(s"Failed to delete topic '$topicId'.", e)
                DialogUtils.showError(s"Error deleting topic: ${e.getMessage}", ownerWindow = mainStage) // Translated
            }
          case _ => logger.debug(s"Deletion cancelled by user for topic $topicId.")
        }
      case None =>
        logger.warn(s"Attempted to delete a non-existent topic with ID: $topicId")
        DialogUtils.showError(s"Cannot delete: topic with ID $topicId not found.", ownerWindow = mainStage) // Translated
    }
  }

  private def handleFileDropped(file: File): Unit = {
    logger.info(s"Handling dropped file: ${file.getName}")
    if (isRequestInProgress.get()) {
      DialogUtils.showError("Cannot process file while a request is in progress.", ownerWindow = mainStage) // Translated
      return
    }

    fileManager.foreach { manager =>
      // Read file content for processing by the AI model
      manager.readFileContent(file) match {
        case Success(content) =>
          // Calculate file size
          val fileSizeKB = file.length() / 1024
          val fileSizeMB = fileSizeKB / 1024.0
          val fileSizeStr = if (fileSizeMB >= 1.0) f"$fileSizeMB%.2f MB" else s"$fileSizeKB KB"

          // Prepare preview
          val contentPreview = if (content.length > 200) {
            content.substring(0, 200) + "..."
          } else {
            content
          }

          // Check if file is too large
          val maxFileSizeKB = 500 // Max file size for text-based context
          if (fileSizeKB > maxFileSizeKB) {
            val warningMsg = s"File ${file.getName} ($fileSizeStr) is too large. Max file size for context: $maxFileSizeKB KB."
            logger.warn(warningMsg)
            DialogUtils.showWarning(warningMsg, ownerWindow = mainStage) // String is already mostly English
          } else {
            // Show confirmation dialog with preview
            val confirmationMsg =
              s"""Add file "${file.getName}" ($fileSizeStr) to context for the AI model?
                 |
                 |Content preview:
                 |```
                 |$contentPreview
                 |```
                 |
                 |The file content will be sent to the AI model with your next request.""".stripMargin // Translated

            DialogUtils.showConfirmation(confirmationMsg, ownerWindow = mainStage).foreach {
              case ButtonType.OK =>
                // User confirmed, add file content to pending context with proper escaping
                val escapedContent = escapeFileContent(content)
                appendToFileContext(file.getName, escapedContent) // Appends to the StringBuilder pendingFileContext

                // Also append formatted text to UI footer
                manager.readFileAndAppendToFooter(file)

                // Show feedback to user
                Platform.runLater {
                  DialogUtils.showInfo(s"File ${file.getName} added to context. Content will be sent with your next request.", ownerWindow = mainStage) // Translated
                }
              case _ =>
                logger.debug(s"User declined to add file ${file.getName} to context")
            }
          }
        case Failure(e) =>
          logger.error(s"Failed to read file ${file.getName} for context: ${e.getMessage}", e)
          DialogUtils.showError(s"Failed to read file ${file.getName} for context: ${e.getMessage}", ownerWindow = mainStage) // Translated
      }
    }
  }

  private def handleDirectoryDropped(dir: File): Unit = {
    logger.info(s"Handling dropped directory: ${dir.getName}")
    if (isRequestInProgress.get()) {
      DialogUtils.showError("Cannot process folder while a request is in progress.", ownerWindow = mainStage) // Translated
      return
    }

    // Only display folder structure in footer, no context for AI yet
    // Could be extended to add folder structure to context as well
    fileManager.foreach(_.attachFolderContext())
  }

  private def handleImagePasted(image: JFXImage): Unit = {
    logger.info(s"Handling pasted image (size: ${image.getWidth}x${image.getHeight}).")
    if (isRequestInProgress.get()) {
      DialogUtils.showError("Cannot process image while another request is in progress.", ownerWindow = mainStage) // Translated
      return
    }
    Try {
      val format = if (image.getUrl != null && (image.getUrl.toLowerCase().endsWith(".jpg") || image.getUrl.toLowerCase().endsWith(".jpeg"))) "jpeg" else "png"
      val mimeType = s"image/$format"
      logger.debug(s"Encoding image as $mimeType")
      val buffer = Using.resource(new ByteArrayOutputStream()) { baos =>
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), format, baos)
        baos.toByteArray
      }
      val base64Data = java.util.Base64.getEncoder.encodeToString(buffer)
      pendingImageData = Some(InlineData(mimeType = mimeType, data = base64Data))
      logger.info(s"Image encoded to Base64 ($mimeType, data length: ${base64Data.length}). Ready for next request.")
      Platform.runLater {
        val placeholder = s"\n[Image (${image.getWidth}x${image.getHeight}) is ready to be sent with text]\n" // Translated
        appendToInputArea(placeholder)
      }
    }.recover {
      case NonFatal(e) =>
        logger.error("Failed to process pasted image.", e)
        DialogUtils.showError(s"Failed to process pasted image: ${e.getMessage}", ownerWindow = mainStage) // Translated
        pendingImageData = None
        clearFileContext() // Clears StringBuilder context
        clearStructuredFileContext() // Clears AppState context
    }
  }

  def updateFileTreeContext(newContext: FileTreeContext): Unit = {
    logger.info(s"Updating FileTreeContext in AppState with ${newContext.selectedFiles.length} file states.")
    val updateResult = stateManager.updateState { currentState =>
      currentState.copy(fileContext = Some(newContext))
    }
    updateResult match {
      case Success(_) =>
        logger.info("Successfully updated AppState with new FileTreeContext.")
        Platform.runLater {
          DialogUtils.showInfo(s"${newContext.selectedFiles.length} file(s) added to request context.", ownerWindow = mainStage) // Translated
        }
      case Failure(e) =>
        logger.error("Failed to update AppState with new FileTreeContext.", e)
        Platform.runLater {
          DialogUtils.showError(s"Failed to update file context: ${e.getMessage}", ownerWindow = mainStage) // Translated
        }
    }
  }

  // Called by FileTreeView (or other UI element) when user wants to clear all selected files from context
  def clearFileTreeContextFromView(): Unit = {
    logger.info("User requested to clear FileTreeContext via UI command.")
    clearStructuredFileContext() // Clears AppState.fileContext

    // Optionally, inform FileTreeView to reset its visual state if it doesn't observe AppState
    fileTreeViewInstance.foreach { ftv =>
      // ftv.resetSelectionVisuals() // Placeholder for a method if needed for FileTreeView to visually update
      logger.debug("FileTreeContext cleared from AppState. FileTreeView might need to update its visuals if not observing state.")
    }
    Platform.runLater {
      DialogUtils.showInfo("Selected file context has been cleared.", ownerWindow = mainStage) // Translated
    }
  }

  private def executeRequest(
                              originalRequestText: String,
                              categoryHint: Option[String],
                              apiKey: String,
                              imageDataOpt: Option[InlineData]
                            ): Unit = {
    if (!isRequestInProgress.compareAndSet(false, true)) {
      logger.warn("executeRequest called while another request is already in progress. Aborting.")
      DialogUtils.showError("Previous request is still in progress.", ownerWindow = mainStage) // Translated
      return
    }
    var turnIdForUI: String = ""
    Platform.runLater {
      updateFooterState(locked = true)
      val displayRequestText = imageDataOpt match {
        case Some(_) if originalRequestText.nonEmpty => s"$originalRequestText\n[Изображение прикреплено]"
        case Some(_)                                 => "[Изображение прикреплено]"
        case None                                    => originalRequestText
      }
      turnIdForUI = ResponseArea.addRequestTurn(displayRequestText)
      if (turnIdForUI.nonEmpty && !turnIdForUI.startsWith("error-")) {
        footerRef.foreach(_.clearInput())
        ResponseArea.showLoadingIndicatorForRequest(turnIdForUI)
        logger.debug(s"UI prepared for request. Turn ID: $turnIdForUI. Image included: ${imageDataOpt.isDefined}")
        // Get text-based file context (from drag-drop) if any is available
        val textFileContextOpt = if (pendingFileContext.nonEmpty) Some(pendingFileContext.toString()) else None
        // Structured FileTreeContext will be retrieved from AppState in submitRequestAsync
        submitRequestAsync(originalRequestText, categoryHint, apiKey, imageDataOpt, textFileContextOpt, turnIdForUI)
      } else {
        val errorMsg = s"Internal UI error creating request turn (ID: $turnIdForUI)." // Translated
        logger.error(errorMsg)
        DialogUtils.showError(errorMsg, ownerWindow = mainStage)
        isRequestInProgress.set(false)
        updateFooterState(locked = false)
      }
    }
  }

  private def submitRequestAsync(
                                  originalRequestText: String,
                                  categoryHint: Option[String],
                                  apiKey: String,
                                  imageDataOpt: Option[InlineData],
                                  // fileContextOpt from pendingFileContext (drag-drop) is passed directly
                                  fileContextOpt: Option[String],
                                  uiTurnId: String
                                ): Unit = {
    // Retrieve the structured file context from AppState
    val structuredFileContextOpt = currentAppState.fileContext

    logger.info(
      s"Submitting request. UITurnID: $uiTurnId, CategoryHint: $categoryHint, Image: ${imageDataOpt.isDefined}, TextFileContext: ${fileContextOpt.isDefined}, StructuredFileContext: ${structuredFileContextOpt.isDefined}"
    )

    // RequestExecutionManager.submitRequest will need to be updated to accept FileTreeContext
    // For now, this will likely cause a compile error until that signature is changed.
    val resultFuture: Future[(String, Dialog)] = requestExecutionManager.submitRequest(
      originalRequestText,
      categoryHint,
      apiKey,
      imageDataOpt,
      fileContextOpt, // Existing text-based context from drag-drop
      structuredFileContextOpt // New structured context from FileTreeView
    )

    // Clear both types of file contexts after submitting the request
    clearFileContext() // Clears the StringBuilder based pendingFileContext
    clearStructuredFileContext() // Clears the AppState.fileContext

    resultFuture.onComplete { resultTry =>
      Platform.runLater {
        isRequestInProgress.set(false)
        updateFooterState(locked = false)
        resultTry match {
          case Success((topicId, resultDialog)) =>
            handleSuccessfulAiResponse(topicId, resultDialog, uiTurnId)
          case Failure(exception) =>
            handleFailedAiResponse(exception, uiTurnId)
        }
      }
    }
  }

  private def handleSuccessfulAiResponse(topicId: String, resultDialog: Dialog, uiTurnId: String): Unit = {
    logger.info(s"Request successful for topic ID '$topicId'. Updating UI for turn ID '$uiTurnId'.")
    ResponseArea.addResponseTurn(uiTurnId, resultDialog.response)
    if (!currentAppState.activeTopicId.contains(topicId)) {
      setActiveTopic(Some(topicId))
    } else {
      val currentCategory = activeCategoryName
      updateHistoryPanel(currentCategory, Some(topicId))
    }
  }

  private def handleFailedAiResponse(exception: Throwable, uiTurnId: String): Unit = {
    val message = Option(exception.getMessage).getOrElse("Unknown AI service error") // Translated
    logger.error(s"Handling failed AI response for UI turn ID '$uiTurnId': $message", exception)
    val displayMessage = if (message.contains("429") && message.toLowerCase.contains("quota")) {
      "AI request limit reached (error 429). Please check your quotas or try again later." // Translated
    } else {
      s"Error from AI service: $message" // Translated
    }
    ResponseArea.showErrorForRequest(uiTurnId, displayMessage)
  }

  private def showSettingsWindow(): Unit = {
    mainStage.foreach { owner =>
      logger.debug("Showing settings window...")
      val state = currentAppState
      val currentKey = getApiKey().getOrElse("")
      val settings = CurrentSettings(
        apiKey = currentKey,
        model = state.globalAiModel,
        availableModels = state.availableModels,
        buttonMappings = presetManager.getButtonMappings,
        defaultPresets = presetManager.getDefaultPresets,
        customPresets = presetManager.getCustomPresets
      )
      val settingsView = new SettingsView(owner, this, settings)
      settingsView.showAndWait()
      logger.debug("Settings window closed. Applying relevant changes.")
      updateAiServiceWithCurrentModel()
      Platform.runLater {
        synchronizeUIState()
      }
    }
  }

  def updateApiKey(newApiKey: String): Unit = {
    val trimmedKey = newApiKey.trim
    val currentStoredKey = getApiKey()
    if (currentStoredKey.getOrElse("") == trimmedKey) {
      logger.debug("API Key submitted is the same as stored. No update performed.")
      return
    }

    logger.info("API Key change detected. Attempting to save/delete...")
    val saveOrDeleteAction = if (trimmedKey.isEmpty) CredentialsService.deleteApiKey() else CredentialsService.saveApiKey(trimmedKey)

    saveOrDeleteAction match {
      case Success(_) =>
        logger.info(s"API Key ${if (trimmedKey.isEmpty) "deleted" else "saved"} successfully.")
        val processingChainFuture: Future[Unit] = fetchModelsAndUpdateState().map { _ =>
          logger.info("Models fetched/cleared and AppState updated after API key change.")
          updateAiServiceWithCurrentModel()
        }
        processingChainFuture.onComplete {
          case Success(_) =>
            logger.info("API key processing chain (fetch models, update AI service) completed successfully. Syncing UI.")
            Platform.runLater(synchronizeUIState())
          case Failure(e) =>
            logger.error(s"Error in API key processing chain (likely fetchModels): ${e.getMessage}. Syncing UI with current (possibly stale) state.", e)
            Platform.runLater(synchronizeUIState())
        }
      case Failure(e) =>
        logger.error("Failed to save or delete API key in CredentialsService.", e)
        DialogUtils.showError(s"Failed to ${if (trimmedKey.isEmpty) "delete" else "save"} API key: ${e.getMessage}", ownerWindow = mainStage) // Translated
    }
  }


  def updateGlobalAIModel(newModelName: String): Try[Unit] = {
    val trimmedModelName = newModelName.trim
    if (trimmedModelName.isEmpty) {
      val msg = "Global model name cannot be empty." // Translated
      logger.error(msg)
      return Failure(new IllegalArgumentException(msg))
    }

    logger.info(s"Attempting to update global AI model in AppState to '$trimmedModelName'.")

    // Fetch current state before the update to be able to compare before/after
    val currentState = stateManager.getState

    // Check if model exists in available models before attempting the update
    if (!currentState.availableModels.exists(_.name == trimmedModelName)) {
      val availableNames = currentState.availableModels.map(m => s"'${m.name}'").mkString(", ")
      val errorMsg = s"Model '$trimmedModelName' not found among available models: [$availableNames]." // Translated
      logger.error(s"Cannot set global model: $errorMsg")
      return Failure(new IllegalArgumentException(errorMsg))
    }

    // Only proceed with update if the model name has changed
    if (currentState.globalAiModel == trimmedModelName) {
      logger.debug("Global model is already set to '{}'. No change to AppState.", trimmedModelName)
      return Success(())
    }

    val result = stateManager.updateState { state =>
      logger.info(s"Updating global AI model in AppState from '${state.globalAiModel}' to '$trimmedModelName'.")
      state.copy(globalAiModel = trimmedModelName)
    }

    result.foreach { _ =>
      // Validate that the update was successful by checking the current state
      val updatedState = stateManager.getState
      if (updatedState.globalAiModel == trimmedModelName) {
        logger.info(s"Successfully updated global AI model to '$trimmedModelName'.")
        // Update AI service with the new model
        updateAiServiceWithCurrentModel()
        // Update UI to reflect changes
        Platform.runLater(synchronizeUIState())
      } else {
        logger.warn(s"State update succeeded but global AI model doesn't match. Expected '$trimmedModelName', got '${updatedState.globalAiModel}'.")
      }
    }

    result.failed.foreach { error =>
      logger.error(s"Failed to update global AI model to '$trimmedModelName'", error)
    }

    result
  }

  def saveCustomPreset(preset: PromptPreset): Try[Unit] = presetManager.saveCustomPreset(preset)
  def saveDefaultPreset(preset: PromptPreset): Try[Unit] = presetManager.saveDefaultPreset(preset)
  def deleteCustomPreset(presetName: String): Try[Unit] = presetManager.deleteCustomPreset(presetName)
  def updateButtonMappings(newMappings: Map[String, String]): Try[Unit] = presetManager.updateButtonMappings(newMappings)

  private def getApiKey(): Option[String] = CredentialsService.loadApiKey()
  private def currentAppState: AppState = stateManager.getState

  // Helper to get category from header or default, used as fallback.
  private def activeCategoryNameFromHeaderFallback(): String = {
    // Uses the new public method in Header.scala
    headerRef.map(_.getCurrentActiveButtonName)
      .filter(Header.categoryButtonNames.contains)
      .getOrElse(Header.categoryButtonNames.headOption.getOrElse("Global"))
  }

  private def activeCategoryName: String = {
    // Prioritize category from the currently active topic in the state
    currentAppState.activeTopicId
      .flatMap(topicManager.findTopicById)
      .map(_.category)
      .filter(Header.categoryButtonNames.contains)
      .getOrElse(activeCategoryNameFromHeaderFallback()) // Fallback to header's state or default
  }

  private def fetchModelsAndUpdateState(): Future[Unit] = {
    getApiKey() match {
      case Some(apiKey) if apiKey.nonEmpty =>
        logger.info("Attempting to fetch available AI models...")
        modelFetchingService.fetchAvailableModels(apiKey).transformWith {
          case Success(fetchedModels) =>
            logger.info(s"Successfully fetched ${fetchedModels.size} AI models.")
            val sortedFetchedModels = fetchedModels.sortBy(_.displayName)
            val updateTry = stateManager.updateState { currentState =>
              val currentGlobalModelIsValid = sortedFetchedModels.exists(_.name == currentState.globalAiModel)
              val newGlobalModel =
                if (currentGlobalModelIsValid) currentState.globalAiModel
                else sortedFetchedModels.headOption.map(_.name).getOrElse("")

              if (currentState.availableModels != sortedFetchedModels || currentState.globalAiModel != newGlobalModel) {
                logger.info(s"Updating available models list and/or global model in AppState. New global model: '$newGlobalModel'.")
                currentState.copy(availableModels = sortedFetchedModels, globalAiModel = newGlobalModel)
              } else {
                logger.debug("Fetched models and global model are the same as current in AppState. No state update needed.")
                currentState
              }
            }
            Future.fromTry(updateTry)
          case Failure(fetchError) =>
            logger.error("Failed to fetch AI models.", fetchError)
            val clearTry = stateManager.updateState(s =>
              if (s.availableModels.nonEmpty || s.globalAiModel.nonEmpty) s.copy(availableModels = List.empty, globalAiModel = "") else s
            )
            Future.fromTry(clearTry.recoverWith { case _ => Failure(fetchError) })
        }
      case _ =>
        logger.warn("Cannot fetch models: API Key is not available. Clearing models in AppState.")
        val clearTry = stateManager.updateState(s =>
          if (s.availableModels.nonEmpty || s.globalAiModel.nonEmpty) s.copy(availableModels = List.empty, globalAiModel = "") else s
        )
        Future.fromTry(clearTry)
    }
  }

  // Helper methods for text-based file context management (drag-drop)
  private def appendToFileContext(fileName: String, content: String): Unit = {
    // Format file content with clear structure and proper line endings for the StringBuilder context
    val formattedContent = s"""
File: ${sanitizeFileName(fileName)}
$content
"""
    pendingFileContext.append(formattedContent)
    logger.debug(s"Added content from $fileName to text-based file context. Total context size: ${pendingFileContext.length}")

    // Log context for debugging
    if (logger.isDebugEnabled) {
      val contextPreview = if (pendingFileContext.length > 500) {
        pendingFileContext.toString.substring(0, 500) + "... (truncated)"
      } else {
        pendingFileContext.toString
      }
      logger.debug(s"Current text-based file context:\n$contextPreview")
    }
  }

  /**
   * Sanitizes filename to prevent possible injection or formatting issues in text-based context.
   */
  private def sanitizeFileName(fileName: String): String = {
    // Remove potential problematic characters like newlines, tabs, backticks
    fileName.replaceAll("[\\n\\r\\t`]", "")
  }

  /**
   * Escapes special characters in file content for text-based context.
   * This helps prevent issues with JSON formatting or markdown interpretation if context is embedded.
   */
  private def escapeFileContent(content: String): String = {
    // Basic escaping for common problematic characters in text formats.
    // Example: replace backslashes to avoid issues if this content is embedded in JSON string.
    // Replace backticks if content is displayed in Markdown without proper fencing.
    content
      .replace("\\", "\\\\") // Escape backslashes
      .replace("`", "'")   // Replace backticks with single quotes as a simple strategy
    // Further escaping might be needed depending on how this text context is used.
  }

  // Clears the StringBuilder-based text file context
  private def clearFileContext(): Unit = {
    if (pendingFileContext.nonEmpty) {
      logger.debug(s"Clearing StringBuilder (text-based) file context (size was: ${pendingFileContext.length})")
      pendingFileContext.clear()
    }
  }

  // Clears the structured FileTreeContext from AppState
  private def clearStructuredFileContext(): Unit = {
    logger.info("Attempting to clear structured FileTreeContext (from FileTreeView) in AppState.")
    if (currentAppState.fileContext.isDefined) {
      val updateResult = stateManager.updateState { currentState =>
        // Only update if it's actually defined, to avoid unnecessary state changes/saves
        if (currentState.fileContext.isDefined) {
          currentState.copy(fileContext = None)
        } else {
          currentState // No change needed if already None (should not happen due to outer check, but safe)
        }
      }
      updateResult match {
        case Success(_) =>
          logger.info("Successfully cleared structured FileTreeContext in AppState (or it was already None).")
        case Failure(e) =>
          logger.error("Failed to clear structured FileTreeContext in AppState.", e)
          // User notification might be excessive here if it's an internal clear operation (e.g., after request).
      }
    } else {
      logger.debug("Structured FileTreeContext is already None in AppState. No update needed.")
    }
  }

  private def updateAiServiceWithCurrentModel(): Unit = {
    val state = currentAppState
    val currentActiveCat = activeCategoryName
    val preset = presetManager.findActivePresetForButton(currentActiveCat)

    logger.info(s"Updating AI Service model. Current Category: '$currentActiveCat', Preset: '${preset.name}'.")
    // Removed maxOutputTokens from log as it's not in PromptPreset model
    logger.debug(s"Preset details: modelOverride=${preset.modelOverride}, temp=${preset.temperature}, topP=${preset.topP}, topK=${preset.topK}")
    logger.debug(s"Global model in current AppState: '${state.globalAiModel}'")
    logger.debug(s"Available models in current AppState: ${state.availableModels.map(_.name).mkString(", ")}")

    // Get model name to use, with priority to preset override, then global model, then first available model
    val modelToUseOpt: Option[String] = preset.modelOverride
      .filter(_.nonEmpty)
      .orElse(Option(state.globalAiModel).filter(_.nonEmpty))
      .orElse(state.availableModels.headOption.map(_.name).filter(_.nonEmpty))

    // Special handling for Flash model variants
    val finalModelToUseOpt = modelToUseOpt.map { modelName =>
      val isFlashVariant = modelName.contains("flash")
      val modelExists = state.availableModels.exists(_.name == modelName)

      if (isFlashVariant && !modelExists) {
        // Try to find a suitable Flash model from available models
        val availableFlashModels = state.availableModels.filter(m => m.name.contains("flash"))
        if (availableFlashModels.nonEmpty) {
          logger.info(s"Flash model '$modelName' not found. Using alternative Flash model: '${availableFlashModels.head.name}'")
          availableFlashModels.head.name
        } else {
          // If no Flash models available, use the original model name (will be handled by fallback logic)
          modelName
        }
      } else {
        modelName
      }
    }

    finalModelToUseOpt match {
      case Some(modelToUse) =>
        // Use new public getter AIService.getCurrentModel
        logger.info(s"Determined model for AIService: '$modelToUse'. Current AIService model: '${aiService.getCurrentModel}'.")

        // Check if the model exists in available models
        if (state.availableModels.nonEmpty && !state.availableModels.exists(_.name == modelToUse)) {
          val fallbackModel = state.availableModels.head.name
          logger.warn(
            s"Model '$modelToUse' (determined for category '$currentActiveCat') not found in available models. Falling back to '$fallbackModel'."
          )

          // Update model in AIService
          Try(aiService.updateModel(fallbackModel)) match {
            case Success(_) =>
              logger.info(s"Successfully updated AIService model to fallback '$fallbackModel'")
              // If we're using a fallback and the global model doesn't match, update it
              if (state.globalAiModel == modelToUse) {
                logger.info(s"Updating global model from '$modelToUse' to fallback '$fallbackModel'")
                stateManager.updateState(s => s.copy(globalAiModel = fallbackModel))
              }
            case Failure(e) =>
              logger.error(s"Failed to update AIService model to fallback '$fallbackModel'", e)
          }
        } else {
          logger.info(s"Setting AI service model to: '$modelToUse' (for category '$currentActiveCat')")
          Try(aiService.updateModel(modelToUse)) match {
            case Success(_) =>
              logger.info(s"Successfully updated AIService model to '$modelToUse'")
              // Ensure model is saved in state if it's different from current global model
              if (preset.modelOverride.isEmpty && state.globalAiModel != modelToUse) {
                logger.info(s"Synchronizing global model with active model: '$modelToUse'")
                stateManager.updateState(s => s.copy(globalAiModel = modelToUse))
              }
            case Failure(e) =>
              logger.error(s"Failed to update AIService model to '$modelToUse'", e)
          }
        }
      case None =>
        logger.error(s"Failed to determine any AI model to use for category '$currentActiveCat'. AIService model not updated. This can happen if no global model is set and no presets override it, and the available models list is empty.")
    }
  }

  private def validateInputLength(text: String): Option[String] = {
    val maxLength = 30000 // Max length for user input text
    if (text.length > maxLength) {
      Some(f"Request is too long (${text.length}%,d / $maxLength%,d characters). Please shorten it.") // Translated
    } else {
      None
    }
  }
}