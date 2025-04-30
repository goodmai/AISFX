package com.aiplatform.service

import org.apache.pekko.actor.ActorSystem // Используем classic ActorSystem для Pekko HTTP
import sttp.client3._
import sttp.client3.circe._
import sttp.client3.pekkohttp.PekkoHttpBackend
import io.circe.{Encoder, Json, JsonObject}
import io.circe.generic.auto._ // Для автоматической генерации Encoder/Decoder для case классов
import io.circe.syntax._
import io.circe.parser.parse
import sttp.model.{StatusCode, Uri}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal // <<< --- Импорт NonFatal --- <<<
import org.slf4j.LoggerFactory
import com.aiplatform.model.Dialog // Импортируем модель Dialog для использования в истории

// --- Модели данных для взаимодействия с Gemini API ---

// Часть контента (обычно текст)
case class Part(text: String)

// Контент запроса или ответа, содержит части и роль (user или model)
case class Content(parts: List[Part], role: Option[String] = None)

// Конфигурация генерации (температура, topP, topK)
case class GenerationConfig(
                             temperature: Option[Double] = None,
                             topP: Option[Double] = None,
                             topK: Option[Int] = None
                           )

// Основной объект запроса к API
case class GenerateContentRequest(
                                   contents: List[Content], // Список ходов диалога (история + текущий запрос)
                                   generationConfig: Option[GenerationConfig] = None // Настройки генерации
                                 )

// Компаньон-объект для GenerateContentRequest для кастомного Encoder'а
object GenerateContentRequest {
  // Неявный Encoder для преобразования GenerateContentRequest в JSON
  implicit val encoder: Encoder[GenerateContentRequest] = (req: GenerateContentRequest) => {
    // Базовый JSON с полем "contents"
    val baseJson = Json.obj(
      "contents" -> req.contents.asJson
    )
    // Обрабатываем опциональный generationConfig
    val configJson = req.generationConfig.flatMap { config =>
      // Собираем поля конфигурации, только если они заданы (не None)
      val configFields = List(
        config.temperature.map("temperature" -> Json.fromDoubleOrNull(_)),
        config.topP.map("topP" -> Json.fromDoubleOrNull(_)),
        config.topK.map("topK" -> Json.fromInt(_))
      ).flatten // Убираем None опции
      // Если есть хотя бы одно поле, создаем JsonObject
      if (configFields.isEmpty) None else Some(JsonObject.fromIterable(configFields))
    }

    // Добавляем generationConfig в JSON, если он не пустой
    configJson match {
      case Some(cfgObj) => baseJson.deepMerge(Json.obj("generationConfig" -> Json.fromJsonObject(cfgObj)))
      case None => baseJson // Возвращаем базовый JSON, если конфиг пуст
    }
  }
}

// Модели для разбора ответа API
case class Candidate(content: Content) // Кандидат на ответ
case class UsageMetadata( // Метаданные об использовании токенов
                          promptTokenCount: Int,
                          candidatesTokenCount: Int,
                          totalTokenCount: Int
                        )
case class ErrorDetails(code: Int, message: String, status: String) // Детали ошибки от API
case class GeminiApiError(error: ErrorDetails) // Структура ошибки API
case class GenerateContentResponse( // Основной объект ответа API
                                    candidates: Option[List[Candidate]] = None,
                                    usageMetadata: Option[UsageMetadata] = None
                                  )
// --- ------------------------------------ ---

/**
 * Сервис для взаимодействия с Google Generative AI API (Gemini).
 * Отправляет запросы на генерацию контента и обрабатывает ответы.
 * @param classicSystem Неявный параметр ActorSystem (классический) для Pekko HTTP бэкенда.
 */
class AIService(implicit classicSystem: ActorSystem) { // Требует classic ActorSystem
  private val logger = LoggerFactory.getLogger(getClass) // Логгер
  // Тип для результата запроса sttp: либо ошибка, либо успешный GenerateContentResponse
  type ApiResponse = Either[ResponseException[String, io.circe.Error], GenerateContentResponse]
  // HTTP бэкенд на основе Pekko HTTP для отправки запросов
  private implicit val backend: SttpBackend[Future, Any] = PekkoHttpBackend.usingActorSystem(classicSystem)

  private val baseUri = "https://generativelanguage.googleapis.com/v1beta/models/" // Базовый URL Gemini API
  @volatile private var currentModel = "gemini-1.5-flash-latest" // Модель по умолчанию (может изменяться через updateModel)

