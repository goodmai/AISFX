// src/main/scala/com/aiplatform/service/AIService.scala
package com.aiplatform.service

import com.aiplatform.model.{Dialog, FileTreeContext, FileSelectionState} // Added FileTreeContext, FileSelectionState
import io.circe.parser.parse
import io.circe.{Encoder, Json, Decoder}
import io.circe.syntax._
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import org.apache.pekko.actor.ActorSystem
import org.slf4j.LoggerFactory
import sttp.client3._
import sttp.client3.circe._
import sttp.client3.pekkohttp.PekkoHttpBackend
import sttp.model.Uri
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

// --- Модели данных для Gemini API ---
case class Part(
                 text: Option[String] = None,
                 inlineData: Option[InlineData] = None
               )
case class InlineData(
                       mimeType: String,
                       data: String
                     )
case class Content(
                    parts: List[Part],
                    role: Option[String] = None
                  )
case class GenerationConfig(
                             temperature: Option[Double] = None,
                             topP: Option[Double] = None,
                             topK: Option[Int] = None,
                             maxOutputTokens: Option[Int] = None
                           )
case class GenerateContentRequest(
                                   contents: List[Content],
                                   generationConfig: Option[GenerationConfig] = None
                                 )
case class Candidate(
                      content: Content,
                      finishReason: Option[String] = None,
                      index: Option[Int] = None
                    )
case class UsageMetadata(
                          promptTokenCount: Option[Int] = None,
                          candidatesTokenCount: Option[Int] = None,
                          totalTokenCount: Option[Int] = None
                        )
case class ErrorDetails(code: Int, message: String, status: String)
case class GeminiApiError(error: ErrorDetails)
case class GenerateContentResponse(
                                    candidates: Option[List[Candidate]] = None,
                                    usageMetadata: Option[UsageMetadata] = None
                                  )

// --- Кодеры/декодеры Circe ---
object GeminiCodecs {
  implicit val encodeInlineData: Encoder[InlineData] = deriveEncoder[InlineData]
  implicit val encodePart: Encoder[Part] = Encoder.instance { part =>
    val fields = Seq.newBuilder[(String, Json)]
    part.text.foreach(t => fields += "text" -> Json.fromString(t))
    part.inlineData.foreach(d => fields += "inlineData" -> d.asJson)
    io.circe.JsonObject.fromIterable(fields.result()).asJson
  }
  implicit val encodeContent: Encoder[Content] = deriveEncoder[Content]
  implicit val encodeGenerationConfig: Encoder[GenerationConfig] = Encoder.instance { config =>
    val fields = Seq.newBuilder[(String, Json)]
    config.temperature.foreach(t => fields += "temperature" -> Json.fromDoubleOrNull(t))
    config.topP.foreach(p => fields += "topP" -> Json.fromDoubleOrNull(p))
    config.topK.foreach(k => fields += "topK" -> Json.fromInt(k))
    config.maxOutputTokens.foreach(m => fields += "maxOutputTokens" -> Json.fromInt(m))
    io.circe.JsonObject.fromIterable(fields.result()).asJson
  }
  implicit val encodeGenerateContentRequest: Encoder[GenerateContentRequest] = Encoder.instance { req =>
    val genConfigJsonOpt = req.generationConfig.map(_.asJson)
    val fields = Seq.newBuilder[(String, Json)]
    fields += "contents" -> req.contents.asJson
    genConfigJsonOpt.foreach { json =>
      if (!json.asObject.exists(_.isEmpty)) {
        fields += "generationConfig" -> json
      }
    }
    io.circe.JsonObject.fromIterable(fields.result()).asJson
  }
  implicit val decodeInlineData: Decoder[InlineData] = deriveDecoder[InlineData]
  implicit val decodePart: Decoder[Part] = deriveDecoder[Part]
  implicit val decodeContent: Decoder[Content] = deriveDecoder[Content]
  implicit val decodeCandidate: Decoder[Candidate] = deriveDecoder[Candidate]
  implicit val decodeUsageMetadata: Decoder[UsageMetadata] = deriveDecoder[UsageMetadata]
  implicit val decodeErrorDetails: Decoder[ErrorDetails] = deriveDecoder[ErrorDetails]
  implicit val decodeGeminiApiError: Decoder[GeminiApiError] = deriveDecoder[GeminiApiError]
  implicit val decodeGenerateContentResponse: Decoder[GenerateContentResponse] = deriveDecoder[GenerateContentResponse]
}

/**
 * Сервис для взаимодействия с Google Generative AI API (Gemini).
 */
class AIService(implicit classicSystem: ActorSystem) {
  private val logger = LoggerFactory.getLogger(getClass)
  import GeminiCodecs._
  type ApiResponse = Either[ResponseException[String, io.circe.Error], GenerateContentResponse]
  private implicit val backend: SttpBackend[Future, Any] = PekkoHttpBackend.usingActorSystem(classicSystem)
  private val baseUri = "https://generativelanguage.googleapis.com/v1beta/models/"
  private val currentModelRef = new AtomicReference[String]("gemini-1.5-flash-latest")

