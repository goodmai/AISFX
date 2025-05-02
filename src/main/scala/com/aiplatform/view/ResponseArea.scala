// src/main/scala/com/aiplatform/view/ResponseArea.scala
package com.aiplatform.view

import com.aiplatform.model.Dialog
import javafx.geometry.Bounds
import javafx.scene.control.{ProgressIndicator => JFXProgressIndicator, ScrollPane => JScrollPane}
import javafx.scene.{Node => JFXNode}
import scalafx.Includes._
import scalafx.application.Platform
import scalafx.geometry.{Insets, Pos}
import scalafx.scene.Parent
import scalafx.scene.control.{Button, Label, ProgressIndicator, ScrollPane, Tooltip}
import scalafx.scene.input.{Clipboard, ClipboardContent}
import scalafx.scene.layout.{BorderPane, HBox, Priority, StackPane, VBox}
import scalafx.scene.text.{Font, Text, TextFlow}
import org.slf4j.LoggerFactory
import scala.util.matching.Regex
import scala.collection.mutable // Используем mutable Map
import scala.util.{Failure, Success, Try}
import scalafx.scene.Node // Импортируем scalafx.scene.Node
import java.util.{Timer, TimerTask, UUID} // Импортируем Timer и TimerTask
import scala.util.control.NonFatal // Для генерации ID
import javafx.scene.layout.{HBox => JFXHBox} // Импортируем JavaFX HBox
import javafx.scene.control.{Label => JFXLabel} // Импортируем JavaFX Label
/**
 * Объект-компаньон для управления областью отображения ответов AI.
 * Содержит UI элементы и логику их обновления.
 */
object ResponseArea {
  private val logger = LoggerFactory.getLogger(getClass)

  // Контейнер для всех блоков диалога (запрос-ответ)
  private val dialogContainer = new VBox {
    spacing = 15 // Расстояние между блоками диалога
    padding = Insets(15)
    styleClass.add("dialog-container")
    fillWidth = true // Растягивать дочерние элементы по ширине
  }

  // Метка для отображения статуса или ошибок (вверху области)
  private val statusLabel = new Label {
    styleClass.add("status-label") // Базовый класс стиля
    padding = Insets(10, 15, 10, 15) // Отступы
    visible = false // По умолчанию скрыта
    managed <== visible // Не занимает место, если невидима
    wrapText = true // Перенос текста
    maxWidth = Double.MaxValue // Растягивать по ширине
  }

  // ScrollPane для прокрутки диалогов
  private val scrollPane = new ScrollPane {
    content = dialogContainer
    fitToWidth = true // Растягивать контент по ширине ScrollPane
    vbarPolicy = ScrollPane.ScrollBarPolicy.AsNeeded // Вертикальная прокрутка по необходимости
    hbarPolicy = ScrollPane.ScrollBarPolicy.Never // Горизонтальная прокрутка отключена
    vgrow = Priority.Always // Занимать все доступное вертикальное пространство
    styleClass.add("response-scroll-pane")
    id = "responseScrollPane" // ID для возможного доступа/тестирования

    // Флаг для управления автопрокруткой
    private var autoScrollEnabled = true
    // Порог для определения, что пользователь прокрутил достаточно вверх, чтобы отключить автоскролл
    private val autoScrollDisableThreshold = 0.95
    // Порог для определения, что пользователь находится внизу (для включения автоскролла)
    private val autoScrollEnableThreshold = 0.99 // Чуть меньше 1.0 на случай погрешностей

    // Отключаем/включаем автопрокрутку при ручном скролле
    vvalue.onChange { (_, oldValue, newValue) =>
      val oldD = oldValue.doubleValue()
      val newD = newValue.doubleValue()
      // Отключаем, если прокрутили вверх и не были уже вверху
      if (newD < oldD && newD < autoScrollDisableThreshold) {
        if (autoScrollEnabled) { // Логируем только при изменении состояния
          autoScrollEnabled = false
          logger.trace("Auto-scroll disabled due to manual scroll up.")
        }
      } else if (newD >= autoScrollEnableThreshold) { // Если достигли самого низа (или почти низа)
        if (!autoScrollEnabled) { // Логируем только при изменении состояния
          autoScrollEnabled = true
          logger.trace("Auto-scroll re-enabled as bottom is reached.")
        }
      }
    }

    // Автопрокрутка вниз при добавлении нового контента, если включена
    dialogContainer.height.onChange { (_, _, _) =>
      if (autoScrollEnabled) {
        scrollToBottom() // Вызываем метод прокрутки вниз
      }
    }
  }

