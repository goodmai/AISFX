// src/main/scala/com/aiplatform/view/Header.scala
package com.aiplatform.view

// Импорты ScalaFX
import scalafx.Includes._
import scalafx.beans.property.StringProperty
import scalafx.scene.control.{Button, Tooltip}
import scalafx.scene.layout.HBox

// Импорты JavaFX
import javafx.scene.paint.{Color => JFXColor} // Для deriveColor

// Импорты для логирования и утилит
import org.slf4j.LoggerFactory // <<< Используем стандартный SLF4j логгер
import scala.util.Try
// Убраны импорты Duration и TimeUnit, т.к. ThrottlingLogger не используется

/**
 * Компонент хедера приложения. Отображает кнопки категорий и кнопку настроек.
 * Использует стандартный логгер SLF4j.
 *
 * @param onHeaderButtonClicked Функция обратного вызова, вызываемая при нажатии
 * любой кнопки в хедере. Передает имя (или userData) нажатой кнопки.
 */
class Header(onHeaderButtonClicked: String => Unit) {

  // Инициализируем стандартный логгер SLF4j
  private val logger = LoggerFactory.getLogger(getClass)

  private val activeCategoryButtonName = StringProperty(Header.categoryButtonNames.headOption.getOrElse(""))
  private var headerButtons: List[Button] = List.empty

  // Карты цветов и подсказок остаются без изменений
  private val buttonColorMap: Map[String, String] = Map(
    "Research" -> "#FFD1DC", "Code" -> "#FFECB3", "Review" -> "#C8E6C9",
    "Test" -> "#BBDEFB", "Deploy" -> "#D1C4E9", "Audio" -> "#FFCCBC",
    "Stream" -> "#CFD8DC", "Exam" -> "#F0F4C3", "Integrations" -> "#D7CCC8",
    "Global" -> "#E0E0E0", "Settings" -> "#616161"
  )
  private val buttonTooltips: Map[String, Tooltip] = Map(
    "Research" -> Tooltip("Запросы на исследование"), "Code" -> Tooltip("Запросы на генерацию/анализ кода"),
    "Review" -> Tooltip("Запросы на ревью кода"), "Test" -> Tooltip("Запросы на генерацию тестов"),
    "Deploy" -> Tooltip("Запросы, связанные с деплоем"), "Audio" -> Tooltip("Запросы для аудио"),
    "Stream" -> Tooltip("Запросы для потоков данных"), "Exam" -> Tooltip("Запросы для экзаменов"),
    "Integrations" -> Tooltip("Запросы об интеграциях"),
    "Global" -> Tooltip("Режим использования глобальной модели AI (без пресетов категорий)"),
    "Settings" -> Tooltip("Открыть настройки приложения")
  ).map { case (k, v) => (k, v: Tooltip) }

  // Функция изменения яркости остается без изменений
  private def adjustBrightness(hexColor: String, factor: Double): String = Try {
    val color = JFXColor.web(hexColor)
    val adjustedColor = color.deriveColor(0, 1, factor, 1)
    def toHex(d: Double): String = f"${(d * 255).round.toInt.max(0).min(255)}%02x"
    s"#${toHex(adjustedColor.getRed)}${toHex(adjustedColor.getGreen)}${toHex(adjustedColor.getBlue)}"
  }.getOrElse(hexColor)

