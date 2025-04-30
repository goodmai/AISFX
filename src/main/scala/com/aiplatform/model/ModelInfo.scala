// main/scala/com/aiplatform/model/ModelInfo.scala
package com.aiplatform.model

import upickle.default._

/**
 * Информация о доступной AI модели.
 * @param name Короткое имя модели (например, "gemini-1.5-flash-latest")
 * @param displayName Отображаемое имя
 * @param description Описание модели
 * @param state Текущее состояние/статус модели (опционально)
 * @param supportedGenerationMethods Список методов, поддерживаемых моделью (например, "generateContent") // ДОБАВЛЕНО
 */
case class ModelInfo(
                      name: String,
                      displayName: String,
                      description: Option[String] = None,
                      state: Option[String] = None,
                      supportedGenerationMethods: List[String] = List.empty
                    )

object ModelInfo {
  implicit val rw: ReadWriter[ModelInfo] = macroRW
}