  /**
   * Основной метод для обработки запроса пользователя к AI.
   * Включает историю диалога для контекста.
   */
  def process(
               prompt: String, // Текущий текст запроса от пользователя
               apiKey: String, // API ключ
               temperature: Option[Double], // Настройки генерации
               topP: Option[Double],
               topK: Option[Int],
               history: List[Dialog] // История предыдущих диалогов для контекста
             )(implicit ec: ExecutionContext): Future[String] = { // Требует ExecutionContext для Future

    // Проверка наличия API ключа
    if (apiKey.trim.isEmpty) {
      logger.error("API Key is empty. Cannot process request.")
      // Возвращаем Future.failed, если ключа нет
      return Future.failed(new IllegalArgumentException("API Key is required but was empty."))
    }

    // Формирование объекта конфигурации генерации
    val genConfig = GenerationConfig(temperature = temperature, topP = topP, topK = topK)
    // Передаем конфиг, только если в нем есть хотя бы один параметр
    val effectiveGenConfig = if (genConfig.temperature.isEmpty && genConfig.topP.isEmpty && genConfig.topK.isEmpty) None else Some(genConfig)

    logger.debug(s"Processing AI request. History size: ${history.size}, Model: $currentModel, Temp: $temperature, TopP: $topP, TopK: $topK")

    // Создание HTTP запроса
    val request: Request[ApiResponse, Any] = buildRequest(prompt, apiKey, effectiveGenConfig, history)

    // Асинхронная отправка запроса и обработка результата
    sendRequest(request) // Отправляем запрос
      .flatMap(handleResponse) // Обрабатываем успешный ответ
      .recoverWith(handleExceptions) // Обрабатываем любые исключения (сеть, таймауты, ошибки API)
  }

  /**
   * Вспомогательный метод для создания объекта HTTP запроса sttp.
   * Формирует JSON тело запроса с учетом истории и настроек.
   */
  private def buildRequest(
                            prompt: String,
                            apiKey: String,
                            genConfig: Option[GenerationConfig],
                            history: List[Dialog]
                          ): Request[ApiResponse, Any] = {

    // Преобразование истории (List[Dialog]) в формат List[Content] для API
    val historyContents: List[Content] = history.flatMap { dialog =>
      // Каждый Dialog разворачивается в пару Content: запрос пользователя и ответ модели
      List(
        Content(parts = List(Part(dialog.request)), role = Some("user")), // Запрос пользователя
        Content(parts = List(Part(dialog.response)), role = Some("model")) // Ответ модели
      )
      // Ограничиваем количество передаваемых сообщений истории (например, последние 10 диалогов = 20 сообщений)
      // Это важно для соблюдения лимитов API по размеру запроса/количеству токенов
    }.takeRight(20)

    // Добавляем текущий запрос пользователя как последний элемент 'user'
    val currentContent = Content(parts = List(Part(prompt)), role = Some("user"))
    // Объединяем историю и текущий запрос в один список
    val allContents = historyContents :+ currentContent

    // Создаем объект тела запроса GenerateContentRequest
    val payload = GenerateContentRequest(
      contents = allContents,
      generationConfig = genConfig // Добавляем настройки генерации, если они есть
    )

    // Формируем полный URL для запроса к API
    val targetUri = Uri.unsafeParse(s"$baseUri$currentModel:generateContent?key=$apiKey")

    // Логирование (исправленное): показываем начало первого и последнего сообщения
    val firstPartText = payload.contents.headOption.flatMap(_.parts.headOption).map(_.text.take(50)).getOrElse("Empty")
    val lastPartText = payload.contents.lastOption.flatMap(_.parts.headOption).map(_.text.take(50)).getOrElse("")
    logger.trace("Request Payload Contents Preview (first/last part): {}...{}", firstPartText, lastPartText)

    // Создаем и возвращаем объект запроса sttp
    basicRequest // Используем базовый запрос sttp
      .post(targetUri) // Метод POST и URL
      .contentType("application/json") // Заголовок Content-Type
      .body(payload) // Тело запроса (автоматически сериализуется в JSON)
      .response(asJson[GenerateContentResponse]) // Ожидаем ответ как JSON, десериализуемый в GenerateContentResponse
  }

  /**
   * Обновляет имя текущей используемой модели AI.
   */
  def updateModel(newModel: String): Unit = {
    val trimmedModel = newModel.trim
    // Обновляем только если имя не пустое и отличается от текущего
    if (trimmedModel.nonEmpty && trimmedModel != currentModel) {
      logger.info("Updating AI model used by AIService from '{}' to: {}", currentModel, trimmedModel)
      currentModel = trimmedModel // Устанавливаем новое имя
    } else if (trimmedModel.isEmpty) {
      logger.warn("Attempted to update AI model to an empty string. Keeping current model '{}'.", currentModel)
    }
  }

  /**
   * Освобождает ресурсы HTTP бэкенда. Вызывать при завершении приложения.
   */
  def shutdown(): Future[Unit] = {
    logger.info("Shutting down AIService HTTP backend...")
    backend.close() // Закрытие бэкенда sttp (PekkoHttpBackend)
  }

  // --- Приватные Вспомогательные Методы Обработки Ответов и Ошибок ---

  /** Асинхронно отправляет HTTP запрос с помощью sttp бэкенда. */
  private def sendRequest(request: Request[ApiResponse, Any]): Future[Response[ApiResponse]] = {
    logger.trace("Sending request to AI API at {}", request.uri)
    request.send(backend) // Выполняем отправку
  }

