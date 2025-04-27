package com.aiplatform.util

import upickle.default._

/**
 * Утилиты для JSON сериализации/десериализации
 */
object JsonUtil {
  /**
   * Сериализация объекта в JSON строку
   * @param value Объект для сериализации
   * @tparam T Тип объекта
   * @return JSON строка
   */
  def serialize[T: Writer](value: T): String = write(value)

  /**
   * Десериализация JSON строки в объект
   * @param json JSON строка
   * @tparam T Тип целевого объекта
   * @return Десериализованный объект
   */
  def deserialize[T: Reader](json: String): T = read[T](json)

  // Для обработки ошибок можно добавить:
  // def tryDeserialize[T: Reader](json: String): Try[T] = ...
}