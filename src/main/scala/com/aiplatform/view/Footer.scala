// src/main/scala/com/aiplatform/view/Footer.scala
package com.aiplatform.view

import scalafx.Includes._
import scalafx.application.Platform
import scalafx.geometry.{Insets, Pos}
import scalafx.scene.control.{Button, TextArea, Tooltip}
import scalafx.scene.input.{Clipboard,DataFormat, KeyCode, KeyEvent, TransferMode}
import scalafx.scene.layout.{HBox, Priority, VBox}
import scalafx.scene.Parent
import scalafx.scene.text.Text // Используем Text для эмодзи
import org.slf4j.LoggerFactory
import scala.util.{Try, Success, Failure} // Импортируем Success и Failure
import java.io.File // Для Drag & Drop и новых колбэков
import scala.jdk.CollectionConverters._ // Для работы с Java коллекциями из Clipboard
import javafx.scene.image.Image // Импортируем JavaFX Image для колбэка

/**
 * Нижняя панель приложения (Footer) для ввода пользовательского запроса и управления.
 * Использует эмодзи для кнопок действий и улучшенную обработку Drag & Drop.
 *
 * @param onSend             Callback, вызываемый при нажатии кнопки "Отправить" или Enter. Принимает текст запроса.
 * @param onNewTopic         Callback, вызываемый при нажатии кнопки "Новый топик".
 * @param onAttachFileClick  Callback, вызываемый при *нажатии* кнопки "Прикрепить файл" (открывает диалог).
 * @param onAttachCodeClick  Callback, вызываемый при *нажатии* кнопки "Прикрепить код/папку" (открывает диалог).
 * @param onFileDropped      Callback, вызываемый при *перетаскивании* файла на область ввода. Принимает объект File.
 * @param onDirectoryDropped Callback, вызываемый при *перетаскивании* папки на область ввода. Принимает объект File (папку).
 * @param onImagePasted      Callback, вызываемый при вставке изображения из буфера обмена. Принимает javafx.scene.image. Image.
 */
