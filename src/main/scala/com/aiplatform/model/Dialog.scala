package com.aiplatform.model

import java.time.Instant
import upickle.default._

case class Dialog(
                   request: String,
                   response: String,
                   timestamp: Instant = Instant.now(),
                   model: String
                 )

object Dialog {
  implicit val instantRW: ReadWriter[Instant] = AppState.instantRW
  implicit val rw: ReadWriter[Dialog] = macroRW

  def apply(request: String, response: String, model: String): Dialog =
    new Dialog(request, response, Instant.now(), model)
}