  /** Прокручивает ScrollPane в самый низ. */
  private def scrollToBottom(): Unit = {
    Platform.runLater {
      scrollPane.vvalue = 1.0
      logger.trace("Scrolled to bottom.")
    }
  }

  // Корневой layout для ResponseArea
  private val rootLayout = new VBox {
    children = Seq(statusLabel, scrollPane) // Статус сверху, прокрутка снизу
    VBox.setVgrow(scrollPane, Priority.Always) // ScrollPane занимает все оставшееся место
    styleClass.add("response-area-root")
  }

  // Карта для хранения ссылок на VBox-плейсхолдеры ожидающих ответов
  private val pendingResponses = mutable.Map[String, VBox]()

  // Регулярное выражение для поиска блоков кода
  private val codeBlockRegex: Regex = """(?s)(.*?)(?:```(?:\w*\r?\n)?(.*?)```|$)""".r
  private val monospaceFont = Font.font("monospace", 13)
  private val codeScrollPaneStyle = "-fx-background: transparent; -fx-background-color: transparent;"

  /**
   * Создает и возвращает корневой узел (Parent) для ResponseArea.
   */
  def create(): Parent = rootLayout

  /**
   * Добавляет блок запроса пользователя. Вызывается из FX потока.
   * @return Уникальный ID хода или строка с префиксом "error-" при ошибке UI.
   */
  def addRequestTurn(request: String): String = {
    val turnId = s"turn-${UUID.randomUUID().toString}"
    try {
      statusLabel.visible = false // Скрываем статус при новом запросе

      val requestLabel = new Label("Вы:") { styleClass.add("dialog-label") }
      val requestTextNode = new Text(request) { styleClass.add("request-text") }
      val requestTextFlow = new TextFlow(requestTextNode) {
        maxWidth <== scrollPane.width - 60 // Ограничение ширины
      }
      val requestContentBox = new VBox(requestLabel, requestTextFlow) {
        styleClass.add("request-box")
      }

      val responsePlaceholder = new VBox { // Плейсхолдер для ответа
        styleClass.add("response-placeholder")
        id = s"placeholder-$turnId"
      }

      val dialogTurnBox = new VBox(requestContentBox, responsePlaceholder) {
        spacing = 5
        styleClass.add("dialog-turn")
        id = turnId
      }

      dialogContainer.children.add(dialogTurnBox)
      pendingResponses.put(turnId, responsePlaceholder) // Сохраняем плейсхолдер
      logger.debug(s"Added request turn UI. ID: $turnId. Pending responses: ${pendingResponses.size}")
      turnId

    } catch {
      case NonFatal(e) =>
        logger.error(s"Error adding request turn UI elements (ID: $turnId)", e)
        s"error-${UUID.randomUUID()}"
    }
  }

