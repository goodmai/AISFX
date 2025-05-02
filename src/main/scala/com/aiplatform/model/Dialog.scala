// src/main/scala/com/aiplatform/model/Dialog.scala
package com.aiplatform.model

import java.time.Instant
import upickle.default._
import com.aiplatform.util.JsonUtil.instantRW

case class Dialog(
                   title: String,
                   request: String,
                   response: String,
                   timestamp: Instant = Instant.now(),
                   model: String
                 )

object Dialog {
  implicit val rw: ReadWriter[Dialog] = macroRW
}