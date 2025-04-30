// src/main/scala/com/aiplatform/view/Header.scala
package com.aiplatform.view

import scalafx.scene.control.Button
import scalafx.scene.layout.HBox
import scalafx.beans.property.StringProperty
import org.slf4j.LoggerFactory

/**
 * Компонент хедера приложения, содержащий кнопки навигации/действий.
 * @param onHeaderButtonClicked Функция обратного вызова, вызываемая при нажатии кнопки.
 * Принимает имя нажатой кнопки в качестве аргумента.
 */
class Header(onHeaderButtonClicked: String => Unit) {

  private val logger = LoggerFactory.getLogger(getClass)

  private val buttonNames = List("Research", "Code", "Review", "Test", "Deploy", "Audio", "Stream", "Exam", "Integrations", "Settings")
  private val activeHeaderButton = StringProperty(buttonNames.head) // Изначально активна первая
  private var headerButtons: List[Button] = List.empty

  // Стили для кнопок
  private val inactiveButtonStyle = "-fx-background-color: #333333; -fx-text-fill: white; -fx-padding: 10px;"
  private val activeButtonStyle   = "-fx-background-color: #555555; -fx-text-fill: white; -fx-padding: 10px;" // Светлее фон

  /**
   * Обновляет стили всех кнопок в хедере в соответствии с текущей активной кнопкой.
   */
  private def updateHeaderButtonStyles(): Unit = {
    val currentActive = activeHeaderButton.value
    headerButtons.foreach { btn =>
      btn.style = if (btn.text.value == currentActive) activeButtonStyle else inactiveButtonStyle
    }
    logger.trace("Header button styles updated. Active: {}", currentActive)
  }

  /**
   * Создает и возвращает узел HBox, представляющий хедер.
   */
  def createHeaderNode(): HBox = {
    headerButtons = buttonNames.map { name =>
      new Button(name) {
        style = inactiveButtonStyle // Начальный стиль
        onAction = _ => {
          if (activeHeaderButton.value != name) { // Реагируем только если нажата другая кнопка
            logger.debug("Header button '{}' clicked.", name)
            activeHeaderButton.value = name // Обновляем внутреннее состояние
            updateHeaderButtonStyles()     // Обновляем стили
            onHeaderButtonClicked(name)    // Вызываем внешний обработчик
          } else {
            logger.trace("Header button '{}' re-clicked (no action).", name)
          }
        }
      }
    }

    // Применяем стили после создания всех кнопок
    updateHeaderButtonStyles()

    // Создаем сам HBox
    new HBox {
      style = "-fx-background-color: #252525; -fx-padding: 10px;"
      children = headerButtons
      spacing = 10
    }
  }
}