  /**
   * Добавляет блок ответа AI. Вызывается из FX потока.
   */
  def addResponseTurn(turnId: String, responseText: String): Unit = {
    pendingResponses.remove(turnId) match { // Атомарно получаем и удаляем
      case Some(responsePlaceholder) =>
        try {
          responsePlaceholder.children.clear() // Очищаем от индикатора/ошибки

          val responseLabel = new Label("AI:") { styleClass.add("dialog-label") }
          val responseContentBox = new VBox { spacing = 5 }
          parseAndAddResponseContent(responseContentBox, responseText) // Заполняем контентом

          val responseBox = new VBox(responseLabel, responseContentBox) {
            styleClass.add("response-box")
          }

          responsePlaceholder.children.add(responseBox) // Добавляем готовый ответ
          logger.debug(s"Added response content for turn ID $turnId. Pending responses: ${pendingResponses.size}")

        } catch {
          case NonFatal(e) =>
            logger.error(s"Error adding response turn UI elements for turn ID $turnId", e)
            Try(showErrorInPlaceholder(responsePlaceholder, s"Ошибка отображения ответа: ${e.getMessage}"))
        }
      case None =>
        logger.error(s"Could not find placeholder for turn ID: $turnId to add response.")
    }
  }

  /**
   * Парсит текст и добавляет элементы в контейнер.
   */
  private def parseAndAddResponseContent(container: VBox, responseText: String): Unit = {
    var lastIndex = 0
    for (m <- codeBlockRegex.findAllMatchIn(responseText)) {
      val precedingText = m.group(1)
      val codeTextOpt = Option(m.group(2))

      if (precedingText.nonEmpty) {
        val textNode = new Text(precedingText) { styleClass.add("response-text") }
        val textFlow = new TextFlow(textNode) { maxWidth <== scrollPane.width - 80 }
        container.children.add(textFlow)
      }
      codeTextOpt.foreach(code => if (code.trim.nonEmpty) container.children.add(createCodeBlockNode(code)))
      lastIndex = m.end
    }
    if (lastIndex < responseText.length) {
      val remainingText = responseText.substring(lastIndex)
      if (remainingText.trim.nonEmpty) {
        val textNode = new Text(remainingText) { styleClass.add("response-text") }
        val textFlow = new TextFlow(textNode) { maxWidth <== scrollPane.width - 80 }
        container.children.add(textFlow)
      }
    }
  }

  /**
   * Вспомогательный метод для сброса текста кнопки "Copy".
   */
  private def scheduleButtonTextReset(button: Button, originalText: String, delayMillis: Long): Unit = {
    val timer = new Timer(true) // Используем демон-поток для таймера
    timer.schedule(new TimerTask {
      def run(): Unit = Platform.runLater {
        // Проверяем, что кнопка все еще существует на сцене перед обновлением
        if (button.scene.value != null) {
          button.text = originalText
        } else {
          logger.trace("Button text reset skipped, button no longer on scene.")
        }
        timer.cancel() // Отменяем таймер
      }
    }, delayMillis)
  }

  /**
   * Создает UI узел для блока кода.
   */
  private def createCodeBlockNode(codeTextRaw: String): Node = {
    val codeText = codeTextRaw.trim
    val codeTextNode = new Text(codeText) {
      font = monospaceFont
      styleClass.add("response-code-text")
    }
    val codeTextFlow = new TextFlow(codeTextNode)
    val codeScrollPane = new ScrollPane {
      content = codeTextFlow
      style = codeScrollPaneStyle
      fitToWidth = true
      hbarPolicy = ScrollPane.ScrollBarPolicy.AsNeeded
      vbarPolicy = ScrollPane.ScrollBarPolicy.AsNeeded
      maxHeight = 450
      styleClass.add("code-block-scroll-pane")
    }

    def createCopyButton(textToCopy: String): Button = new Button("Copy") {
      styleClass.add("copy-button")
      tooltip = Tooltip("Копировать код")
      onAction = _ => {
        val clip = Clipboard.systemClipboard
        val cont = new ClipboardContent()
        cont.putString(textToCopy)
        if (clip.setContent(cont)) {
          logger.debug("Code copied to clipboard.")
          text = "Copied!"
          scheduleButtonTextReset(this, "Copy", 1500)
        } else {
          logger.error("Failed to set clipboard content.")
          text = "Error!"
          scheduleButtonTextReset(this, "Copy", 2000)
        }
      }
    }

    val copyButton = createCopyButton(codeTextRaw)
    val layout = new BorderPane {
      styleClass.add("code-block-inner")
      top = new HBox(copyButton) {
        alignment = Pos.CenterRight
        padding = Insets(0, 5, 3, 0)
      }
      center = codeScrollPane
    }
    new StackPane {
      children = layout
      styleClass.add("code-block")
    }
  }