  def process(
               prompt: String,
               apiKey: String,
               temperature: Option[Double],
               topP: Option[Double],
               topK: Option[Int],
               history: List[Dialog],
               imageData: Option[InlineData] = None,
               structuredFileContextOpt: Option[FileTreeContext] = None // Added new parameter
             )(implicit ec: ExecutionContext): Future[String] = {

    if (apiKey.trim.isEmpty) {
      val errorMsg = "API Key is required but was empty."
      logger.error(errorMsg)
      return Future.failed(new IllegalArgumentException(errorMsg))
    }

    val currentModelName = currentModelRef.get()
    val genConfig = GenerationConfig(
      temperature = temperature, topP = topP, topK = topK, maxOutputTokens = Some(8192)
    )
    val effectiveGenConfig = if (genConfig == GenerationConfig(maxOutputTokens = Some(8192))) None else Some(genConfig)

    logger.info(s"Processing AI request. Model: $currentModelName, History size: ${history.size}, Image data present: ${imageData.isDefined}, Structured context present: ${structuredFileContextOpt.isDefined}")
    logger.trace(s"Generation config: Temp=${temperature.getOrElse("N/A")}, TopP=${topP.getOrElse("N/A")}, TopK=${topK.getOrElse("N/A")}")

    Future.fromTry(buildRequest(prompt, apiKey, effectiveGenConfig, history, currentModelName, imageData, structuredFileContextOpt)) // Pass structuredFileContextOpt
      .flatMap { request =>
        sendRequest(request)
          .flatMap(handleResponse)
      }
      .recoverWith {
        case NonFatal(error) => handleGenericError(error)
      }
  }

  private def buildRequest(
                            prompt: String,
                            apiKey: String,
                            genConfig: Option[GenerationConfig],
                            history: List[Dialog],
                            modelName: String,
                            imageData: Option[InlineData],
                            structuredFileContextOpt: Option[FileTreeContext] // Added new parameter
                          ): Try[Request[ApiResponse, Any]] = Try {
    val historyContents: List[Content] = history.flatMap { dialog =>
      List(
        Content(parts = List(Part(text = Some(dialog.request))), role = Some("user")),
        Content(parts = List(Part(text = Some(dialog.response))), role = Some("model"))
      )
    }.takeRight(20) // Limit history to prevent overly large requests

    // Combine base prompt with structured file context
    val structuredContextString = structuredFileContextOpt.map { sfc =>
      sfc.selectedFiles.map {
        case FileSelectionState.Selected(path, content) =>
          s"\n\n--- File: $path ---\n$content\n--- End File: $path ---"
        case FileSelectionState.SelectionError(path, error) =>
          s"\n\n--- File Error: $path ---\n$error\n--- End File Error ---"
      }.mkString("") // mkString with empty separator to avoid extra newlines between file blocks
    }.getOrElse("")

    val combinedPrompt = prompt + structuredContextString // Append structured context to the existing prompt

    val textPartOpt: Option[Part] = Some(combinedPrompt).filter(_.nonEmpty).map(t => Part(text = Some(t)))
    val imagePartOpt: Option[Part] = imageData.map(data => Part(inlineData = Some(data)))
    val currentUserParts: List[Part] = textPartOpt.toList ++ imagePartOpt.toList

    if (currentUserParts.isEmpty) {
      throw new IllegalArgumentException("Cannot send an empty request (no text and no image).")
    }

    val currentContent = Content(parts = currentUserParts, role = Some("user"))
    val allContents = historyContents :+ currentContent

    val payload = GenerateContentRequest(
      contents = allContents,
      generationConfig = genConfig
    )

    val targetUri = Uri.unsafeParse(s"$baseUri$modelName:generateContent?key=$apiKey")
    logger.trace(s"Target URI: $targetUri")
    val payloadLog = payload.copy(contents = payload.contents.map(c => c.copy(parts = c.parts.map(p => p.copy(inlineData = p.inlineData.map(_ => InlineData("...", "...")))))))
    logger.trace(s"Request Payload (data omitted): ${payloadLog.asJson.spaces2}")

    basicRequest
      .post(targetUri)
      .contentType("application/json")
      .body(payload)
      .response(asJson[GenerateContentResponse])
  }

  private def sendRequest(request: Request[ApiResponse, Any]): Future[Response[ApiResponse]] = {
    logger.trace("Sending request to AI API at {}", request.uri)
    request.send(backend)
  }

