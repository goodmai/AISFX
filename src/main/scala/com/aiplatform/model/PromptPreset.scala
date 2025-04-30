package com.aiplatform.model


/**
 * Представляет пресет промпта с параметрами для AI модели.
 *
 * @param name Название пресета (для кастомных).
 * @param prompt Текст промпта (может содержать плейсхолдеры, например {{INPUT}}).
 * @param temperature Параметр Temperature (контроль случайности).
 * @param topP Параметр Top-P (ядерная выборка).
 * @param topK Параметр Top-K (ограничение выборки).
 * @param isDefault Является ли этот пресет стандартным (неудаляемым).
 * @param associatedButton Имя кнопки хедера, с которой связан пресет по умолчанию (если есть).
 */
case class PromptPreset(
        name: String,
        prompt: String,
        temperature: Double = 0.7, // Значения по умолчанию
        topP: Double = 0.95,
        topK: Int = 40,
        isDefault: Boolean = false,
        associatedButton: Option[String] = None // Связь с кнопкой хедера
)

object PromptPreset {
    implicit val rw: ReadWriter[PromptPreset] = macroRW
}