  /**
   * Показывает индикатор загрузки для хода. Вызывается из FX потока.
   */
  def showLoadingIndicatorForRequest(turnId: String): Unit = {
    pendingResponses.get(turnId) match {
      case Some(responsePlaceholder) =>
        responsePlaceholder.children.clear()
        val indicator = new ProgressIndicator {
          progress = JFXProgressIndicator.INDETERMINATE_PROGRESS
          prefWidth = 20; prefHeight = 20
          style = "-fx-progress-color: -fx-accent-color;"
        }
        val loadingLabel = new Label("AI думает...") {
          styleClass.add("loading-label")
          graphic = indicator
          graphicTextGap = 8
        }
        val loadingBox = new HBox(loadingLabel) {
          alignment = Pos.CenterLeft
          padding = Insets(10, 0, 10, 10)
        }
        responsePlaceholder.children.add(loadingBox)
        logger.debug(s"Showing loading indicator for turn ID $turnId.")
      case None =>
        logger.warn(s"Cannot show loading indicator: placeholder not found for turn ID $turnId.")
    }
  }

  /**
   * Скрывает все активные индикаторы загрузки. Вызывается из FX потока.
   */
  def hideLoadingIndicator(): Unit = {
    var removedCount = 0
    pendingResponses.values.foreach { placeholder =>
      val loadingNodeOpt = placeholder.children.find { node =>
        node.delegate.isInstanceOf[JFXHBox] && // Проверяем JavaFX HBox
          node.delegate.asInstanceOf[JFXHBox].getChildren.exists { child => // Получаем дочерние узлы JavaFX
            child.isInstanceOf[JFXLabel] && // Проверяем JavaFX Label
              child.getStyleClass.contains("loading-label") // Проверяем CSS класс
          }
      }
      loadingNodeOpt.foreach { node =>
        placeholder.children.remove(node)
        removedCount += 1
      }
    }
    if (removedCount > 0) logger.debug(s"Hid loading indicators for $removedCount pending requests.")
  }

  /**
   * Показывает ошибку для хода. Вызывается из FX потока.
   */
  def showErrorForRequest(turnId: String, errorMessage: String): Unit = {
    pendingResponses.remove(turnId) match {
      case Some(responsePlaceholder) =>
        showErrorInPlaceholder(responsePlaceholder, errorMessage)
        logger.warn(s"Showing error for turn ID $turnId: $errorMessage")
      case None =>
        logger.error(s"Could not find placeholder to show error for turn ID: $turnId. Showing general error.")
        showError(s"(ID хода: $turnId) $errorMessage")
    }
  }

  /**
   * Отображает ошибку в плейсхолдере.
   */
  private def showErrorInPlaceholder(placeholder: VBox, errorMessage: String): Unit = {
    placeholder.children.clear()
    val errorLabel = new Label(s"Ошибка: $errorMessage") {
      styleClass.add("error-label") // Убедитесь, что этот класс определен в CSS
      wrapText = true
      maxWidth <== scrollPane.width - 80
    }
    val errorBox = new HBox(errorLabel) { padding = Insets(5, 0, 5, 10) }
    placeholder.children.add(errorBox)
  }

