// src/main/scala/com/aiplatform/repository/StateRepository.scala
package com.aiplatform.repository

import com.aiplatform.model.AppState
import com.aiplatform.util.JsonUtil
import java.nio.file.{Files, Path, Paths, StandardOpenOption, NoSuchFileException, StandardCopyOption}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.slf4j.LoggerFactory
import scala.util.{Try, Success, Failure}
import scala.util.control.NonFatal

object StateRepository {
  private val logger = LoggerFactory.getLogger(getClass)
  private val STATE_FILE_NAME: String = "app_state.json"
  private val STATE_FILE_PATH: Path = Paths.get(STATE_FILE_NAME)

  /**
   * Saves the application state to a JSON file.
   * Uses JsonUtil for serialization.
   *
   * @param state Current application state.
   * @return Success(()) if saving is successful, Failure(exception) in case of an error.
   */
  def saveState(state: AppState): Try[Unit] = {
    Try {
      val json = JsonUtil.serialize(state)
      Files.writeString(STATE_FILE_PATH, json, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
      logger.info("Application state saved successfully to {}", STATE_FILE_PATH.toAbsolutePath)
      () // Return Unit in Success
    }.recoverWith {
      case NonFatal(e) =>
        logger.error(s"Failed to save application state to ${STATE_FILE_PATH.toAbsolutePath}", e)
        Failure(new Exception(s"Error saving state: ${e.getMessage}", e)) // Wrap the error
    }
  }

  /**
   * Loads the application state from a JSON file.
   * Returns AppState.initialState only if the file is missing or a critical read/deserialization error occurs.
   *
   * @return Loaded or initial AppState.
   */
  def loadState(): AppState = {
    val loadAttempt: Try[AppState] = Try {
      // 1. Try to read the file
      val jsonString = Files.readString(STATE_FILE_PATH)
      logger.trace("Read state file content (length: {}).", jsonString.length)

      // 2. Check if the file is empty
      if (jsonString.trim.isEmpty) {
        logger.warn(s"State file ${STATE_FILE_PATH.toAbsolutePath} is empty. Using initial state.")
        throw new Exception("State file is empty") // This specific exception message is caught below.
      }

      // 3. Try to deserialize JSON
      val loadedState = JsonUtil.deserialize[AppState](jsonString)
      logger.info("Application state loaded successfully from {}", STATE_FILE_PATH.toAbsolutePath)
      loadedState // Return successfully loaded state
    }

    loadAttempt.recoverWith {
      case _: NoSuchFileException =>
        logger.warn(s"State file ${STATE_FILE_PATH.toAbsolutePath} not found. Using initial state.")
        Success(AppState.initialState) // Return Success with initial state
      case e: Exception if e.getMessage == "State file is empty" =>
        // We threw this exception above for an empty file.
        Success(AppState.initialState) // Return Success with initial state
      case NonFatal(e) => // Catch all other non-fatal errors (read error, JSON parsing error, etc.)
        logger.error(s"Failed to load or parse state from ${STATE_FILE_PATH.toAbsolutePath}. Attempting to backup and use initial state.", e)
        // Attempt to backup the corrupted file
        Try {
          val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
          val backupPath = Paths.get(s"${STATE_FILE_PATH.toString}.corrupted_$timestamp")
          Files.copy(STATE_FILE_PATH, backupPath, StandardCopyOption.REPLACE_EXISTING)
          logger.info(s"Backed up corrupted state file to ${backupPath.toAbsolutePath}")
        }.recover {
          case NonFatal(backupEx) =>
            logger.error(s"Failed to backup corrupted state file ${STATE_FILE_PATH.toAbsolutePath}", backupEx)
        }
        Success(AppState.initialState) // For any other error - return Success with initial state
    }.get // .get is now called on Success[AppState], which is safe and correct
  }
}
