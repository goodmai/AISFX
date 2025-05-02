// src/main/scala/com/aiplatform/util/JsonUtil.scala
package com.aiplatform.util

import upickle.default._
import java.time.Instant
import org.slf4j.LoggerFactory
import scala.util.control.NonFatal
import scala.util.Try

/**
 * Утилиты для JSON сериализации/десериализации с использованием uPickle.
 */
object JsonUtil {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Неявный ReadWriter для java.time.Instant.
   * ИСПРАВЛЕНО: Сериализует в/из Long (секунды эпохи) для совместимости.
   */
  implicit val instantRW: ReadWriter[Instant] =
    readwriter[Long].bimap[Instant](
      // Instant -> Long (секунды эпохи)
      instant => instant.getEpochSecond,
      // Long (секунды эпохи) -> Instant
      epochSecond => {
        Try(Instant.ofEpochSecond(epochSecond)).recover {
          // Обработка ошибок парсинга числа (маловероятно, но возможно)
          case NonFatal(e) =>
            logger.error(s"Failed to create Instant from epoch seconds: $epochSecond. Falling back to Instant.now().", e)
            Instant.now() // Возвращаем текущее время как fallback
        }.get // .get безопасен из-за recover
      }
    )

  /**
   * Неявный ReadWriter для Option[T].
   */
  implicit def optionRW[T: ReadWriter]: ReadWriter[Option[T]] =
    readwriter[ujson.Value].bimap[Option[T]](
      opt => opt match {
        case Some(value) => writeJs(value)
        case None        => ujson.Null
      },
      json => json match {
        case ujson.Null => None
        case _          => Try(read[T](json)).toOption
      }
    )

  /**
   * Сериализация объекта в JSON строку (с форматированием).
   */
  def serialize[T: Writer](value: T): String = {
    try {
      write(value, indent = 2)
    } catch {
      case NonFatal(e) =>
        logger.error(s"Serialization failed for value of type ${value.getClass.getSimpleName}.", e)
        throw e
    }
  }

  /**
   * Десериализация JSON строки в объект.
   */
  def deserialize[T: Reader](json: String): T = {
    try {
      read[T](json)
    } catch {
      case e @ (_: upickle.core.Abort | _: ujson.ParseException) =>
        logger.error(s"Failed to deserialize JSON string to target type.", e)
        throw new Exception(s"Ошибка разбора JSON: ${e.getMessage}", e)
      case NonFatal(e) =>
        logger.error(s"Unexpected error during JSON deserialization.", e)
        throw e
    }
  }
}
