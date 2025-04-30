package com.aiplatform.view

import com.aiplatform.model.Dialog
import scalafx.application.Platform
import scalafx.geometry.{Insets, Pos}
import scalafx.scene.control.{Label, ScrollPane}
import scalafx.scene.layout.{Priority, VBox}
import scalafx.scene.Parent
import scalafx.scene.text.Text
import org.slf4j.LoggerFactory

object ResponseArea {
  private val logger = LoggerFactory.getLogger(getClass)

  private val dialogContainer = new VBox {
    spacing = 10
    padding = Insets(10)
    styleClass.add("dialog-container")
  }

  private val statusLabel = new Label {
    styleClass.add("status-label")
    padding = Insets(10)
    visible = false
    managed <== visible
  }


  private val scrollPane = new ScrollPane {
    content = dialogContainer
    fitToWidth = true
    vbarPolicy = ScrollPane.ScrollBarPolicy.AsNeeded
    hbarPolicy = ScrollPane.ScrollBarPolicy.Never
    vgrow = Priority.Always
    styleClass.add("response-scroll-pane")
    id = "responseScrollPane"

    dialogContainer.height.onChange { (_, _, _) =>
      Platform.runLater {
        vvalue = 1.0
      }
    }
  }

  private val rootLayout = new VBox {
    children = Seq(statusLabel, scrollPane)
    VBox.setVgrow(scrollPane, Priority.Always) // ScrollPane занимает все доступное верт. пространство
    styleClass.add("response-area-root")
  }

  /**
   * Создает корневой узел (Parent) для области ответа.
   */
  def create(): Parent = {
    rootLayout
  }

  /**
   * Добавляет один ход диалога (запрос пользователя + ответ AI) в область отображения.
   */
  def addDialogTurn(request: String, response: String): Unit = {
    Platform.runLater {

      statusLabel.visible = false

      val requestLabel = new Label("Вы:") { style = "-fx-font-weight: bold;" }
      val requestTextNode = new Text(request) {
        wrappingWidth <== scrollPane.width - 40
        styleClass.add("request-text")
      }
      val requestBox = new VBox(requestLabel, requestTextNode) {
        style = "-fx-background-color: #f0f0f0; -fx-padding: 8px; -fx-border-radius: 5px; -fx-background-radius: 5px;"
        maxWidth = Double.MaxValue
        alignment = Pos.TopLeft
      }

      // --- Создание визуальных узлов для ответа (без изменений) ---
      val responseLabel = new Label("AI:") { style = "-fx-font-weight: bold;" }
      val responseTextNode = new Text(response) {
        wrappingWidth <== scrollPane.width - 40
        styleClass.add("response-text")
      }
      val responseBox = new VBox(responseLabel, responseTextNode) {
        style = "-fx-background-color: #e6f3ff; -fx-padding: 8px; -fx-border-radius: 5px; -fx-background-radius: 5px;"
        maxWidth = Double.MaxValue
        alignment = Pos.TopLeft
      }

      dialogContainer.children.addAll(requestBox, responseBox)
      // statusLabel.text = "" // Больше не очищаем текст, просто скрываем
      logger.debug("Added dialog turn to ResponseArea.")
    }
  }

  /**
   * Отображает все диалоги для заданного списка (обычно для выбранного топика).
   */
  def displayTopicDialogs(dialogs: List[Dialog]): Unit = {
    Platform.runLater {
      clearDialog() // Сначала очищаем предыдущее содержимое
      // --- УБРАЛИ hideLoadingIndicator() ---
      if (dialogs.isEmpty) {
        // Показываем статусную метку с сообщением
        statusLabel.text = "В этом топике еще нет сообщений."
        statusLabel.style = "" // Сброс стиля (если была ошибка)
        statusLabel.visible = true
        logger.debug("Displaying empty topic in ResponseArea.")
      } else {
        statusLabel.visible = false // Скрываем статусную строку, т.к. есть сообщения
        dialogs.foreach(d => addDialogTurn(d.request, d.response))
        logger.debug(s"Displayed ${dialogs.size} dialog turns for the topic.")
        Platform.runLater(() => scrollPane.vvalue = 0.0)
      }
    }
  }

  /**
   * Полностью очищает область отображения диалога и статусную строку.
   */
  def clearDialog(): Unit = {
    Platform.runLater {
      dialogContainer.children.clear()
      statusLabel.text = "" // Очищаем текст
      statusLabel.visible = false // Скрываем
      logger.debug("ResponseArea cleared.")
    }
  }

  /**
   * Отображает сообщение об ошибке в статусной строке.
   */
  def showError(msg: String): Unit = {
    Platform.runLater {
      logger.warn(s"Showing error in ResponseArea: $msg")
      clearDialog() // Очищаем основной контент
      statusLabel.text = s"Ошибка: $msg" // Устанавливаем текст ошибки
      statusLabel.style = "-fx-text-fill: red;" // Красный цвет
      statusLabel.visible = true // Делаем видимой
    }
  }

  /**
   * Показывает статус "Обработка запроса...".
   */
  def showLoadingIndicator(): Unit = {
    Platform.runLater {
      logger.debug("Showing 'Processing request...' status in ResponseArea")
      statusLabel.text = "Обработка запроса..." // Устанавливаем статус
      statusLabel.style = "" // Сбрасываем стиль
      statusLabel.visible = true // Показываем метку
    }
  }
}