  /** Обрабатывает успешный HTTP ответ (статус 2xx). */
  private def handleResponse(response: Response[ApiResponse])(implicit ec: ExecutionContext): Future[String] = {
    logger.trace("Received successful response from AI API (Status: {}). Processing body...", response.code)
    response.body match {
      // Case 1: Тело ответа успешно десериализовано в GenerateContentResponse
      case Right(apiResponse) =>
        apiResponse.candidates match { // Ищем кандидатов в ответе
          case Some(candidate :: _) => // Если есть хотя бы один кандидат, берем первого
            extractAnswer(candidate.content) // Извлекаем текст ответа
          case _ => // Если кандидатов нет
            logger.warn("No candidates found in the successful AI response body.")
            Future.failed(new Exception("Ответ AI не содержит данных (no candidates).")) // Ошибка
        }
      // Case 2: Ошибка десериализации тела ответа (даже при статусе 2xx)
      case Left(error: ResponseException[String, io.circe.Error]) =>
        logger.error("Failed to process/deserialize successful AI API response body.", error)
        handleDeserializationError(error) // Обрабатываем ошибку десериализации
    }
  }

  /** Обрабатывает ошибки десериализации JSON ответа sttp. */
  private def handleDeserializationError(error: ResponseException[String, io.circe.Error]): Future[String] = error match {
    // Явно обрабатываем DeserializationException
    case DeserializationException(body, e) =>
      logger.warn(s"Failed to deserialize AI API response body. Circe Error: ${e.getMessage}. Body start: ${body.take(500)}", e)
      Future.failed(new Exception(s"Ошибка разбора ответа от AI: ${e.getMessage}"))
    // HttpError также может попасть сюда, если sttp не смог распарсить JSON ошибки
    case HttpError(body, statusCode) =>
      val errorMsg = extractErrorMessage(body, statusCode) // Пытаемся извлечь сообщение
      logger.warn("Received HTTP Error from AI API (during response body processing). Status: {}, Message: {}", statusCode, errorMsg)
      Future.failed(new Exception(s"Ошибка API $statusCode: $errorMsg"))
  }

  /** Извлекает основной текстовый ответ из 'content' кандидата. */
  private def extractAnswer(content: Content): Future[String] = content.parts match {
    case head :: _ => Future.successful(head.text) // Возвращаем текст из первой части
    case _ => Future.failed(new Exception("Ответ AI не содержит текстовых данных (content.parts).")) // Если список parts пуст
  }

  /** Обрабатывает HTTP ошибки (статус не 2xx), извлекая сообщение. */
  private def handleHttpError(body: String, statusCode: StatusCode): Future[String] = {
    val errorMsg = extractErrorMessage(body, statusCode) // Пытаемся извлечь читаемое сообщение
    logger.warn("Received HTTP Error from AI API. Status: {}, Extracted Message: {}", statusCode, errorMsg)
    // Возвращаем Future.failed с понятным сообщением
    Future.failed(new Exception(s"Ошибка API $statusCode: $errorMsg"))
  }

  /** Пытается извлечь сообщение об ошибке из JSON тела ответа API. */
  private def extractErrorMessage(body: String, statusCode: StatusCode): String = {
    // Попытка 1: Распарсить как стандартную структуру ошибки Gemini
    val attempt1: Either[io.circe.Error, String] = parse(body) // Парсим JSON строку
      .flatMap(_.as[GeminiApiError]) // Пытаемся декодировать как GeminiApiError
      .map(e => s"${e.error.message} (code: ${e.error.code}, status: ${e.error.status})") // Формируем строку из деталей

    lazy val attempt2: Either[io.circe.Error, String] = parse(body)
      .flatMap(_.as[ErrorDetails]) 
      .map(d => s"${d.message} (code: ${d.code}, status: ${d.status})") // Формируем строку

    attempt1.orElse(attempt2).getOrElse { // Если обе попытки парсинга не удались
      val bodyExcerpt = body.replaceAll("\\s+", " ").take(200) // Берем начало тела ответа, убирая лишние пробелы
      s"Не удалось извлечь детальное сообщение об ошибке API. Код ответа: $statusCode. Начало тела ответа: $bodyExcerpt${if (body.length > 200) "..." else ""}"
    }
  }

  /**
   * Обрабатывает исключения, возникшие при выполнении Future (например, сетевые ошибки, таймауты).
   * Используется как PartialFunction для метода `recoverWith`.
   */
  private def handleExceptions(implicit ec: ExecutionContext): PartialFunction[Throwable, Future[String]] = {

    case NonFatal(ex: Throwable) =>
      logger.error("AI request execution failed unexpectedly (e.g., network issue, timeout).", ex)
      Future.failed(new Exception(s"Ошибка выполнения запроса к AI: ${ex.getMessage}", ex))
  }
}