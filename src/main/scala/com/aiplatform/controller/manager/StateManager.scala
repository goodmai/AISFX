// src/main/scala/com/aiplatform/controller/manager/StateManager.scala
package com.aiplatform.controller.manager

import com.aiplatform.model.AppState
import com.aiplatform.repository.StateRepository
import org.slf4j.LoggerFactory
import scala.util.{Try, Success, Failure}
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec

/**
 * Управляет состоянием приложения AppState.
 * Обеспечивает потокобезопасный доступ и обновление состояния,
 * а также взаимодействие с StateRepository для сохранения/загрузки.
 */
class StateManager {
  private val logger = LoggerFactory.getLogger(getClass)
  private val currentState: AtomicReference[AppState] = new AtomicReference[AppState](loadInitialState())

  /** Загружает начальное состояние при инициализации. */
  private def loadInitialState(): AppState = {
    logger.info("Loading initial application state...")
    val loadedState = StateRepository.loadState()
    logger.info("Initial application state loaded.")
    loadedState
  }

  /** Возвращает текущее неизменяемое состояние. */
  def getState: AppState = currentState.get()

  /**
   * Атомарно обновляет состояние приложения с использованием функции и сохраняет результат.
   * Использует compare-and-set цикл для обработки конкурентных обновлений.
   *
   * @param updateFunc Функция, принимающая текущее состояние и возвращающая новое.
   * Эта функция может быть вызвана несколько раз в случае конкуренции.
   * @return Success(()) если обновление и сохранение прошли успешно,
   * Failure(exception) если возникла ошибка во время updateFunc или сохранения.
   */
  @tailrec // Гарантируем хвостовую рекурсию
  final def updateState(updateFunc: AppState => AppState): Try[Unit] = {
    val current = currentState.get()
    Try(updateFunc(current)) match {
      case Failure(updateError) =>
        logger.error("Error occurred during state update function execution.", updateError)
        Failure(updateError) // Возвращаем ошибку из функции

      case Success(updated) =>
        if (current == updated) {
          logger.trace("State update function did not change the state. No save needed.")
          Success(())
        } else {
          // Пытаемся атомарно обновить состояние в памяти
          if (currentState.compareAndSet(current, updated)) {
            logger.debug("Application state updated in memory. Attempting to save...")
            val saveResult = StateRepository.saveState(updated)
            saveResult match {
              case Success(_) =>
                logger.debug("State saved successfully after update.")
                Success(()) // Обновление и сохранение успешны

              case Failure(saveException) =>
                logger.error("Failed to save state after update. Attempting to revert in-memory state.", saveException)

                if (currentState.compareAndSet(updated, current)) {
                  logger.warn("In-memory state successfully reverted after save failure.")
                } else {
                  logger.error("CRITICAL: Failed to revert in-memory state after save failure! State might be inconsistent.")
                }
                Failure(saveException) // Возвращаем ошибку сохранения
            }
          } else {

            logger.warn("Concurrent state update detected. Retrying state update...")
            updateState(updateFunc)
          }
        }
    }
  }

  /**
   * Принудительно сохраняет текущее состояние из памяти в файл.
   * Используется, например, при завершении работы приложения.
   *
   * @return Success(()) если сохранение успешно, Failure(exception) в случае ошибки.
   */
  def forceSaveState(): Try[Unit] = {
    logger.info("Force saving current application state...")
    StateRepository.saveState(currentState.get())
  }
}
