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
    // Добавим еще несколько проверок для полноты
    state.availableModels shouldBe AppState.initialState.availableModels
    state.defaultPresets shouldBe AppState.initialState.defaultPresets
    state.customPresets shouldBe AppState.initialState.customPresets
    state.buttonMappings shouldBe AppState.initialState.buttonMappings
    state.lastActiveTopicPerCategory shouldBe AppState.initialState.lastActiveTopicPerCategory
    state.fontFamily shouldBe AppState.initialState.fontFamily
    state.fontSize shouldBe AppState.initialState.fontSize
  }

  it should "return initial state and backup corrupted file if file is corrupted" in {
    val corruptedJson = "{ \"invalid json data, missing closing brace"
    Files.writeString(stateFilePath, corruptedJson)

    val state = StateRepository.loadState()

    // 1. Проверяем, что возвращено начальное состояние
    state shouldBe AppState.initialState

    // 2. Проверяем, что оригинальный файл все еще содержит поврежденный JSON
    // (поскольку мы делаем копию для бэкапа, а не перемещение)
    Files.exists(stateFilePath) shouldBe true
    Files.readString(stateFilePath) shouldBe corruptedJson

    // 3. Проверяем, что создан файл бэкапа
    val parentDir = stateFilePath.getParent
    val filesInDir = if (parentDir != null) Files.list(parentDir) else Files.list(Paths.get(""))
    
    val backupFileOpt = filesInDir
      .filter(Files.isRegularFile(_))
      .filter(_.getFileName.toString.startsWith(s"${stateFilePath.getFileName.toString}.corrupted_"))
      .findFirst()

    backupFileOpt.isPresent shouldBe true
    backupFileOpt.ifPresent { backupPath =>
      println(s"Found backup file: $backupPath")
      // 4. (Опционально) Проверяем содержимое бэкапа, если это важно
      // Files.readString(backupPath) shouldBe corruptedJson
      
      // Очищаем бэкап файл после теста
      try Files.deleteIfExists(backupPath) catch { case e: Exception => println(s"Could not delete backup file $backupPath: $e")}
    }
    // Закрываем Stream после использования
    filesInDir.close()
  }
  
  it should "return initial state if file is empty" in {
    Files.writeString(stateFilePath, "") // Создаем пустой файл
    val state = StateRepository.loadState()
    state shouldBe AppState.initialState // Должно быть полное сравнение с initialState

    // Дополнительно проверим, что пустой файл все еще существует (не удален)
    Files.exists(stateFilePath) shouldBe true
    Files.readString(stateFilePath) shouldBe ""
  }

}