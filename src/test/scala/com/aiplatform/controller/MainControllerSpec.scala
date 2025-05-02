// src/test/scala/com/aiplatform/controller/MainControllerSpec.scala
package com.aiplatform.controller

import com.aiplatform.model._
import com.aiplatform.repository.StateRepository
import com.aiplatform.service.HistoryService
// --- ИЗМЕНЕНИЕ: Убраны ResponseArea и RequestArea из импортов view ---
import com.aiplatform.view.{Header, HistoryPanel} // RequestArea, ResponseArea}
// --- -------------------------------------------------------------- ---
import org.apache.pekko.actor.typed.ActorSystem
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import org.mockito.Mockito._
import org.mockito.ArgumentMatchers.{any, eq => mockitoEq}
import org.mockito.ArgumentCaptor
import java.time.Instant
import java.util.UUID
import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag


class MainControllerSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  // --- Настройка моков и системы акторов ---
  implicit val system: ActorSystem[HistoryService.Command] = ActorSystem(HistoryService(), "MainControllerTestSystem")
  // Мокируем зависимости
  val mockHistoryPanel: HistoryPanel.type = mock[HistoryPanel.type]
  // --- ИЗМЕНЕНИЕ: Удалены моки для ResponseArea и RequestArea ---
  // val mockResponseArea: ResponseArea.type = mock[ResponseArea.type]
  // val mockRequestArea: RequestArea.type = mock[RequestArea.type]
  // --- ------------------------------------------------------- ---
  val mockStateRepository: StateRepository.type = mock[StateRepository.type]
  val mockHeader: Header = mock[Header]

  // Вспомогательная функция для создания MainController с моками
  private def createControllerWithMocks(initialState: AppState): MainController = {
    // Настройка мока loadState() без аргументов
    when(mockStateRepository.loadState()).thenReturn(initialState)

    // Создаем экземпляр контроллера
    val controller = new MainController() // Он вызовет loadState()

    // Подменяем ссылки на моки UI с помощью рефлексии
    def setPrivateField(obj: AnyRef, fieldName: String, value: Any): Unit = {
      try {
        val field = obj.getClass.getDeclaredField(fieldName)
        field.setAccessible(true)
        field.set(obj, value)
      } catch {
        case e: Exception => println(s"WARN: Failed to set private field '$fieldName' via reflection: ${e.getMessage}")
      }
    }
    // Устанавливаем моки
    setPrivateField(controller, "historyPanelRef", Some(mockHistoryPanel))
    setPrivateField(controller, "headerRef", Some(mockHeader))
    // Не устанавливаем моки для RequestArea и ResponseArea

    // Сбрасываем вызовы на моках ПОСЛЕ инициализации
    reset(mockStateRepository)
    reset(mockHistoryPanel)
    reset(mockHeader)

    controller
  }


  "MainController" should "add a new topic correctly when startNewTopic is called" in {
    // 1. Начальное состояние: один топик в категории "Code"
    val initialTopicId = UUID.randomUUID().toString
    val initialTopic = Topic(id = initialTopicId, title = "Initial Code Topic", category = "Code", dialogs = List(Dialog("t", "r", "a", Instant.now(), "m")))
    val initialState = AppState(
      topics = List(initialTopic),
      activeTopicId = Some(initialTopicId),
      lastActiveTopicPerCategory = Map("Code" -> initialTopicId),
      globalAiModel = "test-model",
      availableModels = List(ModelInfo("test-model", "Test Model")),
      defaultPresets = AppState.initialDefaultPresets,
      customPresets = List.empty,
      buttonMappings = AppState.initialButtonMappings,
      fontFamily = "System",
      fontSize = 13
    )

    val controller = createControllerWithMocks(initialState)

    // Устанавливаем категорию через публичный метод
    controller.handleHeaderAction("Code")
    // Проверим, что UI обновился (опционально)
    verify(mockHeader, times(1)).setActiveButton(mockitoEq("Code"))

    // 2. Вызов startNewTopic
    controller.startNewTopic()

    // 3. Проверка вызова saveState и захват состояния
    val stateCaptor: ArgumentCaptor[AppState] = ArgumentCaptor.forClass(classOf[AppState])
    // Проверка saveState(AppState) без второго аргумента
    verify(mockStateRepository, times(2)).saveState(stateCaptor.capture())

    // Берем последнее сохраненное состояние (после startNewTopic)
    val finalState = stateCaptor.getValue

    // 4. Проверка захваченного состояния
    finalState.topics.size shouldBe 2 // Должно быть два топика
    val newTopicOpt = finalState.topics.find(_.id != initialTopicId)
    newTopicOpt shouldBe defined // Новый топик должен существовать
    val newTopic = newTopicOpt.get

    newTopic.category shouldBe "Code" // Должен создаться в активной категории "Code"
    newTopic.dialogs shouldBe empty // Должен быть пустым
    newTopic.title shouldBe "Новый топик" // Или другой дефолтный заголовок

    finalState.activeTopicId shouldBe Some(newTopic.id) // Новый топик должен быть активным
    finalState.lastActiveTopicPerCategory.get("Code") shouldBe Some(newTopic.id) // Карта должна обновиться

    // 5. Проверка вызовов UI (косвенно)
    // Проверяем, что был вызов для обновления HistoryPanel после startNewTopic
    verify(mockHistoryPanel, atLeastOnce()).updateTopics(any[List[Topic]], any[Option[String]]) // Проверяем вызов на моке HistoryPanel
    // Вызовы RequestArea.clearInput() и ResponseArea.clearDialog() проверить сложнее без моков

  }

}