class Footer(
              onSend: String => Unit,
              onNewTopic: () => Unit,
              // Колбэки для КЛИКОВ по кнопкам
              onAttachFileClick: () => Unit,
              onAttachCodeClick: () => Unit,
              // Колбэки для Drag & Drop
              onFileDropped: File => Unit,
              onDirectoryDropped: File => Unit,
              // НОВЫЙ колбэк для вставки изображения
              onImagePasted: Image => Unit
            ) {

  private val logger = LoggerFactory.getLogger(getClass)

  // --- UI Элементы ---

  private lazy val inputTextArea = new TextArea {
    promptText = "Введите ваш запрос или перетащите файлы/папки..."
    prefRowCount = 3
    hgrow = Priority.Always
    wrapText = true
    id = "inputTextArea"

    // Обработка Enter
    onKeyPressed = (event: KeyEvent) => {
      if (event.code == KeyCode.Enter && !event.shiftDown) {
        event.consume()
        if (!disable.value) {
          val textToSend = text.value.trim
          if (textToSend.nonEmpty) onSend(textToSend)
          else logger.warn("Attempted to send empty input via Enter key.")
        } else {
          logger.trace("Enter pressed but input is disabled.")
        }
      }
      // Обработка вставки (Ctrl+V/Cmd+V) - вызываем наш кастомный метод
      else if ((event.controlDown || event.metaDown) && event.code == KeyCode.V) {
        event.consume() // Потребляем стандартное событие вставки
        if (!disable.value) {
          pasteFromClipboard() // Вызываем нашу логику вставки
        } else {
          logger.trace("Paste shortcut used but input is disabled.")
        }
      }
    }

    // --- Обработка Drag & Drop ---
    onDragOver = event => {
      if (event.gestureSource != this && event.dragboard.hasFiles) {
        event.acceptTransferModes(TransferMode.CopyOrMove*)
      }
      event.consume()
    }

    onDragDropped = event => {
      val db = event.dragboard
      var success = false
      if (db.hasFiles) {
        // Используем Java List и конвертируем в Scala List
        val files: List[File] = Option(db.getFiles).map(_.asScala.toList).getOrElse(List.empty)
        files.headOption.foreach { file =>
          logger.info(s"Element dropped: ${file.getAbsolutePath}")
          if (file.isDirectory) {
            logger.debug("Dropped element is a directory. Calling onDirectoryDropped.")
            onDirectoryDropped(file)
          } else {
            logger.debug("Dropped element is a file. Calling onFileDropped.")
            onFileDropped(file)
          }
          success = true
        }
      }
      event.dropCompleted = success
      event.consume()
    }
    // --- Конец Обработки Drag & Drop ---
  }

  private lazy val sendButton = new Button("Отправить") {
    tooltip = Tooltip("Отправить запрос (Enter)")
    prefWidth = 100
    styleClass.add("send-button")
    onAction = _ => {
      if (!disable.value) {
        val textToSend = inputTextArea.text.value.trim
        if (textToSend.nonEmpty) onSend(textToSend)
        else logger.warn("Send button clicked with empty input.")
      } else {
        logger.trace("Send button clicked but it's disabled.")
      }
    }
    disable = false
  }

  /**
   * Вспомогательная функция для создания кнопок с ЭМОДЗИ.
   */
  private def createEmojiButton(emoji: String, tooltipText: String, action: () => Unit): Button = {
    new Button {
      graphic = new Text(emoji) { style = "-fx-font-size: 16px;" }
      style = "-fx-background-color: transparent; -fx-padding: 5px;"
      tooltip = Tooltip(tooltipText)
      onAction = _ => if (!disable.value) action()
      styleClass.add("emoji-button")
      disable = false
    }
  }

  private lazy val newTopicButton = createEmojiButton("➕", "Начать новый диалог", onNewTopic)
  private lazy val attachFileButton = createEmojiButton("📎", "Прикрепить текстовый файл (открыть диалог)", onAttachFileClick)
  private lazy val attachCodeButton = createEmojiButton("📁", "Прикрепить контекст папки (открыть диалог)", onAttachCodeClick)

  // Список контролов для блокировки
  private lazy val lockableControls: Seq[javafx.scene.Node] = Seq(
    inputTextArea, sendButton, newTopicButton, attachFileButton, attachCodeButton
  )

  /**
   * Создает корневой узел (Parent) для панели Footer.
   */
  def createFooterNode(): Parent = {
    val controlButtonsBox = new VBox {
      spacing = 5
      alignment = Pos.TopRight
      children = Seq(
        sendButton,
        new HBox(2, newTopicButton, attachFileButton, attachCodeButton) { alignment = Pos.CenterRight }
      )
    }
    new HBox {
      padding = Insets(10)
      spacing = 10
      children = Seq(inputTextArea, controlButtonsBox)
      alignment = Pos.BottomLeft
      styleClass.add("footer-area")
    }
  }

  /** Очищает поле ввода. */
  def clearInput(): Unit = Platform.runLater {
    inputTextArea.text = ""
    logger.debug("Footer input area cleared.")
  }

  /** Добавляет текст в поле ввода. */
  def appendText(text: String): Unit = Platform.runLater {
    inputTextArea.appendText(text)
    inputTextArea.positionCaret(inputTextArea.text.value.length)
    inputTextArea.requestFocus()
    logger.debug("Text appended to footer input area.")
  }

  /** Блокирует/разблокирует элементы управления. */
  def setLocked(locked: Boolean): Unit = Platform.runLater {
    lockableControls.foreach(_.disable = locked)
    val opacity = if (locked) 0.6 else 1.0
    lockableControls.foreach(_.opacity = opacity)
    logger.trace(s"Footer controls locked: $locked")
  }

  /**
   * Вставляет содержимое из буфера обмена, обрабатывая разные типы данных.
   * Приоритет: Текст > Изображение > Файлы > HTML > RTF.
   * Использует Try и match для безопасной обработки.
   * ИСПРАВЛЕНО: Добавлена полная обработка match для Try(clipboard.image).
   */
  def pasteFromClipboard(): Unit = {
    val clipboard = Clipboard.systemClipboard
    logger.debug("Attempting paste from clipboard. Available formats: {}", clipboard.contentTypes.mkString(", "))

    // 1. Проверяем ТЕКСТ
    Try(clipboard.getContent(DataFormat.PlainText)) match {
      case Success(content) if content != null && content.isInstanceOf[String] =>
        val textToPaste = content.asInstanceOf[String]
        if (textToPaste.nonEmpty) {
          inputTextArea.insertText(inputTextArea.caretPosition.value, textToPaste)
          logger.info("Pasted TEXT from clipboard into input area.")
          return
        } else {
          logger.debug("Clipboard contains an empty string.")
        }
      case Success(null) => logger.trace("Clipboard returned null for PLAIN_TEXT.")
      case Success(other) => logger.warn(s"Clipboard returned unexpected type for PLAIN_TEXT: ${other.getClass.getName}")
      case Failure(e) => logger.warn(s"Error getting PLAIN_TEXT from clipboard: ${e.getMessage}")
    }

    // 2. Если текст не вставлен, проверяем ИЗОБРАЖЕНИЕ
    if (clipboard.hasImage) {
      Try(clipboard.image) match {
        case Success(image) => // image is of type javafx.scene.image.Image (nullable)
          if (image != null) {
            logger.info(s"Pasted IMAGE from clipboard (size: ${image.width()}x${image.height()}). Calling onImagePasted callback.")
            onImagePasted(image)
            Platform.runLater {
              val placeholder = s"\n[Изображение (${image.width()}x${image.height()}) вставлено и будет отправлено с запросом]\n"
              inputTextArea.insertText(inputTextArea.caretPosition.value, placeholder)
            }
            return // Image processed, exit
          } else {
            logger.debug("Clipboard hasImage is true, but clipboard.image returned null.")
          }
        case Failure(e) =>
          logger.warn(s"Error getting IMAGE from clipboard: ${e.getMessage}")
      }
    }

    // 3. Если не текст и не изображение, проверяем ФАЙЛЫ
    if (clipboard.hasFiles) {
      Try(Option(clipboard.getFiles).map(_.asScala.toList).getOrElse(List.empty)) match {
        case Success(files) if files.nonEmpty =>
          files.headOption.foreach { file =>
            logger.info(s"Pasted FILES from clipboard (${files.size} files). Processing first element: ${file.getAbsolutePath}")
            if (file.isDirectory) onDirectoryDropped(file) else onFileDropped(file)
            Platform.runLater {
              val placeholder = s"\n[Вставлен ${if(file.isDirectory)"каталог" else "файл"}: ${file.getName}]\n"
              inputTextArea.insertText(inputTextArea.caretPosition.value, placeholder)
            }
          }
          return
        case Success(_) => logger.warn("Clipboard has files, but the list is empty.")
        case Failure(e) => logger.warn(s"Error getting FILES from clipboard: ${e.getMessage}")
      }
    }

    // 4. Проверяем HTML
    Try(clipboard.getContent(DataFormat.Html)) match {
      case Success(content) if content != null && content.isInstanceOf[String] =>
        val htmlContent = content.asInstanceOf[String]
        if (htmlContent.nonEmpty) {
          logger.info("Pasted HTML from clipboard. Processing not implemented yet. Content starts with: {}", htmlContent.take(100))
          Platform.runLater {
            inputTextArea.insertText(inputTextArea.caretPosition.value, "[Вставлен HTML контент (обработка не реализована)]")
          }
          return
        } else { logger.debug("Clipboard contains empty HTML.") }
      case Success(null) => logger.trace("Clipboard returned null for HTML.")
      case Success(other) => logger.warn(s"Clipboard returned unexpected type for HTML: ${other.getClass.getName}")
      case Failure(e) => logger.warn(s"Error getting HTML from clipboard: ${e.getMessage}")
    }

    // 5. Проверяем RTF
    Try(clipboard.getContent(DataFormat.Rtf)) match {
      case Success(content) if content != null && content.isInstanceOf[String] =>
        val rtfContent = content.asInstanceOf[String]
        if (rtfContent.nonEmpty) {
          logger.info("Pasted RTF from clipboard. Processing not implemented yet.")
          Platform.runLater {
            inputTextArea.insertText(inputTextArea.caretPosition.value, "[Вставлен RTF контент (обработка не реализована)]")
          }
          return
        } else { logger.debug("Clipboard contains empty RTF.") }
      case Success(null) => logger.trace("Clipboard returned null for RTF.")
      case Success(other) => logger.warn(s"Clipboard returned unexpected type for RTF: ${other.getClass.getName}")
      case Failure(e) => logger.warn(s"Error getting RTF from clipboard: ${e.getMessage}")
    }

    logger.warn("Clipboard contains content, but not in a currently supported/prioritized format for pasting (String, Image, Files, HTML, RTF).")
  }
}