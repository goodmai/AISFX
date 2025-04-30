package com.aiplatform.model

import java.time.Instant
import upickle.default._

case class AppState(
                     topics: List[Topic] = List.empty,
                     activeTopicId: Option[String] = None,
                     // --- ДОБАВЛЕНО: Отслеживание последнего активного топика для каждой категории ---
                     lastActiveTopicPerCategory: Map[String, String] = Map.empty,
                     // -----------------------------------------------------------------------------
                     globalAiModel: String,
                     availableModels: List[ModelInfo] = List.empty,
                     defaultPresets: List[PromptPreset] = AppState.initialDefaultPresets,
                     customPresets: List[PromptPreset] = List.empty,
                     buttonMappings: Map[String, String] = AppState.initialButtonMappings,
                     fontFamily: String = "System",
                     fontSize: Int = 13
                   )

object AppState {
  // --- Сериализаторы ---
  // Убедимся, что все RW определены ДО `macroRW[AppState]`
  private object AppStateInternal { // Приватный объект для изоляции instantRW
    implicit val instantRW: ReadWriter[Instant] =
      readwriter[Long].bimap[Instant](
        _.getEpochSecond, // Сериализация Instant в Long (секунды)
        Instant.ofEpochSecond(_) // Десериализация Long в Instant
      )
  }
  implicit val instantRW: ReadWriter[Instant] = AppStateInternal.instantRW // Экспортируем RW для Instant

  implicit val modelInfoRW: ReadWriter[ModelInfo] = ModelInfo.rw
  implicit val promptPresetRW: ReadWriter[PromptPreset] = PromptPreset.rw
  implicit val dialogRW: ReadWriter[Dialog] = Dialog.rw
  // Используем RW для Topic из его компаньон-объекта
  implicit val topicRW: ReadWriter[Topic] = Topic.rw
  // RW для List[Topic] должен генерироваться автоматически, если есть RW для Topic
  implicit val topicListRW: ReadWriter[List[Topic]] = implicitly[ReadWriter[List[Topic]]]
  // RW для Map[String, String] тоже генерируется автоматически
  implicit val mapRW: ReadWriter[Map[String, String]] = implicitly[ReadWriter[Map[String, String]]]
  // RW для AppState (должен быть последним)
  implicit val rw: ReadWriter[AppState] = macroRW

  // --- Начальные значения ---
  val initialDefaultPresets: List[PromptPreset] = List(
    PromptPreset("Default Research", "Выполни исследование на тему: {{INPUT}}", 0.6, 0.9, 30, isDefault = true),
    PromptPreset("Default Code", "Напиши код на Scala для: {{INPUT}}", 0.5, 0.95, 40, isDefault = true),
    PromptPreset("Default Review", "Сделай ревью кода: \n```\n{{INPUT}}\n```\nУкажи на возможные проблемы и предложи улучшения.", 0.4, 0.95, 50, isDefault = true),
    PromptPreset("Default Test", "Напиши модульные тесты (unit tests) для следующего кода на Scala:\n```scala\n{{INPUT}}\n```", 0.5, 0.95, 40, isDefault = true),
    PromptPreset("Default Deploy", "Опиши шаги для деплоя приложения, связанного с: {{INPUT}}", 0.7, 0.9, 30, isDefault = true),
    PromptPreset("Default Audio", "Транскрибируй аудио или ответь на вопрос об аудио: {{INPUT}}", 0.7, 0.95, 40, isDefault = true),
    PromptPreset("Default Stream", "Обработай стрим данных: {{INPUT}}", 0.6, 0.9, 40, isDefault = true),
    PromptPreset("Default Exam", "Создай вопросы для экзамена по теме: {{INPUT}}", 0.8, 0.95, 50, isDefault = true),
    PromptPreset("Default Integrations", "Как интегрировать {{INPUT}} с другими системами?", 0.7, 0.9, 40, isDefault = true)
  )

  val initialButtonMappings: Map[String, String] = Map(
    "Research" -> "Default Research",
    "Code" -> "Default Code",
    "Review" -> "Default Review",
    "Test" -> "Default Test",
    "Deploy" -> "Default Deploy",
    "Audio" -> "Default Audio",
    "Stream" -> "Default Stream",
    "Exam" -> "Default Exam",
    "Integrations" -> "Default Integrations"
  )

  // Начальное состояние приложения
  val initialState: AppState = AppState(
    topics = List.empty,
    activeTopicId = None,
    lastActiveTopicPerCategory = Map.empty, // <-- Инициализируем пустой картой
    globalAiModel = "gemini-1.5-flash-latest",
    availableModels = List(ModelInfo("gemini-1.5-flash-latest", "gemini-1.5-flash-latest")),
    defaultPresets = initialDefaultPresets,
    customPresets = List.empty,
    buttonMappings = initialButtonMappings,
    fontFamily = "System",
    fontSize = 13
  )
}