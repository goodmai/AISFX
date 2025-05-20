// src/test/scala/com/aiplatform/repository/StateRepositorySpec.scala
package com.aiplatform.repository

import com.aiplatform.model._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.matchers.dsl.ResultOfATypeInvocation // For a[Type] syntax
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.UUID
import org.scalatest.BeforeAndAfterEach // Added for more granular cleanup if needed alongside withFixture
import scala.util.Success // Ensure Success is imported

class StateRepositorySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val stateFilePath: Path = Paths.get("app_state.json") // Replaced StateRepository.STATE_FILE_NAME

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
    val model1 = ModelInfo(name = "model-1", displayName = "Model One Display", description = None, supportedGenerationMethods = List("generateContent"))
    val model2 = ModelInfo(name = "model-2", displayName = "Model Two Display", description = None, supportedGenerationMethods = List("generateContent"))
    // Assuming topK is Int, removed maxOutputTokens
    val presetD1 = PromptPreset(name = "Default Preset 1", prompt = "Prompt for D1: {{INPUT}}", isDefault = true, temperature = 0.7, topP = 0.9, topK = 40, modelOverride = None)
    val presetC1 = PromptPreset(name = "Custom Preset 1", prompt = "Prompt for C1: {{INPUT}}", isDefault = false, temperature = 0.5, topP = 1.0, topK = 50, modelOverride = Some("model-1"))

    val time1 = Instant.now().minusSeconds(300) // Effectively createdAt for topic1 if used that way
    val time2 = time1.plusSeconds(30) // Dialog1 timestamp
    val time3 = time1.plusSeconds(60) // Effectively createdAt for topic2
    val time4 = time1.plusSeconds(90) // Dialog2 timestamp & lastUpdated for topic1
    val time5 = time1.plusSeconds(120) // Dialog3 timestamp & lastUpdated for topic2 & topic3

    // Corrected Dialog constructor based on compiler error: title, request, response, timestamp, model
    val dialog1 = Dialog(title = "D1", request = "Request 1", response = "Response 1", timestamp = time2, model = "model-1")
    val dialog2 = Dialog(title = "D2", request = "Request 2", response = "Response 2", timestamp = time4, model = "model-1")
    val dialog3 = Dialog(title = "D3", request = "Request 3", response = "Response 3", timestamp = time5, model = "model-2")

    val topic1Id = UUID.randomUUID().toString
    val topic2Id = UUID.randomUUID().toString
    val topic3Id = UUID.randomUUID().toString

    // Corrected Topic constructor: id, title, category, dialogs, lastUpdatedAt
    val topic1 = Topic(id = topic1Id, title = "Research Topic", category = "Research", dialogs = List(dialog1, dialog2), lastUpdatedAt = time4)
    val topic2 = Topic(id = topic2Id, title = "Coding Topic", category = "Code", dialogs = List(dialog3), lastUpdatedAt = time5)
    val topic3 = Topic(id = topic3Id, title = "General Topic", category = "Global", dialogs = List(), lastUpdatedAt = time5)

    val appStateToSave = AppState(
      topics = List(topic1, topic2, topic3),
      activeTopicId = Some(topic2.id),
      lastActiveTopicPerCategory = Map("Research" -> topic1.id, "Code" -> topic2.id, "Global" -> topic3.id),
      globalAiModel = model2.name,
      availableModels = List(model1, model2),
      defaultPresets = List(presetD1),
      customPresets = List(presetC1),
      buttonMappings = Map("Research" -> presetD1.name, "Code" -> presetC1.name),
      fileContext = None // Added fileContext
      // Removed fontFamily, fontSize
    )

    // Act
    val saveResult = StateRepository.saveState(appStateToSave)
    saveResult shouldBe a[scala.util.Success[?]] // Using ? for wildcard
    Files.exists(stateFilePath) shouldBe true

    val savedJson = Files.readString(stateFilePath)
    savedJson should include ("\"globalAiModel\":\"model-2\"") // Quick check for content
    // savedJson should include ("\"fontFamily\":\"Arial\"") // Removed
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

      backupFileOpt.isPresent shouldBe true // A backup file should have been created
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