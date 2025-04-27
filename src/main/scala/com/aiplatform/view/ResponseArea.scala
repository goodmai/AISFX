package com.aiplatform.view

import scalafx.scene.control.{Label, ProgressIndicator, ScrollPane, TextArea}
import scalafx.scene.layout.{Priority, StackPane, VBox}
import scalafx.scene.Parent
import scalafx.geometry.Pos

// Объект для создания панели отображения ответа
object ResponseArea {

  private val responseTextArea = new TextArea {
    editable = false
    wrapText = true
    vgrow = Priority.Always
    styleClass.add("response-text-area")
  }

  private val statusLabel = new Label {
    styleClass.add("status-label")
  }

  private val loadingIndicator = new ProgressIndicator {
    visible = false // Изначально невидимый
    maxWidth = 50
    maxHeight = 50
  }

  private val responseContainer = new VBox {
    children = Seq(statusLabel, responseTextArea)
    vgrow = Priority.Always
    styleClass.add("response-container")
  }

  private val stackPane = new StackPane {
    children = Seq(responseContainer, loadingIndicator)
    alignment = Pos.Center
    vgrow = Priority.Always
    styleClass.add("response-stack-pane")
  }


  def create(): Parent = {
    // Оборачиваем в ScrollPane на случай длинных ответов
    new ScrollPane {
      content = stackPane
      fitToWidth = true
      fitToHeight = true
      vgrow = Priority.Always
      styleClass.add("response-scroll-pane")
    }
  }

  def showError(msg: String): Unit = {
    println(s"[ResponseArea] Showing error: $msg") // Логирование
    responseTextArea.text = "" // Очищаем основное поле
    statusLabel.text = s"Ошибка: $msg"
    statusLabel.style = "-fx-text-fill: red;" // Пример простого стиля ошибки
    loadingIndicator.visible = false
    responseContainer.visible = true
  }

  def showLoadingIndicator(): Unit = {
    println("[ResponseArea] Showing loading indicator")
    statusLabel.text = "Обработка запроса..."
    statusLabel.style = "" // Сброс стиля
    responseTextArea.text = ""
    loadingIndicator.visible = true
    responseContainer.visible = false // Скрываем текст на время загрузки
  }

  def updateResponse(text: String): Unit = {
    println("[ResponseArea] Updating response")
    statusLabel.text = "" // Очищаем статус
    statusLabel.style = ""
    responseTextArea.text = text
    loadingIndicator.visible = false
    responseContainer.visible = true
  }
}