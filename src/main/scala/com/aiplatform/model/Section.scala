package com.aiplatform.model

import upickle.default._

/**
 * Перечисление секций приложения
 */
enum Section derives ReadWriter:
  case Audio, Video, Text, Screen, History, AIModel

object Section {
  // Кастомный ReadWriter для преобразования enum в строку
  given ReadWriter[Section] = readwriter[String].bimap[Section](
    _.toString,
    name => Section.valueOf(name)
  )
}