// main/scala/com/aiplatform/model/PromptPreset.scala
package com.aiplatform.model

import upickle.default._

/**
 * Представляет пресет промпта с параметрами для AI модели.
 *
 * @param name Название пресета. Должно быть уникальным.
 * @param prompt Текст промпта (может содержать плейсхолдеры, например {{INPUT}}).
 * @param temperature Параметр Temperature (контроль случайности).
 * @param topP Параметр Top-P (ядерная выборка).
 * @param topK Параметр Top-K (ограничение выборки).
 * @param modelOverride Модель AI, специфичная для этого пресета (если None, используется глобальная). // ИЗМЕНЕНО
 * @param isDefault Является ли этот пресет стандартным (неудаляемым, обычно).
 */
case class PromptPreset(
                         name: String,
                         prompt: String,
                         temperature: Double = 0.7,
                         topP: Double = 0.95,
                         topK: Int = 40,
                         modelOverride: Option[String] = None, 
                         isDefault: Boolean = false
                       )

object PromptPreset {
    implicit val rw: ReadWriter[PromptPreset] = macroRW
}