  private def handleResponse(response: Response[ApiResponse])(implicit ec: ExecutionContext): Future[String] = {
    logger.debug("Received successful HTTP response (Status: {}). Processing body...", response.code)
    response.body match {
      case Right(apiResponse) =>
        apiResponse.candidates.flatMap(_.headOption) match {
          case Some(candidate) => extractAnswerFromContent(candidate.content)
          case None =>
            val finishReason = apiResponse.candidates.flatMap(_.headOption.flatMap(_.finishReason)).getOrElse("N/A")
            val errorMsg = if (finishReason == "SAFETY") "Ответ AI заблокирован из-за настроек безопасности." else s"Ответ AI не содержит данных (причина: $finishReason)."
            logger.warn(errorMsg + s" Finish reason: $finishReason")
            Future.failed(new Exception(errorMsg))
        }

      case Left(deserError: DeserializationException[io.circe.Error]) =>
        val specificErrorMessage = s"Circe error: ${deserError.error.getMessage}"
        logger.error(s"Failed to deserialize successful AI API response body (Status: ${response.code}). $specificErrorMessage. Body start: ${deserError.body.take(500)}", deserError.error)
        Future.failed(new Exception(s"Ошибка разбора ответа от AI: $specificErrorMessage"))

      case Left(otherError) =>
        logger.error(s"Unexpected Left type in successful response body: ${otherError.getClass.getName}", otherError)
        Future.failed(new Exception(s"Неожиданная ошибка обработки ответа: ${otherError.getMessage}", otherError))
    }
  }

  private def extractAnswerFromContent(content: Content): Future[String] = {
    val textParts = content.parts.flatMap(_.text)
    if (textParts.nonEmpty) {
      Future.successful(textParts.mkString)
    } else {
      logger.warn("AI response content contains no text parts.")
      Future.failed(new Exception("Ответ AI не содержит текстовых данных."))
    }
  }

  /**
   * Обрабатывает ЛЮБЫЕ ошибки Future (сетевые, HTTP 4xx/5xx, ошибки сборки запроса и т.д.).
   * ИСПРАВЛЕНО: Добавлена проверка типа тела ошибки в HttpError.
   */
  private def handleGenericError(error: Throwable): Future[String] = {
    error match {
      // Ошибка HTTP (4xx, 5xx)
      case HttpError(body, statusCode) =>
        // --- ИЗМЕНЕНИЕ НАЧАЛО ---
        // Явно проверяем тип тела ошибки
        val bodyString = body match {
          case s: String => s // Используем как строку, если это строка
          case bytes: Array[Byte] => // Если байты, пробуем декодировать как UTF-8
            Try(new String(bytes, "UTF-8")).getOrElse(s"<non-string body: ${bytes.length} bytes>")
          case other => // Для других типов используем toString()
            logger.warn(s"Received HttpError with non-string/non-byte body type: ${other.getClass.getName}. Converting to string.")
            Option(other).map(_.toString).getOrElse("<null body>") // Добавлена проверка на null
        }
        // --- ИЗМЕНЕНИЕ КОНЕЦ ---
        val errorMsg = extractErrorMessageFromJson(bodyString) // Пытаемся извлечь сообщение из JSON
          .getOrElse(s"Код ошибки: $statusCode. Тело ответа: ${bodyString.take(500)}") // Используем bodyString
        logger.warn("Received HTTP Error from AI API. Status: {}, Message: {}", statusCode, errorMsg)
        Future.failed(new Exception(s"Ошибка API $statusCode: $errorMsg")) // Возвращаем Future.failed

      // Ошибка сборки запроса или другая общая ошибка
      case NonFatal(other) =>
        logger.error("AI request processing failed.", other)
        Future.failed(new Exception(s"Ошибка выполнения запроса к AI: ${other.getMessage}", other)) // Возвращаем Future.failed

      // Пробрасываем фатальные ошибки
      case fatal =>
        logger.error("Fatal error during AI request processing.", fatal)
        throw fatal // Перебрасываем фатальную ошибку
    }
  }


  /** Пытается извлечь сообщение об ошибке из JSON тела ответа API Gemini. */
  private def extractErrorMessageFromJson(body: String): Option[String] = {
    parse(body).flatMap(_.as[GeminiApiError]) match {
      case Right(geminiError) =>
        Some(s"${geminiError.error.message} (status: ${geminiError.error.status}, code: ${geminiError.error.code})")
      case Left(_) =>
        parse(body).flatMap(_.as[ErrorDetails]) match {
          case Right(details) => Some(s"${details.message} (status: ${details.status}, code: ${details.code})")
          case Left(_) => None
        }
    }
  }

  def updateModel(newModel: String): String = {
    val trimmedModel = newModel.trim
    if (trimmedModel.isEmpty) {
      logger.warn("Attempted to update AI model to an empty string. Keeping current model '{}'.", currentModelRef.get())
      currentModelRef.get() // Return current model if new one is empty
    } else {
      val previousModel = currentModelRef.getAndSet(trimmedModel)
      if (trimmedModel != previousModel) {
        logger.info("Updated AI model used by AIService from '{}' to: {}", previousModel, trimmedModel)
      } else {
        logger.trace("AI model update requested, but new model name is the same as current ('{}').", previousModel)
      }
      previousModel // Return previous model
    }
  }

  /** Возвращает текущее имя модели, используемой сервисом. */
  def getCurrentModel: String = currentModelRef.get()

  def shutdown(): Future[Unit] = {
    logger.info("Shutting down AIService HTTP backend...")
    backend.close()
  }
}