  /**
   * Отображает диалоги топика. Вызывается из FX потока.
   */
  def displayTopicDialogs(dialogs: List[Dialog]): Unit = {
    // Гарантируем выполнение в FX потоке
    if (!Platform.isFxApplicationThread) {
      Platform.runLater(displayTopicDialogs(dialogs))
      return
    }
    clearDialog() // Очищаем перед отображением
    if (dialogs.nonEmpty) {
      statusLabel.visible = false
      logger.debug(s"Displaying ${dialogs.size} dialog turns.")
      dialogs.foreach { d =>
        val turnId = addRequestTurn(d.request)
        if (!turnId.startsWith("error-")) {
          addResponseTurn(turnId, d.response)
        } else {
          logger.error(s"Skipping response display due to request turn UI error for: ${d.request.take(50)}...")
        }
      }
      logger.debug(s"Finished displaying dialog turns. Pending responses map empty: ${pendingResponses.isEmpty}")
      if (pendingResponses.nonEmpty) {
        logger.warn("Pending responses map is not empty after displaying all dialogs! Clearing.")
        pendingResponses.clear()
      }
      scrollToBottom() // Прокрутка вниз после добавления
    } else {
      logger.debug("Displaying empty topic. Status message should be set by controller.")
      // Статус устанавливается извне через showError/showStatus
    }
  }

  /**
   * Очищает область диалогов. Вызывается из FX потока.
   */
  def clearDialog(): Unit = {
    // Гарантируем выполнение в FX потоке
    if (!Platform.isFxApplicationThread) {
      Platform.runLater(clearDialog())
      return
    }
    dialogContainer.children.clear()
    pendingResponses.clear()
    statusLabel.text = ""
    statusLabel.visible = false
    statusLabel.styleClass.removeAll("status-label-error", "status-label-info")
    logger.debug("ResponseArea cleared.")
  }

  /**
   * Показывает общую ошибку. Вызывается из FX потока.
   */
  def showError(msg: String): Unit = {
    // Гарантируем выполнение в FX потоке
    if (!Platform.isFxApplicationThread) {
      Platform.runLater(showError(msg))
      return
    }
    logger.warn(s"Showing general error: $msg")
    clearDialog() // Очищаем диалоги при общей ошибке
    statusLabel.text = s"Ошибка: $msg"
    statusLabel.styleClass.removeAll("status-label-info")
    statusLabel.styleClass.add("status-label-error") // Убедитесь, что этот класс определен в CSS
    statusLabel.visible = true
  }

  /**
   * Показывает общий статус. Вызывается из FX потока.
   */
  def showStatus(msg: String): Unit = {
    // Гарантируем выполнение в FX потоке
    if (!Platform.isFxApplicationThread) {
      Platform.runLater(showStatus(msg))
      return
    }
    logger.info(s"Showing general status: $msg")
    clearDialog() // Очищаем диалоги при общем статусе
    statusLabel.text = msg
    statusLabel.styleClass.removeAll("status-label-error")
    statusLabel.styleClass.add("status-label-info") // Убедитесь, что этот класс определен в CSS
    statusLabel.visible = true
  }

  /** Прокручивает ScrollPane к указанному узлу JavaFX. */
  def scrollToNode(node: JFXNode): Unit = {
    Platform.runLater { // Уже обернуто, но для надежности
      Try {
        val scrollPaneDelegate: JScrollPane = scrollPane.delegate
        val contentNode = dialogContainer.delegate
        val nodeBoundsInContent: Bounds = node.getBoundsInParent
        if (nodeBoundsInContent != null) {
          val scrollPaneHeight: Double = scrollPaneDelegate.getViewportBounds.getHeight
          val contentHeight: Double = contentNode.getBoundsInLocal.getHeight
          val nodeY: Double = nodeBoundsInContent.getMinY
          val desiredScrollTop = nodeY - 20 // Отступ
          val maxScrollTop = contentHeight - scrollPaneHeight
          val targetVvalue = if (maxScrollTop > 0) Math.max(0.0, Math.min(1.0, desiredScrollTop / maxScrollTop)) else 0.0
          scrollPane.vvalue = targetVvalue
          logger.trace(s"Scrolled to node. Target Vvalue: $targetVvalue")
        } else {
          logger.warn("Cannot scroll to node, bounds in parent are null.")
        }
      }.recover {
        case NonFatal(e) => logger.error("Error calculating scroll position.", e)
      }
    }
  }
}
