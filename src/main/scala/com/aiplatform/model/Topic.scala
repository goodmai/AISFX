package com.aiplatform.model

import upickle.default._
import java.util.UUID
import java.time.Instant

case class Topic(
                  id: String = UUID.randomUUID().toString,
                  var title: String,
                  dialogs: List[Dialog] = List.empty,
                  createdAt: Instant = Instant.now(),
                  var lastUpdatedAt: Instant = Instant.now(),
                  category: String = "Default"
                )

object Topic {
  implicit val instantRW: ReadWriter[Instant] = AppState.instantRW // Используем RW из AppState
  implicit val dialogListRW: ReadWriter[List[Dialog]] = implicitly[ReadWriter[List[Dialog]]] // RW для Dialog должен быть определен
  implicit val rw: ReadWriter[Topic] = macroRW

  def createNew(category: String, initialTitle: String = "Новый топик"): Topic = {
    val now = Instant.now()
    Topic(
      title = initialTitle,
      category = category,
      createdAt = now,
      lastUpdatedAt = now
    )
  }
}