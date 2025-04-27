package com.aiplatform.controller

import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import com.aiplatform.model.*
import com.aiplatform.repository.StateRepository
import com.aiplatform.service.{AIService, HistoryService}
import com.aiplatform.view.{HistoryPanel, RequestArea, ResponseArea}
import com.typesafe.config.ConfigFactory
import scalafx.application.Platform
import scalafx.scene.layout.BorderPane
import scalafx.scene.Parent

// Добавлен импорт ExecutionContext
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

object MainController {
  // Фабричный метод для создания экземпляра
  def apply()(implicit system: ActorSystem[HistoryService.Command]): MainController =
    new MainController()
}

class MainController(implicit system: ActorSystem[HistoryService.Command]) {

  // Необходим ExecutionContext для Future.onComplete
  // Получаем его из ActorSystem
  private implicit val ec: ExecutionContext = system.executionContext

  private var currentState: AppState = StateRepository.loadState()
  private val config = ConfigFactory.load()
  // Используем классическую систему акторов для AIService, как и раньше
  private val aiService = new AIService(config.getString("ai.gemini.api-key"))(system.classicSystem)
  // ИСПРАВЛЕНО: Добавлен перенос строки
  private val historyActor: ActorRef[HistoryService.Command] = system

  /**
   * Обрабатывает пользовательский запрос.
   * @param requestText Пользовательский запрос
   */
  def processRequest(requestText: String): Unit = {
    validateInput(requestText) match {
      case Some(error) =>
        // Обновляем UI в потоке JavaFX
        Platform.runLater(() => ResponseArea.showError(error)) // Добавлено () => для Runnable

      case None =>
        Platform.runLater(() => ResponseArea.showLoadingIndicator()) // Добавлено () =>

        // Выполняем запрос асинхронно
        aiService.process(requestText).onComplete { // onComplete использует неявный ec
          case Success(aiResponse) =>
            val dialog = Dialog(requestText, aiResponse, currentState.aiModel)

            // Обновляем UI и состояние в потоке JavaFX
            Platform.runLater { () => // Блок кода для Runnable
              ResponseArea.updateResponse(dialog.response)
              HistoryPanel.addEntry(dialog)
            }

            // Отправляем сообщение актору истории (асинхронно)
            historyActor ! HistoryService.AddDialog(dialog)
            // Обновляем локальное состояние контроллера
            currentState = currentState.copy(
              dialogHistory = dialog :: currentState.dialogHistory // Добавляем в начало списка
            )

          case Failure(exception) =>
            // Логирование ошибки может быть полезно здесь
            // log.error("AI service request failed", exception)
            Platform.runLater(() => // Добавлено () =>
              ResponseArea.showError(s"Ошибка обработки запроса: ${exception.getMessage}")
            )
        }(ec) // Можно явно передать ec, если неявный не подхватывается
    }
  }

  /**
   * Создает корневой UI-элемент приложения.
   * @return Корневой узел сцены ScalaFX
   */
  def createUI(): Parent = {
    // Создаем панели, передавая зависимости
    val requestPanel = RequestArea.create(this) // Передаем ссылку на контроллер
    val responsePanel = ResponseArea.create()
    val historyPanel = HistoryPanel.create(historyActor) // Передаем ссылку на актора истории

    // Собираем главный макет
    new BorderPane {
      styleClass.add("main-pane") // Добавлен CSS класс для стилизации
      left = historyPanel
      center = new BorderPane {
        styleClass.add("center-pane")
        center = responsePanel
        bottom = requestPanel
      }
      // Лучше загружать стили в главном классе приложения
      // stylesheets.add(getClass.getResource("/styles.css").toExternalForm)
    }
  }

  /**
   * Сохраняет текущее состояние приложения.
   */
  def saveState(): Unit = {
    StateRepository.saveState(currentState)
    historyActor ! HistoryService.Save // Отправляем команду на сохранение актору
  }

  /**
   * Валидация входных данных.
   * @param text Входной текст
   * @return `Some(errorMessage)` если невалидно, `None` если валидно
   */
  private def validateInput(text: String): Option[String] = {
    val trimmed = text.trim
    if (trimmed.isEmpty) Some("Запрос не может быть пустым.")
    else if (trimmed.length > 4000) Some("Запрос слишком длинный (макс. 4000 симв).") // Увеличено ограничение
    else None
  }

  /**
   * Обновляет выбранную AI-модель.
   * @param newModel Идентификатор новой модели
   */
  def updateAIModel(newModel: String): Unit = {
    if (currentState.aiModel != newModel) {
      println(s"Updating AI model to: $newModel") // Логирование для отладки
      currentState = currentState.copy(aiModel = newModel)
      // Предполагаем, что AIService МОЖЕТ менять модель во время работы
      // Если AIService не может менять модель, эту строку нужно убрать,
      // а сервис пересоздавать при смене модели.
      aiService.updateModel(newModel)
      // Возможно, нужно обновить и UI, если модель где-то отображается
      // Platform.runLater(...)
    }
  }

  // Метод для корректного завершения работы сервисов при закрытии приложения
  def shutdown(): Unit = {
    println("Shutting down AI Service...")
    aiService.shutdown() // Предполагаем наличие метода shutdown в AIService
    // Остановка ActorSystem обычно происходит в главном классе приложения
  }
}