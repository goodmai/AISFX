package com.aiplatform.model

import java.time.Instant
import upickle.default._

case class AppState(
                     currentSection: Section,
                     dialogHistory: List[Dialog],
                     aiModel: String
                   )

object AppState {
  // Явные сериализаторы для всех компонентов
  implicit val rw: ReadWriter[AppState] = macroRW

  // Сериализатор для Instant (должен быть в scope)
  implicit val instantRW: ReadWriter[Instant] =
    readwriter[Long].bimap[Instant](
      _.getEpochSecond,
      Instant.ofEpochSecond
    )

  // Сериализатор для Section (должен быть определен в объекте Section)
  implicit val sectionRW: ReadWriter[Section] = Section.given_ReadWriter_Section

  val initialState: AppState = AppState(
    currentSection = Section.Text,
    dialogHistory = List.empty,
    aiModel = "gemini-1.5-flash" // Обновлено на актуальную модель Gemini
  )
}