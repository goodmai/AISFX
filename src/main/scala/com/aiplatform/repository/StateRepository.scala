package com.aiplatform.repository

import com.aiplatform.model.AppState
import com.aiplatform.util.JsonUtil
import java.nio.file.{Files, Paths, StandardOpenOption} // Добавлены опции записи
import org.slf4j.LoggerFactory
import scala.util.{Try, Success, Failure} // Добавлен Try для обработки ошибок

object StateRepository {
  private val logger = LoggerFactory.getLogger(getClass)
  private val STATE_FILE = Paths.get("app_state.json") // Используем Path

  def saveState(state: AppState): Unit = {
    Try {
      val json = JsonUtil.serialize(state) // Может выбросить исключение при сериализации
      // Перезаписываем файл полностью
      Files.writeString(STATE_FILE, json, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
      logger.info("Application state saved successfully to {}", STATE_FILE)
    } match {
      case Success(_) => // Все хорошо
      case Failure(e) => logger.error(s"Failed to save application state to $STATE_FILE", e) // Логируем ошибку
    }
  }

  def loadState(): AppState = {
    if (Files.exists(STATE_FILE) && Files.isReadable(STATE_FILE)) {
      Try(Files.readString(STATE_FILE)) match { // Читаем файл
        case Success(jsonString) if jsonString.trim.nonEmpty =>
          Try(JsonUtil.deserialize[AppState](jsonString)) match { // Десериализуем
            case Success(state) =>
              logger.info("Application state loaded successfully from {}", STATE_FILE)
              state
            case Failure(e) =>
              logger.error(s"Failed to deserialize state from $STATE_FILE. Using initial state.", e)
              AppState.initialState // Возвращаем дефолтное состояние при ошибке десериализации
          }
        case Success(_) => // Пустой файл
          logger.warn(s"State file $STATE_FILE is empty. Using initial state.")
          AppState.initialState
        case Failure(e) => // Ошибка чтения файла
          logger.error(s"Failed to read state file $STATE_FILE. Using initial state.", e)
          AppState.initialState
      }
    } else {
      logger.warn(s"State file $STATE_FILE not found or not readable. Using initial state.")
      AppState.initialState // Возвращаем дефолтное состояние, если файла нет
    }
  }
}