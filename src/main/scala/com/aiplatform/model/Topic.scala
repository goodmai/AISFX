// src/main/scala/com/aiplatform/model/Topic.scala
package com.aiplatform.model

import upickle.default._
import java.util.UUID
import java.time.Instant
import com.aiplatform.util.JsonUtil.instantRW

case class Topic(
                  id: String = UUID.randomUUID().toString,
                  var title: String,
                  dialogs: List[Dialog] = List.empty, // Список диалогов
                  createdAt: Instant = Instant.now(),
                  var lastUpdatedAt: Instant = Instant.now(),
                  category: String = "Default" // Категория топика
                )

object Topic {
  implicit val dialogRW: ReadWriter[Dialog] = Dialog.rw
  implicit val rw: ReadWriter[Topic] = macroRW

  /**
   * Создает новый топик с заданной категорией.
   */
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