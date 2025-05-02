// src/main/scala/com/aiplatform/service/ModelFetchingService.scala
package com.aiplatform.service

import com.aiplatform.model.ModelInfo
import org.slf4j.LoggerFactory
import scala.concurrent.{Future, ExecutionContext}
import org.apache.pekko.actor.typed.ActorSystem // Используем ActorSystem[?]
import sttp.client3._
import sttp.client3.circe._
import sttp.client3.pekkohttp.PekkoHttpBackend
import io.circe.{Decoder, HCursor, DecodingFailure}
import io.circe.generic.semiauto.deriveDecoder
import io.circe.parser.parse // Импорт для parse
import sttp.model.{Header, Uri, StatusCode, HeaderNames} // Добавлен HeaderNames
import scala.util.{Try, Success, Failure}
import scala.util.control.NonFatal

// --- Структуры данных для ответа API ---
private case class ModelsListResponse(models: List[ModelInfo])

// --- Декодеры Circe ---
private object ModelInfoDecoder {

  // Декодер для ModelInfo
  implicit val decodeModelInfo: Decoder[ModelInfo] = (c: HCursor) => {
    for {
      fullName <- c.downField("name").as[String]
      displayName <- c.downField("displayName").as[String]
      description <- c.downField("description").as[Option[String]]
      methods <- c.downField("supportedGenerationMethods").as[List[String]]
    } yield {
      val shortName = fullName.split('/').lastOption.getOrElse(fullName)
      ModelInfo(
        name = shortName,
        displayName = displayName,
        description = description,
        state = None,
        supportedGenerationMethods = methods
      )
    }
  }

  // Декодер для корневого объекта ответа API
  implicit val decodeModelsListResponse: Decoder[ModelsListResponse] = deriveDecoder[ModelsListResponse]
}


/**
 * Сервис для получения списка доступных AI моделей от Google Generative Language API.
 */
class ModelFetchingService()(implicit system: ActorSystem[?], ec: ExecutionContext) {

  private val logger = LoggerFactory.getLogger(getClass)
  private val modelsApiUrl = "https://generativelanguage.googleapis.com/v1beta/models"
  private implicit val backend: SttpBackend[Future, Any] = PekkoHttpBackend.usingActorSystem(system.classicSystem)

  // Импортируем декодеры из компаньона
  import ModelInfoDecoder._

  /**
   * Получает список доступных моделей от Google Generative Language API,
   * фильтруя те, которые поддерживают метод "generateContent".
   */
  def fetchAvailableModels(apiKey: String): Future[List[ModelInfo]] = {
    val trimmedApiKey = apiKey.trim
    if (trimmedApiKey.isEmpty) {
      logger.warn("Cannot fetch models: API Key is empty.")
      return Future.successful(List.empty)
    }

    logger.info("Fetching available AI models supporting 'generateContent' from Google API...")
    val targetUri = Uri.unsafeParse(modelsApiUrl)

    // Создаем HTTP GET запрос
    val request = basicRequest
      .get(targetUri)
      .header("x-goog-api-key", trimmedApiKey) // Используем стандартное имя заголовка
      .response(asJson[ModelsListResponse]) // Ожидаем JSON ответ

    // Отправляем запрос асинхронно
    request.send(backend).transform { // Используем transform для обработки Success и Failure
      case Success(response) =>
        // Обрабатываем успешный ответ (код 2xx)
        response.body match {
          case Right(apiResponse) =>
            // Фильтруем модели ПОСЛЕ успешного парсинга JSON
            val supportedModels = apiResponse.models.filter { model =>
              val supported = model.supportedGenerationMethods.contains("generateContent")
              if (!supported) {
                logger.trace(s"Model '${model.name}' ('${model.displayName}') excluded (does not support 'generateContent'). Methods: ${model.supportedGenerationMethods.mkString(", ")}")
              }
              supported
            }
            logger.info(s"Successfully fetched ${apiResponse.models.size} models, ${supportedModels.size} support 'generateContent'.")
            Success(supportedModels.sortBy(_.displayName)) // Возвращаем отфильтрованный и отсортированный список

          case Left(error: ResponseException[String, io.circe.Error]) =>
            // Ошибка парсинга JSON ответа (даже при коде 2xx)
            handleFetchError(error) // Логируем ошибку
            Success(List.empty) // Возвращаем пустой список при ошибке парсинга
        }
      case Failure(exception) =>
        // Обрабатываем ЛЮБОЕ исключение (сеть, таймаут, HTTP ошибка, ошибка парсинга тела ошибки)
        handleGenericFetchError(exception) // Используем общий обработчик ошибок
        Success(List.empty) // Возвращаем пустой список при любой ошибке
    }
  }

  /**
   * Обрабатывает и логирует ошибки ResponseException (HTTP ошибки или ошибки десериализации).
   * @param error Исключение ResponseException от sttp.
   */
  private def handleFetchError(error: ResponseException[String, io.circe.Error]): Unit = {
    val statusCodeOpt: Option[StatusCode] = error match {
      case HttpError(_, code) => Some(code)
      case DeserializationException(_, _) => None // Статус код не так важен при ошибке десериализации тела
    }
    val statusCodeStr = statusCodeOpt.map(c => s" (Status: $c)").getOrElse("")

    error match {
      case HttpError(body, code) =>
        val errorMsgDetail = Try(parse(body).flatMap(_.hcursor.downField("error").downField("message").as[String]))
          .map(msg => s" Message: $msg")
          .getOrElse("")
        logger.error(s"HTTP Error fetching models: Status $code.${errorMsgDetail} Body preview: ${body.take(500)}")
      case DeserializationException(body, e) =>
        logger.error(s"Failed to deserialize models response${statusCodeStr}: ${e.getMessage}. Body preview: ${body.take(500)}", e)
    }
  }

  /**
   * Обрабатывает и логирует любые ошибки, возникшие при выполнении запроса.
   * @param exception Исключение.
   */
  private def handleGenericFetchError(exception: Throwable): Unit = {
    exception match {
      case respExc: ResponseException[String, io.circe.Error] =>
        // Если это ResponseException, используем специфичный обработчик
        handleFetchError(respExc)
      case NonFatal(other) =>
        // Другие ошибки (сеть, таймауты и т.д.)
        logger.error("Request to fetch models failed with unexpected error.", other)
      // Можно добавить обработку фатальных ошибок отдельно, если нужно
    }
  }
}
