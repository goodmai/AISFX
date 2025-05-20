// src/main/scala/com/aiplatform/model/AppState.scala
package com.aiplatform.model

import com.aiplatform.util.JsonUtil.*
import upickle.default.*
import org.slf4j.LoggerFactory
import com.aiplatform.view.Header
import com.aiplatform.model.FileTreeContext

import scala.util.Try // General utility Try, e.g. for default category list access.

/**
 * Root state of the application.
 * Uses custom ReadWriters from JsonUtil for Instant and Option.
 *
 * @param topics List of all topics.
 * @param activeTopicId ID of the currently active topic (if any). Option is used for explicit absence handling.
 * @param lastActiveTopicPerCategory Map: Category Name -> ID of the last active topic in that category.
 * @param globalAiModel Name of the globally selected AI model.
 * @param availableModels List of available AI models (loaded from API).
 * @param defaultPresets List of standard (non-deletable) presets.
 * @param customPresets List of user-defined presets.
 * @param buttonMappings Map: Category button name -> Assigned preset name.
 * @param fileContext Holds structured information about files selected in the FileTreeView, for context.
 */
case class AppState(
                     topics: List[Topic],
                     activeTopicId: Option[String],
                     lastActiveTopicPerCategory: Map[String, String],
                     globalAiModel: String,
                     availableModels: List[ModelInfo],
                     defaultPresets: List[PromptPreset],
                     customPresets: List[PromptPreset],
                     buttonMappings: Map[String, String],
                     fileContext: Option[FileTreeContext]
                   )

object AppState {
  private val logger = LoggerFactory.getLogger(getClass) // Logger for future needs.

  /**
   * Macro for generating ReadWriter for AppState.
   * It will use implicit RWs for all fields, including our custom ones for Instant and Option.
   */
  implicit val rw: ReadWriter[AppState] = macroRW

  // --- Initial Values ---
  // (Leave as is, but model relevance can be re-verified)
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
    // Add a preset for Global if it wasn't there
    // PromptPreset( name = "Default Global", prompt = "{{INPUT}}", temperature = 0.7, topP = 0.95, topK = 40, modelOverride = None, isDefault = true )
  )

  // Generate initial mappings dynamically from Header.categoryButtonNames
  val initialButtonMappings: Map[String, String] = Header.categoryButtonNames
    .map { categoryName =>
      // Attempt to find a standard preset named "Default CategoryName"
      val defaultPresetName = s"Default ${categoryName.capitalize}"
      initialDefaultPresets.find(_.name.equalsIgnoreCase(defaultPresetName)) match {
        case Some(preset) => categoryName -> preset.name
        // If not found by name, try to find the first available standard (or any, if no standard ones)
        case None => categoryName -> initialDefaultPresets.headOption.map(_.name).getOrElse("Fallback") // Fallback if no presets exist
      }
    }
    // Ensure no specific mapping for "Global" if not needed
    .filterNot { case (cat, _) => cat == "Global" } // Remove mapping for Global, as it uses text directly
    .toMap

  val initialFontFamily: String = Try(javafx.scene.text.Font.getDefault.getFamily).getOrElse("System") // Use system default font
  val initialFontSize = 13
  val initialGlobalModel = "gemini-1.5-flash-latest" // Can be updated to a more current model
  val initialAvailableModels: List[ModelInfo] = List(
    // Leave only one "starter" model, the rest will be loaded via API
    ModelInfo(
      name = initialGlobalModel,
      displayName = "Gemini 1.5 Flash", // More understandable name
      description = Some("Быстрая и универсальная модель."), // Fast and versatile model.
      supportedGenerationMethods = List("generateContent") // Important for filtering
    )
  )

  // Initial application state
  val initialState: AppState = AppState(
    topics = List.empty,
    activeTopicId = None, // Explicitly None
    lastActiveTopicPerCategory = Map.empty,
    globalAiModel = initialGlobalModel,
    availableModels = initialAvailableModels,
    defaultPresets = initialDefaultPresets,
    customPresets = List.empty,
    buttonMappings = initialButtonMappings,
    fileContext = None
  )
}