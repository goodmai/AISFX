// src/main/scala/com/aiplatform/repository/StateRepository.scala
package com.aiplatform.repository

import com.aiplatform.model.AppState
import com.aiplatform.util.JsonUtil // Используем наш обновленный JsonUtil
import java.nio.file.{Files, Path, Paths, StandardOpenOption, NoSuchFileException}
import org.slf4j.LoggerFactory
import scala.util.{Try, Success, Failure}
import scala.util.control.NonFatal

object StateRepository {
  private val logger = LoggerFactory.getLogger(getClass)
  private val STATE_FILE_NAME: String = "app_state.json"
  private val STATE_FILE_PATH: Path = Paths.get(STATE_FILE_NAME)

  /**
   * Сохраняет состояние приложения в JSON файл.
   * Использует JsonUtil для сериализации.
   *
   * @param state Текущее состояние приложения.
   * @return Success(()) если сохранение успешно, Failure(exception) в случае ошибки.
   */
  def saveState(state: AppState): Try[Unit] = {
    Try {
      val json = JsonUtil.serialize(state)
      Files.writeString(STATE_FILE_PATH, json, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
      logger.info("Application state saved successfully to {}", STATE_FILE_PATH.toAbsolutePath)
      () // Возвращаем Unit в Success
    }.recoverWith {
      case NonFatal(e) =>
        logger.error(s"Failed to save application state to ${STATE_FILE_PATH.toAbsolutePath}", e)
        Failure(new Exception(s"Ошибка сохранения состояния: ${e.getMessage}", e)) // Оборачиваем ошибку
    }
  }

  /**
   * Загружает состояние приложения из JSON файла.
   * Возвращает начальное состояние AppState.initialState только в случае
   * отсутствия файла или критической ошибки чтения/десериализации.
   *
   * @return Загруженное или начальное состояние AppState.
   */
  def loadState(): AppState = {
    val loadAttempt: Try[AppState] = Try {
      // 1. Пробуем прочитать файл
      val jsonString = Files.readString(STATE_FILE_PATH)
      logger.trace("Read state file content (length: {}).", jsonString.length)

      // 2. Проверяем, не пустой ли файл
      if (jsonString.trim.isEmpty) {
        logger.warn(s"State file ${STATE_FILE_PATH.toAbsolutePath} is empty. Using initial state.")
        throw new Exception("State file is empty")
      }

      // 3. Пробуем десериализовать JSON
      val loadedState = JsonUtil.deserialize[AppState](jsonString)
      logger.info("Application state loaded successfully from {}", STATE_FILE_PATH.toAbsolutePath)
      loadedState // Возвращаем успешно загруженное состояние
    }

    loadAttempt.recoverWith {
      case _: NoSuchFileException =>
        logger.warn(s"State file ${STATE_FILE_PATH.toAbsolutePath} not found. Using initial state.")
        Success(AppState.initialState) // Возвращаем Success с начальным состоянием
      case e: Exception if e.getMessage == "State file is empty" =>
        // Мы сами бросили это исключение выше для пустого файла
        Success(AppState.initialState) // Возвращаем Success с начальным состоянием
      case e: Throwable => // Ловим все остальные ошибки (ошибка чтения, ошибка парсинга JSON и т.д.)
        logger.error(s"Failed to load or parse state from ${STATE_FILE_PATH.toAbsolutePath}. Using initial state.", e)
        Success(AppState.initialState) // При любой другой ошибке - возвращаем Success с начальным состоянием
    }.get // Теперь .get вызывается на Success[AppState], что безопасно и корректно
  }
}
