package com.aiplatform.view

import com.aiplatform.controller.MainController
import scalafx.scene.control.{Button, TextArea}
import scalafx.scene.layout.{HBox, Priority}
import scalafx.scene.Parent
import scalafx.geometry.Insets

// Объект для создания панели ввода запроса
object RequestArea {

  def create(controller: MainController): Parent = {
    val requestTextArea = new TextArea {
      promptText = "Введите ваш запрос здесь..."
      prefRowCount = 3
      hgrow = Priority.Always // Растягиваться по горизонтали
    }

    val sendButton = new Button("Отправить") {
      onAction = _ => { // Используем _ для неиспользуемого ActionEvent
        val text = requestTextArea.text.value
        controller.processRequest(text) // Вызываем метод контроллера
        // Очистка поля после отправки (опционально)
        // requestTextArea.text = ""
      }
      styleClass.add("send-button")
    }

    new HBox {
      padding = Insets(10)
      spacing = 10
      children = Seq(requestTextArea, sendButton)
      styleClass.add("request-area")
    }
  }
}