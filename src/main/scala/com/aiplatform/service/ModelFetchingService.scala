// main/scala/com/aiplatform/service/ModelFetchingService.scala
package com.aiplatform.service

import com.aiplatform.model.ModelInfo
import org.slf4j.LoggerFactory
import scala.concurrent.{Future, ExecutionContext}
import org.apache.pekko.actor.typed.ActorSystem
import sttp.client3._
import sttp.client3.circe._
import sttp.client3.pekkohttp.PekkoHttpBackend
import io.circe.generic.auto._
import io.circe.{Decoder, HCursor}
import sttp.model.{Header, Uri, StatusCode}
import scala.util.Try

private case class ModelApiResponse(models: List[ModelInfo])


private object ModelInfoDecoder {
  implicit val decodeModelInfo: Decoder[ModelInfo] = (c: HCursor) => {
    for {
      fullName <- c.downField("name").as[String]
      displayName <- c.downField("displayName").as[String]
      description <- c.downField("description").as[Option[String]]
      // Читаем список поддерживаемых методов
      methods <- c.downField("supportedGenerationMethods").as[List[String]]
    } yield {
      val shortName = fullName.split('/').lastOption.getOrElse(fullName)
      ModelInfo(
        name = shortName,
        displayName = displayName,
        description = description,
        state = None, // Состояние пока не парсим
        supportedGenerationMethods = methods // Сохраняем методы
      )
    }
  }
}


// Сервис для получения списка моделей
class ModelFetchingService()(implicit system: ActorSystem[?], ec: ExecutionContext) {

  private val logger = LoggerFactory.getLogger(getClass)
  private val modelsApiUrl = "https://generativelanguage.googleapis.com/v1beta/models"
  private implicit val backend: SttpBackend[Future, Any] = PekkoHttpBackend.usingActorSystem(system.classicSystem)

  // Импортируем декодер
  import ModelInfoDecoder._

  /**
   * Получает список доступных моделей от Google Generative Language API,
   * поддерживающих метод "generateContent".
   */
  def fetchAvailableModels(apiKey: String): Future[List[ModelInfo]] = {
    if (apiKey.trim.isEmpty) {
      logger.warn("Cannot fetch models: API Key is empty.")
      return Future.successful(List.empty)
    }

    logger.info("Fetching available AI models supporting 'generateContent' from Google API...")
    val targetUri = Uri.unsafeParse(modelsApiUrl)
    val request = basicRequest
      .get(targetUri)
      .header(Header("x-goog-api-key", apiKey))
      .response(asJson[ModelApiResponse])

    request.send(backend).map { response =>
      response.body match {
        case Right(apiResponse) =>
          // Фильтруем модели ПОСЛЕ успешного парсинга
          val supportedModels = apiResponse.models.filter { model =>
            val supported = model.supportedGenerationMethods.contains("generateContent")
            if (!supported) {
              logger.trace(s"Model '${model.name}' excluded (does not support 'generateContent'). Methods: ${model.supportedGenerationMethods.mkString(", ")}")
            }
            supported
          }
          logger.info(s"Successfully fetched ${apiResponse.models.size} models, ${supportedModels.size} support 'generateContent'.")
          supportedModels // Возвращаем отфильтрованный список

        case Left(error) =>
          handleFetchError(error, response.code)
          List.empty
      }
    }.recover {
      case e: Exception =>
        logger.error("Request to fetch models failed.", e)
        List.empty
    }
  }

  private def handleFetchError(error: ResponseException[String, io.circe.Error], statusCode: StatusCode): Unit = {
    error match {
      case HttpError(body, code) =>
        val errorMsg = Try(io.circe.parser.parse(body).flatMap(_.hcursor.downField("error").downField("message").as[String])).map(m => s" ($m)").getOrElse("")
        logger.error(s"HTTP Error fetching models: $code - ${body.take(500)}$errorMsg")
      case DeserializationException(body, e) =>
        logger.error(s"Failed to deserialize models response: ${e.getMessage}. Body: ${body.take(500)}")
    }
  }
}