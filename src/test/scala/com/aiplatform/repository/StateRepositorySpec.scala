// src/test/scala/com/aiplatform/repository/StateRepositorySpec.scala
package com.aiplatform.repository

import com.aiplatform.model._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.UUID
import org.scalatest.BeforeAndAfterEach // Added for more granular cleanup if needed alongside withFixture

class StateRepositorySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val stateFilePath: Path = Paths.get(StateRepository.STATE_FILE_NAME) // Use constant from StateRepository

  override def beforeAll(): Unit = {
    super.beforeAll()
    // Initial cleanup in case of leftover files from a previous failed run
    Files.deleteIfExists(stateFilePath)
    // Clean up any potential old backup files from the test directory
    cleanupBackupFiles(Paths.get(".")) // Assuming tests run in project root or a specific test dir
  }

  override def afterAll(): Unit = {
    Files.deleteIfExists(stateFilePath)
    cleanupBackupFiles(Paths.get("."))
    super.afterAll()
  }

  // withFixture handles cleanup for each test, ensuring stateFilePath is deleted.
  override def withFixture(test: NoArgTest) = {
    Files.deleteIfExists(stateFilePath) // Ensure clean state before test
    try super.withFixture(test)
    finally {
      Files.deleteIfExists(stateFilePath) // Clean up after test
    }
  }
  
  private def cleanupBackupFiles(directory: Path): Unit = {
    try {
      val directoryStream = Files.newDirectoryStream(directory, s"${stateFilePath.getFileName.toString}.corrupted_*")
      try {
        directoryStream.forEach(Files.deleteIfExists)
      } finally {
        directoryStream.close()
      }
    } catch {
      case e: Exception => // Ignore errors during cleanup of backups, they might not exist
    }
  }


  behavior of "StateRepository.saveState and StateRepository.loadState"

  it should "correctly save and then load a valid AppState" in {
    // Arrange
    val model1 = ModelInfo("model-1", "Model One Display")
    val model2 = ModelInfo("model-2", "Model Two Display")
    val presetD1 = PromptPreset("Default Preset 1", "Prompt for D1: {{INPUT}}", isDefault = true, temperature = 0.7, topP = 0.9, topK = Some(40), maxOutputTokens = Some(256))
    val presetC1 = PromptPreset("Custom Preset 1", "Prompt for C1: {{INPUT}}", isDefault = false, modelOverride = Some("model-1"))

    val time1 = Instant.now().minusSeconds(300)
    val time2 = time1.plusSeconds(30)
    val time3 = time1.plusSeconds(60)
    val time4 = time1.plusSeconds(90)
    val time5 = time1.plusSeconds(120)

    val dialog1 = Dialog("Dialog1", "Request 1", "Response 1", time2, "model-1")
    val dialog2 = Dialog("Dialog2", "Request 2", "Response 2", time4, "model-1")
    val dialog3 = Dialog("Dialog3", "Request 3", "Response 3", time5, "model-2")

    val topic1Id = UUID.randomUUID().toString
    val topic2Id = UUID.randomUUID().toString
    val topic3Id = UUID.randomUUID().toString

    val topic1 = Topic(topic1Id, "Research Topic", List(dialog1, dialog2), time1, time4, "Research")
    val topic2 = Topic(topic2Id, "Coding Topic", List(dialog3), time3, time5, "Code")
    val topic3 = Topic(topic3Id, "General Topic", List(), time5, time5, "Global")

    val appStateToSave = AppState(
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

    // Act
    val saveResult = StateRepository.saveState(appStateToSave)
    saveResult shouldBe a[Success[_]]
    Files.exists(stateFilePath) shouldBe true

    val savedJson = Files.readString(stateFilePath)
    savedJson should include ("\"globalAiModel\":\"model-2\"") // Quick check for content
    savedJson should include ("\"fontFamily\":\"Arial\"")
    savedJson should include ("Research Topic")

    val loadedState = StateRepository.loadState()

    // Assert
    // For comprehensive comparison, case classes should have proper equals or use libraries like diffx
    // Here, we rely on the default case class equals, which is field-by-field.
    loadedState shouldBe appStateToSave 
    
    // Example of more granular checks if needed, though 'loadedState shouldBe appStateToSave' covers it for case classes
    loadedState.globalAiModel shouldBe appStateToSave.globalAiModel
    loadedState.activeTopicId shouldBe appStateToSave.activeTopicId
    loadedState.topics.sortBy(_.id) shouldBe appStateToSave.topics.sortBy(_.id) // Compare sorted lists
    loadedState.availableModels.sortBy(_.name) shouldBe appStateToSave.availableModels.sortBy(_.name)
  }

  behavior of "StateRepository.loadState error handling"

  it should "return AppState.initialState if the state file does not exist" in {
    // Arrange
    // File is deleted by withFixture or ensured non-existent by beforeAll

    // Act
    val state = StateRepository.loadState()

    // Assert
    state shouldBe AppState.initialState
  }

  it should "return AppState.initialState and backup the corrupted file if the state file is corrupted" in {
    // Arrange
    val corruptedJson = "{ \"invalid json data, missing closing brace"
    Files.writeString(stateFilePath, corruptedJson)

    val state = StateRepository.loadState()

    val corruptedJsonContent = "{ \"invalidJson\": true, \"message\": \"This JSON is intentionally broken\" " // Missing closing brace
    Files.writeString(stateFilePath, corruptedJsonContent)

    // Act
    val state = StateRepository.loadState()

    // Assert
    state shouldBe AppState.initialState // Should return initial state

    Files.exists(stateFilePath) shouldBe true // Original corrupted file should still exist
    Files.readString(stateFilePath) shouldBe corruptedJsonContent // Content should be unchanged

    val parentDir = Option(stateFilePath.getParent).getOrElse(Paths.get(""))
    val backupFileStream = Files.list(parentDir)
    try {
      val backupFileOpt = backupFileStream
        .filter(Files.isRegularFile(_))
        .filter(_.getFileName.toString.startsWith(s"${stateFilePath.getFileName.toString}.corrupted_"))
        .findFirst()

      backupFileOpt shouldBe defined // A backup file should have been created
      backupFileOpt.ifPresent { backupPath =>
        Files.readString(backupPath) shouldBe corruptedJsonContent // Backup content should match corrupted content
        Files.deleteIfExists(backupPath) // Clean up the specific backup file
      }
    } finally {
      backupFileStream.close() // Ensure stream is closed
    }
  }
  
  it should "return AppState.initialState if the state file is empty" in {
    // Arrange
    Files.writeString(stateFilePath, "") // Create an empty file

    // Act
    val state = StateRepository.loadState()

    // Assert
    state shouldBe AppState.initialState 

    Files.exists(stateFilePath) shouldBe true // Empty file should still exist
    Files.readString(stateFilePath) shouldBe "" // Content should still be empty
  }
}