  /**
   * Обновляет стили кнопок хедера.
   * Использует ТОЛЬКО явные, константные значения в инлайн-стилях.
   * Использует стандартный логгер SLF4j для предупреждений.
   */
  private def updateHeaderButtonStyles(): Unit = {
    val currentActiveCategory = activeCategoryButtonName.value
    logger.trace(s"Updating header button styles. Active category: '$currentActiveCategory'")

    headerButtons.foreach { btn =>
      val buttonName = btn.userData match {
        case name: String => name
        case _ =>
          val btnText = btn.text.value
          // <<< ВОЗВРАЩАЕМ СТАНДАРТНЫЙ ЛОГГЕР ДЛЯ ПРЕДУПРЕЖДЕНИЯ >>>
          logger.warn(s"Button user data not set or not a String for button text: $btnText. Using text as fallback.")
          btnText // Fallback на текст кнопки
      }

      // Логика определения состояния и цветов остается прежней
      val isCategoryButton = Header.categoryButtonNames.contains(buttonName)
      val isSettingsButton = buttonName == "Settings"
      val isActive = isCategoryButton && buttonName == currentActiveCategory

      val baseBgColor = buttonColorMap.getOrElse(buttonName, "#E0E0E0")
      val activeBrightnessFactor = 0.85; val hoverBrightnessFactor = 0.95; val activeHoverBrightnessFactor = 0.80

      val finalBgColor = if (isSettingsButton) baseBgColor else if (isActive) adjustBrightness(baseBgColor, activeBrightnessFactor) else baseBgColor
      val hoverBgColor = if (isSettingsButton) adjustBrightness(baseBgColor, 1.1) else if (isActive) adjustBrightness(baseBgColor, activeHoverBrightnessFactor) else adjustBrightness(baseBgColor, hoverBrightnessFactor)
      val textColor = if (isSettingsButton) "white" else "black"
      val fontWeight = if(isActive) "bold" else "normal"
      val padding = "6px 12px"
      val bgRadius = "5px"
      val borderColor = if (isActive) "#555555" else "transparent"
      val borderWidth = if (isActive) "1.5px" else "0px"
      val borderRadius = if (isActive) "5px" else "0px"
      val cursor = "hand"

      // Формируем инлайн-стиль только с явными значениями
      val baseStyle = s"""
    -fx-background-color: $finalBgColor;
    -fx-text-fill: $textColor;
    -fx-font-weight: $fontWeight;
    -fx-padding: $padding;
    -fx-background-radius: $bgRadius;
    -fx-border-color: $borderColor;
    -fx-border-width: $borderWidth;
    -fx-border-radius: $borderRadius;
    -fx-cursor: $cursor;
  """
      btn.style = baseStyle // Применяем стиль

      // Стиль при наведении - также использует только явные значения
      val hoverStyle = s"""
    -fx-background-color: $hoverBgColor;
    -fx-text-fill: $textColor;
    -fx-font-weight: $fontWeight;
    -fx-padding: $padding;
    -fx-background-radius: $bgRadius;
    -fx-border-color: $borderColor;
    -fx-border-width: $borderWidth;
    -fx-border-radius: $borderRadius;
    -fx-cursor: $cursor;
  """
      // Устанавливаем обработчики наведения
      btn.onMouseEntered = _ => { btn.style = hoverStyle }
      btn.onMouseExited = _ => { btn.style = baseStyle }
    }
  }

  /**
   * Создает и возвращает узел HBox, представляющий хедер.
   */
  def createHeaderNode(): HBox = {
    headerButtons = Header.allButtonNames.map { name =>
      new Button(name) {
        tooltip = buttonTooltips.get(name).orNull
        userData = name
        onAction = { _ =>
          logger.debug(s"Header button '$name' clicked.")
          onHeaderButtonClicked(name)
        }
      }
    }
    updateHeaderButtonStyles()
    new HBox {
      styleClass.add("header-area")
      children = headerButtons
    }
  }

  /**
   * Устанавливает активную кнопку категории.
   */
  def setActiveButton(categoryName: String): Unit = {
    if (Header.categoryButtonNames.contains(categoryName)) {
      if (activeCategoryButtonName.value != categoryName) {
        logger.debug(s"Setting active category button to: '$categoryName'")
        activeCategoryButtonName.value = categoryName
        updateHeaderButtonStyles()
      } else {
        logger.trace(s"setActiveButton called for already active category '$categoryName'. Ensuring styles are correct.")
        updateHeaderButtonStyles()
      }
    } else {
      if (activeCategoryButtonName.value.nonEmpty) {
        logger.debug(s"setActiveButton called with non-category name: '$categoryName'. Clearing category highlight.")
        activeCategoryButtonName.value = "" // Clear active category if name is not a valid category
        updateHeaderButtonStyles()
      }
    }
  }

  /**
   * Возвращает имя текущей активной кнопки категории из состояния хедера.
   */
  def getCurrentActiveButtonName: String = activeCategoryButtonName.value

} // Конец класса Header

/**
 * Объект-компаньон для констант.
 */
object Header {
  val categoryButtonNames: List[String] = List(
    "Research", "Code", "Review", "Test", "Deploy",
    "Audio", "Stream", "Exam", "Integrations", "Global"
  )
  val allButtonNames: List[String] = categoryButtonNames ++ List("Settings")
}