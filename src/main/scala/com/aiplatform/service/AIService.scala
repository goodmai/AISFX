package com.aiplatform.service

import org.apache.pekko.actor.ActorSystem
import sttp.client3._
import sttp.client3.circe._
import sttp.client3.pekkohttp.PekkoHttpBackend
import io.circe.generic.auto._
import io.circe.syntax._
import io.circe.parser.parse
import sttp.model.{StatusCode, Uri}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try // Добавлен импорт Try

// Обновленные case-классы для точного соответствия API Gemini
case class Part(text: String)
case class Content(parts: List[Part], role: Option[String] = None)
case class GenerateContentRequest(contents: List[Content])
case class Candidate(content: Content)
case class UsageMetadata(
                          promptTokenCount: Int,
                          candidatesTokenCount: Int,
                          totalTokenCount: Int
                        )
case class ErrorDetails(
                         code: Int,
                         message: String,
                         status: String
                       )
// Добавлен корневой класс для парсинга ошибок API Gemini
case class GeminiApiError(error: ErrorDetails)

case class GenerateContentResponse(
                                    candidates: Option[List[Candidate]] = None,
                                    usageMetadata: Option[UsageMetadata] = None
                                    // Убрано поле error, так как ошибки обрабатываются через ResponseException
                                    // или парсингом тела ошибки при HttpError
                                  )

class AIService(apiKey: String)(implicit classicSystem: ActorSystem) {
  // Псевдоним для сложного типа ответа для улучшения читаемости
  type ApiResponse = Either[ResponseException[String, io.circe.Error], GenerateContentResponse]

  // Используем PekkoHttpBackend напрямую, т.к. usingActorSystem может быть deprecated или измениться
  private implicit val backend: SttpBackend[Future, Any] = PekkoHttpBackend.usingActorSystem(classicSystem)

  private val baseUri = "https://generativelanguage.googleapis.com/v1beta/models/"
  @volatile private var currentModel = "gemini-1.5-flash-latest"

  // Основной метод обработки запроса
  def process(prompt: String)(implicit ec: ExecutionContext): Future[String] = {
    val request = buildRequest(prompt)

    sendRequest(request) // Отправляем запрос
      .flatMap(handleResponse) // Обрабатываем ответ Future
      .recoverWith(handleExceptions) // Обрабатываем другие исключения Future
  }

  // Построение HTTP-запроса
  // Исправлена сигнатура: добавлен параметр R=Any
  private def buildRequest(prompt: String): Request[ApiResponse, Any] = {
    val payload = GenerateContentRequest(List(
      Content(parts = List(Part(prompt)), role = Some("user"))
    ))

    basicRequest
      .post(Uri.unsafeParse(s"$baseUri$currentModel:generateContent?key=$apiKey"))
      .contentType("application/json")
      .body(payload.asJson.noSpaces)
      // Указываем, как парить ответ: JSON в GenerateContentResponse
      // Ошибки парсинга или HTTP ошибки будут обернуты в Either
      .response(asJson[GenerateContentResponse])
  }

  // Отправка запроса и обработка ответа
  // Исправлены типы параметра request и возвращаемого значения
  private def sendRequest(
                           request: Request[ApiResponse, Any]
                         ): Future[Response[ApiResponse]] = {
    request.send(backend)
  }

  // Обработка успешного или неуспешного ответа (уровень HTTP и десериализации)
  // Используем ApiResponse в сигнатуре
  private def handleResponse(response: Response[ApiResponse])(implicit ec: ExecutionContext): Future[String] = {
    response.body match {
      // Успешная десериализация в GenerateContentResponse
      case Right(apiResponse) =>
        apiResponse.candidates match {
          // Есть кандидаты, берем первого
          case Some(candidate :: _) =>
            extractAnswer(candidate.content) // Возвращает Future[String]
          // Кандидатов нет или список пуст
          case Some(Nil) | None =>
            Future.failed(new Exception("No candidates found in response"))
          // Поле candidates отсутствует (хотя case класс определяет его как Option)
          // Этот случай покрывается предыдущим, но оставим для ясности
          // case None =>
          //   Future.failed(new Exception("Candidates field missing in response"))
        }
      // Ошибка HTTP или десериализации
      case Left(error) =>
        handleErrorResponse(error) // Возвращает Future.failed(...)
    }
  }

  // Извлечение текста ответа из первого Part
  private def extractAnswer(content: Content): Future[String] = content.parts match {
    case head :: _ => Future.successful(head.text)
    case _ => Future.failed(new Exception("Empty response content parts")) // Более точное сообщение
  }

  // Обработка ошибок типа ResponseException (HttpError, DeserializationException)
  // Возвращает Future[String], но всегда неуспешный (failed)
  private def handleErrorResponse(error: ResponseException[String, io.circe.Error]): Future[String] = error match {
    case HttpError(body, statusCode) =>
      Future.failed(new Exception(s"HTTP Error $statusCode: ${extractErrorMessage(body, statusCode)}"))
    case DeserializationException(body, error) =>
      Future.failed(new Exception(s"Deserialization Error: ${error.getMessage}. Original body: $body"))
  }

  // Извлечение сообщения об ошибке из тела ответа (улучшено)
  // Добавлен параметр statusCode для контекста
  private def extractErrorMessage(body: String, statusCode: StatusCode): String = {
    // Попытка 1: Парсим как GeminiApiError
    val attempt1: Either[io.circe.Error, String] = parse(body)
      .flatMap(_.as[GeminiApiError])
      .map(geminiError => s"${geminiError.error.message} (code: ${geminiError.error.code}, status: ${geminiError.error.status})")

    // Попытка 2: Парсим напрямую как ErrorDetails
    lazy val attempt2: Either[io.circe.Error, String] = parse(body)
      .flatMap(_.as[ErrorDetails])
      .map(details => s"${details.message} (code: ${details.code}, status: ${details.status})")

    attempt1.orElse(attempt2).getOrElse {
      s"Status code: $statusCode. Failed to parse error response. Body: ${body.take(200)}${if (body.length > 200) "..." else ""}"
    }
  }



  // Обработка исключений времени выполнения Future (например, проблемы с сетью)
  private def handleExceptions: PartialFunction[Throwable, Future[String]] = {
    // Ловим ResponseException отдельно, если они не были обработаны ранее (маловероятно из-за recoverWith)
    // case e: ResponseException[String, io.circe.Error] => handleErrorResponse(e)
    // Ловим другие исключения
    case ex: Exception =>
      // Логирование ошибки может быть полезно здесь
      // logger.error("Request failed unexpectedly", ex)
      Future.failed(new Exception(s"Request execution failed: ${ex.getMessage}", ex)) // Включаем исходное исключение
  }

  // Метод для обновления модели (без изменений, но с комментарием про потокобезопасность)
  def updateModel(newModel: String): Unit = {
    // @volatile помогает с видимостью, но не гарантирует атомарность
    // при одновременном вызове process и updateModel.
    // Для данного случая может быть достаточно.
    currentModel = newModel
  }

  // Метод для корректного завершения работы бэкенда
  def shutdown(): Future[Unit] = {
    backend.close()
  }
}