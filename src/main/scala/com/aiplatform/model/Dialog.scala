// updated: scala/com/aiplatform/model/Dialog.scala
package com.aiplatform.model

import java.time.Instant
import upickle.default._

case class Dialog(
                   title: String,
                   // ------------------------------------
                   request: String,
                   response: String,
                   timestamp: Instant = Instant.now(),
                   model: String
                 )

object Dialog {
  implicit val instantRW: ReadWriter[Instant] = AppState.instantRW // Используем RW из AppState
  // uPickle автоматически сгенерирует RW для Dialog с новым полем
  implicit val rw: ReadWriter[Dialog] = macroRW


  /*
  def apply(title: String, request: String, response: String, model: String): Dialog =
    new Dialog(title, request, response, Instant.now(), model)
   */
  // ---------------------------------------------------------
}