package com.aiplatform.view

import scalafx.scene.control.Button
import scalafx.scene.layout.HBox
import scalafx.beans.property.StringProperty
import org.slf4j.LoggerFactory
import scalafx.Includes._
import scala.util.Try // Для безопасного парсинга цвета

/**
 * Компонент хедера приложения, содержащий кнопки навигации/действий.
 * @param onHeaderButtonClicked Функция обратного вызова, вызываемая при нажатии кнопки.
 * Принимает имя нажатой кнопки в качестве аргумента.
 */
class Header(onHeaderButtonClicked: String => Unit) {

  private val logger = LoggerFactory.getLogger(getClass)

  // Используем buttonNames из companion object ниже
  // Инициализируем первой кнопкой
  private val activeHeaderButton = StringProperty(Header.buttonNames.headOption.getOrElse(""))
  private var headerButtons: List[Button] = List.empty

  // --- Пастельные цвета КОЖЗГСФ ---
  private val pastelColors: List[String] = List(
    "#FFC0CB", "#FFDAB9", "#FFFFE0", "#90EE90", "#ADD8E6", "#B0C4DE", "#E6E6FA", // КОЖЗГСФ
    "#FFB6C1", "#98FB98" // Дополнительные для Exam, Integrations
  )

  // Карта: Имя кнопки -> Цвет
  private val buttonColorMap: Map[String, String] = Header.buttonNames
    .filterNot(_ == "Settings")
    .zip(pastelColors)
    .toMap

  // Функция для модификации цвета (пример осветления/затемнения)
  private def adjustBrightness(hexColor: String, factor: Double): String = {
    Try {
      val color = javafx.scene.paint.Color.web(hexColor)
      val brighterColor = color.deriveColor(0, 1, factor, 1)
      def toHex(d: Double): String = f"${(d * 255).round.toInt.max(0).min(255)}%02x"
      s"#${toHex(brighterColor.getRed)}${toHex(brighterColor.getGreen)}${toHex(brighterColor.getBlue)}"
    }.getOrElse(hexColor)
  }


  /**
   * Обновляет стили всех кнопок в хедере в соответствии с текущей активной кнопкой.
   */
  private def updateHeaderButtonStyles(): Unit = {
    val currentActive = activeHeaderButton.value
    headerButtons.foreach { btn =>
      val buttonName = btn.text.value
      val baseColor = buttonColorMap.getOrElse(buttonName, "#D3D3D3") // Серый для Settings/fallback
      val isActive = buttonName == currentActive

      val finalColor = if (isActive && buttonName != "Settings") adjustBrightness(baseColor, 0.85) else baseColor
      val borderStyle = if (isActive && buttonName != "Settings") "-fx-border-color: #333333; -fx-border-width: 1.5px; -fx-border-radius: 3px;" else "-fx-border-width: 0px;"
      val textColor = if (buttonName == "Settings") "white" else "black"

      btn.style = s"""
        -fx-background-color: $finalColor;
        -fx-text-fill: $textColor;
        -fx-padding: 8px 15px;
        -fx-font-size: 13px;
        -fx-background-radius: 5px;
        $borderStyle
      """
    }
    logger.trace("Header button styles updated. Active: {}", currentActive)
  }

  /**
   * Создает и возвращает узел HBox, представляющий хедер.
   */
  def createHeaderNode(): HBox = {
    headerButtons = Header.buttonNames.map { name =>
      new Button(name) {
        // Стиль установится в updateHeaderButtonStyles
        onAction = { _ =>
          val previouslyActive = activeHeaderButton.value
          // Позволяем нажимать Settings повторно, остальные - только если не активны
          if (previouslyActive != name || name == "Settings") {
            logger.debug("Header button '{}' clicked.", name)
            // Обновляем состояние только если кнопка НЕ Settings и она не была активной
            if (name != "Settings" && previouslyActive != name) {
              activeHeaderButton.value = name // Обновляем свойство
              updateHeaderButtonStyles() // Обновляем стили
            }
            // Всегда вызываем колбэк (для Settings и для смены категории)
            onHeaderButtonClicked(name)
          } else {
            logger.trace("Header button '{}' re-clicked (no action).", name)
          }
        }
      }
    }

    // Применяем начальные стили
    updateHeaderButtonStyles()

    new HBox {
      style = "-fx-background-color: #E8E8E8; -fx-padding: 10px; -fx-border-color: #CCCCCC; -fx-border-width: 0 0 1 0;"
      children = headerButtons
      spacing = 8
    }
  }

  // --- ДОБАВЛЕН МЕТОД ---
  /**
   * Устанавливает активную кнопку программно.
   * Используется контроллером при инициализации или смене категории.
   * @param buttonName Имя кнопки, которую нужно сделать активной.
   */
  def setActiveButton(buttonName: String): Unit = {
    if (Header.buttonNames.contains(buttonName) && buttonName != "Settings") {
      if (activeHeaderButton.value != buttonName) {
        logger.debug(s"Programmatically setting active button to: '$buttonName'")
        activeHeaderButton.value = buttonName
        updateHeaderButtonStyles()
      } else {
        logger.trace(s"Programmatic setActiveButton called for already active button '$buttonName'.")
      }
    } else if (buttonName == "Settings") {
      logger.warn("setActiveButton called with 'Settings'. Settings button cannot be programmatically activated.")
    } else {
      logger.warn(s"setActiveButton called with invalid button name: '$buttonName'. Ignoring.")
    }
  }
  // --- ------------- ---

}

object Header {
  // Список имен кнопок
  val buttonNames: List[String] = List("Research", "Code", "Review", "Test", "Deploy", "Audio", "Stream", "Exam", "Integrations", "Settings")
}