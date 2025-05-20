// src/main/scala/com/aiplatform/service/CredentialsService.scala
package com.aiplatform.service

import java.util.prefs.{Preferences, BackingStoreException} // Явный импорт BackingStoreException
import org.slf4j.LoggerFactory
import scala.util.{Try, Success, Failure}
import scala.util.control.NonFatal
import com.aiplatform.controller.MainController

/**
 * Сервис для безопасного (насколько позволяет Preferences API) хранения
 * и извлечения API ключа. Использует Java Preferences API.
 */
object CredentialsService {

  private val logger = LoggerFactory.getLogger(getClass)
  // ИСПРАВЛЕНО: Получаем узел Preferences, используя MainController для определения пакета
  private val prefs: Preferences = Try(Preferences.userNodeForPackage(classOf[MainController]))
    .recover {
      case NonFatal(e) =>
        logger.warn(s"Failed to get Preferences node for MainController package, using root node as fallback: ${e.getMessage}")
        Preferences.userRoot() // Fallback на корневой узел
    }.get // .get безопасен из-за recover

  private val API_KEY_PREF_KEY = "aiplatform.api.key" // Ключ для хранения API Key

  /**
   * Сохраняет API ключ в хранилище Preferences и проверяет сохранение.
   * Пустая строка или строка из пробелов удаляет ключ.
   *
   * @param apiKey Ключ API для сохранения.
   * @return Success(()) если сохранение и проверка прошли успешно, иначе Failure(exception).
   */
  def saveApiKey(apiKey: String): Try[Unit] = {
    val trimmedKey = apiKey.trim
    Try { // Шаг 1: Запись
      if (trimmedKey.isEmpty) {
        logger.info("Attempting to remove API key from Preferences.")
        prefs.remove(API_KEY_PREF_KEY)
      } else {
        logger.debug("Putting API key into Preferences node: {}", prefs.absolutePath())
        prefs.put(API_KEY_PREF_KEY, trimmedKey)
      }
    }.flatMap { _ => // Шаг 2: Flush
      Try {
        logger.debug("Attempting to flush Preferences...")
        prefs.flush()
        logger.debug("Preferences flush completed.")
      }
    }.flatMap { _ => // Шаг 3: Верификация
      logger.debug("Verifying API key persistence...")
      verifyApiKeyPersistence(trimmedKey)
    }.map { _ => // Шаг 4: Логирование успеха
      val logMessage = if (trimmedKey.isEmpty) "removed" else "saved and verified"
      logger.info("API Key successfully {} in Preferences storage (Node: {}).", logMessage, prefs.name())
      ()
    }.recoverWith { // Обработка ошибок
      case e: BackingStoreException =>
        logger.error(s"Failed to flush Preferences! Path: ${prefs.absolutePath()}", e)
        Failure(new Exception("Не удалось записать ключ API в системное хранилище.", e))
      case e: SecurityException =>
        logger.error(s"Security exception accessing Preferences! Path: ${prefs.absolutePath()}", e)
        Failure(new Exception("Ошибка безопасности при доступе к хранилищу настроек.", e))
      case verificationEx: Exception if verificationEx.getMessage != null && verificationEx.getMessage.startsWith("Ошибка проверки:") =>
        logger.error(s"API key persistence verification failed! Path: ${prefs.absolutePath()}", verificationEx)
        Failure(verificationEx)
      case NonFatal(e) =>
        logger.error(s"Unexpected error saving/verifying API Key. Path: ${prefs.absolutePath()}", e)
        Failure(new Exception(s"Непредвиденная ошибка при сохранении/проверке ключа API: ${e.getMessage}", e))
    }
  }

  /**
   * Вспомогательный метод для проверки, сохранился ли ключ (или был ли удален).
   */
  private def verifyApiKeyPersistence(expectedKey: String): Try[Unit] = Try {
    val loadedKeyOpt = loadApiKeyInternal(logSuccess = false)
    (expectedKey.isEmpty, loadedKeyOpt) match {
      case (true, None) => logger.debug("Verification successful: Key correctly removed."); ()
      case (true, Some(_)) => logger.error("Verification failed: Key was NOT removed."); throw new Exception("Ошибка проверки: Ключ не был удален из хранилища.")
      case (false, Some(loadedKey)) if loadedKey == expectedKey => logger.debug("Verification successful: Key correctly saved."); ()
      case (false, Some(_)) => logger.error("Verification failed: Saved key does not match expected."); throw new Exception("Ошибка проверки: Сохраненный ключ не совпадает с ожидаемым.")
      case (false, None) => logger.error("Verification failed: Key NOT found after save attempt."); throw new Exception("Ошибка проверки: Ключ не найден в хранилище после попытки сохранения.")
    }
  }

  /**
   * Загружает API ключ из хранилища Preferences (внутренний метод).
   * ИСПРАВЛЕНО: Добавлена обработка Failure для полноты match.
   */
  private def loadApiKeyInternal(logSuccess: Boolean = true): Option[String] = {
    Try(Option(prefs.get(API_KEY_PREF_KEY, null))) match {
      case Success(Some(key)) if key.nonEmpty =>
        if (logSuccess) logger.info("API Key loaded successfully from Preferences (Node: {}).", prefs.name())
        Some(key)
      case Success(_) => // Обрабатывает случай Success(None) или Success(Some(""))
        if (logSuccess) logger.info("No API Key found in Preferences (Node: {}).", prefs.name())
        None
      case Failure(e) => // Catch all failures
        e match {
          case se: SecurityException =>
            logger.error(s"Security exception loading API Key (Node: ${prefs.name()}). Returning None.", se)
            None
          case NonFatal(nf) =>
            logger.error(s"Failed to load API Key (Node: ${prefs.name()}). Returning None.", nf)
            None
          case fatal => // Should ideally not happen with Preferences API or be caught
            logger.error(s"Fatal error loading API Key (Node: ${prefs.name()}). Rethrowing.", fatal)
            throw fatal // Rethrow fatal errors
        }
    }
  }

  /**
   * Публичный метод для загрузки API ключа.
   */
  def loadApiKey(): Option[String] = {
    loadApiKeyInternal()
  }

  /**
   * Явное удаление API ключа из хранилища Preferences.
   */
  def deleteApiKey(): Try[Unit] = {
    logger.warn("Explicitly deleting API key from Preferences.")
    saveApiKey("") // Сохранение пустой строки удаляет ключ
  }
}