// src/main/scala/com/aiplatform/model/AppState.scala
package com.aiplatform.model

import com.aiplatform.util.JsonUtil.*
import upickle.default.*
import org.slf4j.LoggerFactory
import com.aiplatform.view.Header

import scala.util.Try // Для доступа к списку категорий по умолчанию

/**
 * Корневое состояние приложения.
 * Использует кастомные ReadWriters из JsonUtil для Instant и Option.
 *
 * @param topics Список всех топиков.
 * @param activeTopicId ID текущего активного топика (если есть). Используем Option для явной обработки отсутствия.
 * @param lastActiveTopicPerCategory Карта: Имя категории -> ID последнего активного топика в этой категории.
 * @param globalAiModel Имя глобально выбранной модели AI.
 * @param availableModels Список доступных моделей AI (загружается из API).
 * @param defaultPresets Список стандартных (неудаляемых) пресетов.
 * @param customPresets Список пользовательских пресетов.
 * @param buttonMappings Карта: Имя кнопки категории -> Имя назначенного пресета.
// * @param fontFamily Название шрифта для UI.
// * @param fontSize Размер шрифта для UI.
 */
case class AppState(
                     topics: List[Topic],
                     activeTopicId: Option[String],
                     lastActiveTopicPerCategory: Map[String, String],
                     globalAiModel: String,
                     availableModels: List[ModelInfo],
                     defaultPresets: List[PromptPreset],
                     customPresets: List[PromptPreset],
                     buttonMappings: Map[String, String]
                     // fontFamily: String, // <<< УДАЛЕНО >>>
                     // fontSize: Int      // <<< УДАЛЕНО >>>
                   )

object AppState {
  private val logger = LoggerFactory.getLogger(getClass) // Логгер на случай будущих нужд

  // Макрос для генерации ReadWriter для AppState.
  // Он будет использовать неявные RW для всех полей, включая наши кастомные для Instant и Option.
  implicit val rw: ReadWriter[AppState] = macroRW

  // --- Начальные значения ---
  // (Оставляем как есть, но можно перепроверить актуальность моделей)
  val initialDefaultPresets: List[PromptPreset] = List(
    PromptPreset( name = "Default Research", prompt = "Выполни исследование на тему: {{INPUT}}", temperature = 0.6, topP = 0.9, topK = 30, modelOverride = Some("gemini-1.5-pro-latest"), isDefault = true ),
    PromptPreset( name = "Default Code", prompt = "Напиши код на Scala для: {{INPUT}}", temperature = 0.5, topP = 0.95, topK = 40, modelOverride = None, isDefault = true ),
    PromptPreset( name = "Default Review", prompt = "Сделай ревью кода: \n```\n{{INPUT}}\n```\nУкажи на возможные проблемы и предложи улучшения.", temperature = 0.4, topP = 0.95, topK = 50, modelOverride = Some("gemini-1.5-flash-latest"), isDefault = true ),
    PromptPreset( name = "Default Test", prompt = "Напиши модульные тесты (unit tests) для следующего кода на Scala:\n```scala\n{{INPUT}}\n```", temperature = 0.5, topP = 0.95, topK = 40, modelOverride = None, isDefault = true ),
    PromptPreset( name = "Default Deploy", prompt = "Опиши шаги для деплоя приложения, связанного с: {{INPUT}}", temperature = 0.7, topP = 0.9, topK = 30, modelOverride = None, isDefault = true ),
    PromptPreset( name = "Default Audio", prompt = "Транскрибируй аудио или ответь на вопрос об аудио: {{INPUT}}", temperature = 0.7, topP = 0.95, topK = 40, modelOverride = None, isDefault = true ),
    PromptPreset( name = "Default Stream", prompt = "Обработай стрим данных: {{INPUT}}", temperature = 0.6, topP = 0.9, topK = 40, modelOverride = None, isDefault = true ),
    PromptPreset( name = "Default Exam", prompt = "Создай вопросы для экзамена по теме: {{INPUT}}", temperature = 0.8, topP = 0.95, topK = 50, modelOverride = None, isDefault = true ),
    PromptPreset( name = "Default Integrations", prompt = "Как интегрировать {{INPUT}} с другими системами?", temperature = 0.7, topP = 0.9, topK = 40, modelOverride = None, isDefault = true )
    // Добавим пресет для Global, если его не было
    // PromptPreset( name = "Default Global", prompt = "{{INPUT}}", temperature = 0.7, topP = 0.95, topK = 40, modelOverride = None, isDefault = true )
  )

  // Генерируем начальные маппинги динамически из Header.categoryButtonNames
  val initialButtonMappings: Map[String, String] = Header.categoryButtonNames
    .map { categoryName =>
      // Пытаемся найти стандартный пресет с именем "Default ИмяКатегории"
      val defaultPresetName = s"Default ${categoryName.capitalize}"
      initialDefaultPresets.find(_.name.equalsIgnoreCase(defaultPresetName)) match {
        case Some(preset) => categoryName -> preset.name
        // Если не нашли по имени, пытаемся найти первый попавшийся стандартный (или любой, если стандартных нет)
        case None => categoryName -> initialDefaultPresets.headOption.map(_.name).getOrElse("Fallback") // Fallback, если пресетов нет
      }
    }
    // Убедимся, что для "Global" нет специфичного маппинга, если он не нужен
    .filterNot { case (cat, _) => cat == "Global" } // Убираем маппинг для Global, т.к. он использует текст напрямую
    .toMap

  val initialFontFamily: String = Try(javafx.scene.text.Font.getDefault.getFamily).getOrElse("System") // Используем системный шрифт по умолчанию
  val initialFontSize = 13
  val initialGlobalModel = "gemini-1.5-flash-latest" // Можно обновить на более актуальную модель
  val initialAvailableModels: List[ModelInfo] = List(
    // Оставляем только одну "стартовую" модель, остальное загрузится
    ModelInfo(
      name = initialGlobalModel,
      displayName = "Gemini 1.5 Flash", // Более понятное имя
      description = Some("Быстрая и универсальная модель."),
      supportedGenerationMethods = List("generateContent") // Важно для фильтрации
    )
  )

  // Начальное состояние приложения
  val initialState: AppState = AppState(
    topics = List.empty,
    activeTopicId = None, // Явно None
    lastActiveTopicPerCategory = Map.empty,
    globalAiModel = initialGlobalModel,
    availableModels = initialAvailableModels,
    defaultPresets = initialDefaultPresets,
    customPresets = List.empty,
    buttonMappings = initialButtonMappings,
//    fontFamily = initialFontFamily,
//    fontSize = initialFontSize
  )
}