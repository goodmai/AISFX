// updated: scala/com/aiplatform/view/RequestArea.scala
package com.aiplatform.view

import com.aiplatform.controller.MainController
import scalafx.application.Platform
import scalafx.scene.control.{Button, TextArea, Tooltip}
import scalafx.scene.layout.{HBox, Priority}
import scalafx.scene.Parent
import scalafx.geometry.{Insets, Pos}
import scalafx.scene.image.{Image, ImageView}
import scalafx.scene.text.Text // Добавлен Text для fallback иконки
import org.slf4j.LoggerFactory // Логирование
import scala.util.Try

// Объект для создания панели ввода запроса
object RequestArea {

  private val logger = LoggerFactory.getLogger(getClass)

  // --- ИЗМЕНЕНИЕ: Сделаем TextArea доступным внутри объекта ---
  private lazy val requestTextArea = new TextArea {
    promptText = "Введите ваш запрос здесь..."
    prefRowCount = 3 // Можно сделать 4 для большего комфорта
    hgrow = Priority.Always
    id = "requestTextArea"
    wrapText = true // Перенос текста
  }
  // ----------------------------------------------------------

  def create(controller: MainController): Parent = {

    val sendButton = new Button("Отправить") {
      onAction = _ => {
        val text = requestTextArea.text.value
        // Вызываем контроллер, он сам решит, надо ли чистить поле
        controller.processRequest(text)
        // Очистка поля теперь будет вызываться из контроллера через clearInput() при успехе
      }
      styleClass.add("send-button")
      prefWidth = 100
      tooltip = Tooltip("Отправить запрос AI")
      defaultButton = true // Делаем кнопкой по умолчанию (сработает на Enter в TextArea?)
    }

    // Кнопка "Новый топик" (как в предыдущем шаге)
    val newTopicButton = new Button {
      graphic = Try {
        // Попробуйте разные пути, если иконка не грузится
        val imageUrl = getClass.getResource("/icons/plus-circle.png") // Путь к иконке
        // Или val imageUrl = new java.io.File("path/to/icons/plus-circle.png").toURI.toURL
        if (imageUrl == null) throw new Exception("Icon resource not found at /icons/plus-circle.png")
        new ImageView(new Image(imageUrl.toExternalForm, 16, 16, true, true)) {
          styleClass.add("icon-view") // Добавляем класс для стилизации, если нужно
        }
      }.recover { case e: Exception =>
        logger.warn(s"Failed to load new topic icon: ${e.getMessage}. Using fallback text.")
        new Text("+") // Запасной вариант - текст "+"
      }.get // Получаем результат Try
      style = "-fx-background-color: transparent; -fx-padding: 5px;"
      tooltip = Tooltip("Начать новый диалог (очистить)")
      onAction = _ => {
        logger.debug("New Topic button clicked.")
        controller.startNewTopic() // Вызов метода в контроллере
      }
      styleClass.add("new-topic-button")
    }

    // Панель с кнопками
    val buttonPanel = new HBox {
      spacing = 5 // Уменьшил расстояние
      alignment = Pos.BottomLeft
      children = Seq(sendButton, newTopicButton)
    }

    // Основная панель HBox
    new HBox {
      padding = Insets(10)
      spacing = 10
      children = Seq(requestTextArea, buttonPanel) // requestTextArea теперь используется из lazy val
      styleClass.add("request-area")
      alignment = Pos.BottomLeft
    }
  }

  /**
   * Очищает текстовое поле для ввода запроса.
   * Должен вызываться в потоке JavaFX.
   */
  def clearInput(): Unit = {
    // Так как requestTextArea - lazy val, она будет инициализирована при первом доступе
    // Вызов из контроллера должен происходить уже после создания UI
    if (requestTextArea != null) { // Доп. проверка, хотя lazy val не должен быть null после инициализации
      Platform.runLater { // Убедимся, что выполняется в UI-потоке
        requestTextArea.text = ""
        logger.debug("Request input area cleared.")
      }
    } else {
      logger.warn("Attempted to clear input area, but requestTextArea is somehow null.")
    }
  }
}