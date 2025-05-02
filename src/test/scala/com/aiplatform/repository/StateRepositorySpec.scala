// src/test/scala/com/aiplatform/repository/StateRepositorySpec.scala
package com.aiplatform.repository

import com.aiplatform.model._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.UUID


class StateRepositorySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  // --- --------------------------------------------- ---

  // --- ИЗМЕНЕНИЕ: Используем предполагаемый путь по умолчанию ---
  // Тест теперь ЗАВИСИТ от того, что StateRepository использует именно этот путь
  private val stateFilePath: Path = Paths.get("app_state.json")
  // --- ---------------------------------------------------- ---

  // --- ИЗМЕНЕНИЕ: Удален код для подмены пути через рефлексию ---

  // Используем afterAll из BeforeAndAfterAll для очистки после ВСЕХ тестов
  override def afterAll(): Unit = {
    try {
      Files.deleteIfExists(stateFilePath) // Удаляем файл состояния по умолчанию
      println(s"Deleted default state file used by test: $stateFilePath")
    } catch {
      case e: Exception => println(s"Error cleaning up default state file: ${e.getMessage}")
    }
    super.afterAll() // Вызываем afterAll суперкласса
  }

  // Используем withFixture для очистки перед/после КАЖДОГО теста
  override def withFixture(test: NoArgTest) = {
    println(s"Cleaning up default state file before test: ${test.name}")
    Files.deleteIfExists(stateFilePath) // Очистка перед каждым тестом
    try super.withFixture(test) // Запускаем тест
    finally {
      println(s"Cleaning up default state file after test: ${test.name}")
      Files.deleteIfExists(stateFilePath) // Очистка после каждого теста
    }
  }

  "StateRepository" should "save and load AppState correctly" in {
    // 1. Создаем сложное состояние
    val model1 = ModelInfo("model-1", "Model One")
    val model2 = ModelInfo("model-2", "Model Two")
    val presetD1 = PromptPreset("Preset D1", "D1: {{INPUT}}", isDefault = true)
    val presetC1 = PromptPreset("Preset C1", "C1: {{INPUT}}")

    val time1 = Instant.parse("2024-01-10T10:00:00Z")
    val time2 = Instant.parse("2024-01-10T10:00:30Z")
    val time3 = Instant.parse("2024-01-10T10:00:50Z")
    val time4 = Instant.parse("2024-01-10T10:01:00Z")
    val time5 = Instant.parse("2024-01-10T10:01:10Z")

    val dialog1 = Dialog("T1", "Req1", "Resp1", time2, "model-1")
    val dialog2 = Dialog("T1", "Req2", "Resp2", time4, "model-1")
    val dialog3 = Dialog("T2", "Req3", "Resp3", time5, "model-2")

    val topic1 = Topic(UUID.randomUUID().toString, "Topic One", List(dialog1, dialog2), time1, time4, "Research")
    val topic2 = Topic(UUID.randomUUID().toString, "Topic Two", List(dialog3), time3, time5, "Code")
    val topic3 = Topic(UUID.randomUUID().toString, "Topic Three", List(), time5, time5, "Global")

    val initialState = AppState(
      topics = List(topic1, topic2, topic3),
      activeTopicId = Some(topic2.id),
      lastActiveTopicPerCategory = Map("Research" -> topic1.id, "Code" -> topic2.id, "Global" -> topic3.id),
      globalAiModel = model2.name,
      availableModels = List(model1, model2),
      defaultPresets = List(presetD1),
      customPresets = List(presetC1),
      buttonMappings = Map("Research" -> presetD1.name, "Code" -> presetC1.name),
      fontFamily = "Arial",
      fontSize = 14
    )

    // 2. Сохраняем состояние (без передачи пути)
    noException should be thrownBy StateRepository.saveState(initialState)
    Files.exists(stateFilePath) shouldBe true // Проверяем, что файл создан по ожидаемому пути

    // --- Опционально: Проверка содержимого файла ---
    val savedJson = Files.readString(stateFilePath)
    // Можно добавить базовые проверки на наличие ключей в JSON
    savedJson should include ("\"globalAiModel\":\"model-2\"")
    savedJson should include ("\"fontFamily\":\"Arial\"")
    savedJson should include ("Topic One")
    // --- -------------------------------------- ---

    // 3. Загружаем состояние (без передачи пути)
    var loadedState: AppState = null
    noException should be thrownBy { loadedState = StateRepository.loadState() }

    // 4. Сравниваем состояния (как и раньше)
    loadedState should not be null
    loadedState.globalAiModel shouldBe initialState.globalAiModel
    loadedState.fontFamily shouldBe initialState.fontFamily
    loadedState.fontSize shouldBe initialState.fontSize
    loadedState.activeTopicId shouldBe initialState.activeTopicId
    loadedState.availableModels.sortBy(_.name) shouldBe initialState.availableModels.sortBy(_.name)
    loadedState.defaultPresets.sortBy(_.name) shouldBe initialState.defaultPresets.sortBy(_.name)
    loadedState.customPresets.sortBy(_.name) shouldBe initialState.customPresets.sortBy(_.name)
    loadedState.buttonMappings shouldBe initialState.buttonMappings
    loadedState.lastActiveTopicPerCategory shouldBe initialState.lastActiveTopicPerCategory
    loadedState.topics.map(_.id).toSet shouldBe initialState.topics.map(_.id).toSet
    loadedState.topics.sortBy(_.id) shouldBe initialState.topics.sortBy(_.id)

    val loadedTopic2 = loadedState.topics.find(_.id == topic2.id)
    loadedTopic2 shouldBe defined
    loadedTopic2.get.title shouldBe topic2.title
    loadedTopic2.get.category shouldBe topic2.category
    loadedTopic2.get.dialogs.sortBy(_.timestamp) shouldBe topic2.dialogs.sortBy(_.timestamp)
    loadedTopic2.get.createdAt.getEpochSecond shouldBe topic2.createdAt.getEpochSecond
    loadedTopic2.get.lastUpdatedAt.getEpochSecond shouldBe topic2.lastUpdatedAt.getEpochSecond
  }

  it should "return initial state if file does not exist" in {
    Files.deleteIfExists(stateFilePath)
    // Загружаем (без пути)
    val state = StateRepository.loadState()
    // Сравниваем с AppState.initialState
    state.topics shouldBe AppState.initialState.topics
    state.globalAiModel shouldBe AppState.initialState.globalAiModel
    state.activeTopicId shouldBe AppState.initialState.activeTopicId
    // ...
  }

  it should "return initial state if file is corrupted" in {
    // Пишем мусор в файл по умолчанию
    Files.writeString(stateFilePath, "{ \"invalid json data ")
    // Загружаем (без пути)
    val state = StateRepository.loadState()
    state.topics shouldBe AppState.initialState.topics
    state.globalAiModel shouldBe AppState.initialState.globalAiModel
    state.activeTopicId shouldBe AppState.initialState.activeTopicId
  }

}