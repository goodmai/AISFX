// src/test/scala/com/aiplatform/controller/MainControllerSpec.scala
package com.aiplatform.controller

import com.aiplatform.model._
// StateRepository will be used directly for file ops, not mocked in most controller tests
import com.aiplatform.model._
import com.aiplatform.service.CredentialsService // For API key interactions (static object)
import com.aiplatform.util.JsonUtil
import com.aiplatform.view.{Footer, Header} // HistoryPanel, ResponseArea are objects
import org.apache.pekko.actor.typed.ActorSystem
import org.mockito.ArgumentMatchers.{any, eq => mockitoEq}
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}

import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.UUID
import scala.util.Success


class MainControllerSpec extends AnyFlatSpec with Matchers with MockitoSugar with BeforeAndAfterAll with BeforeAndAfterEach {

  implicit val systemActor: ActorSystem[Nothing] = ActorSystem(mock[org.apache.pekko.actor.typed.Behavior[Nothing]], "mockSystem")

  private val stateFilePath: Path = Paths.get("app_state.json")
  private val apiKeyPath: Path = Paths.get(CredentialsService.API_KEY_FILE_PATH) // Assuming CredentialsService has this

  var mockHeader: Header = _
  var mockFooter: Footer = _
  // Cannot easily mock objects like DialogUtils, HistoryPanel, ResponseArea without PowerMock or code changes.
  // Tests will focus on MainController logic and interactions with mockable dependencies or observable state changes.

  override def beforeAll(): Unit = {
    super.beforeAll()
    Files.deleteIfExists(stateFilePath)
    Files.deleteIfExists(apiKeyPath) // Clean up API key file if it exists
  }

  override def afterAll(): Unit = {
    Files.deleteIfExists(stateFilePath)
    Files.deleteIfExists(apiKeyPath)
    ActorSystem.terminate(systemActor)
    super.afterAll()
  }

  // Clean up state file before each test to ensure independence
  override def beforeEach(): Unit = {
    super.beforeEach()
    Files.deleteIfExists(stateFilePath)
    Files.deleteIfExists(apiKeyPath)
    // Resetting static mocks or shared state if any (tricky with objects)
  }


  private def initializeStateFile(appState: AppState): Unit = {
    val json = JsonUtil.serialize(appState)
    Files.writeString(stateFilePath, json)
  }

  private def readStateFile(): AppState = {
    if (Files.exists(stateFilePath)) {
      val json = JsonUtil.readFromFile[AppState](stateFilePath.toString) // Assuming JsonUtil has this
      json.getOrElse(fail("Failed to read/deserialize app_state.json during test verification"))
    } else {
      fail("app_state.json does not exist when it was expected.")
    }
  }
  
  private def setPrivateField(obj: AnyRef, fieldName: String, value: Any): Unit = {
    try {
      val field = obj.getClass.getDeclaredField(fieldName)
      field.setAccessible(true)
      field.set(obj, value)
    } catch {
      case e: NoSuchFieldException => println(s"WARN: Field '$fieldName' not found in ${obj.getClass.getName}. Skipping mock setup. Error: ${e.getMessage}")
      case e: Exception => println(s"WARN: Failed to set private field '$fieldName' via reflection: ${e.getMessage}")
    }
  }

  private def setupController(initialState: AppState): MainController = {
    initializeStateFile(initialState) // Ensure state file is set up before controller instantiation

    mockHeader = mock[Header]
    mockFooter = mock[Footer]

    val controller = new MainController()(systemActor)

    setPrivateField(controller, "headerRef", Some(mockHeader))
    setPrivateField(controller, "footerRef", Some(mockFooter))
    
    // Reset mocks AFTER controller initialization AND injection of mock refs.
    // The controller's constructor calls initializeController -> performInitialUISetup -> synchronizeUIState.
    // synchronizeUIState interacts with headerRef and footerRef.
    reset(mockHeader, mockFooter)
    
    controller
  }

  // Behavior descriptions for better test organization
  behavior of "MainController Topic Management"

  it should "add a new topic correctly when startNewTopic is called" in {
    // Arrange
    val initialTopicId = UUID.randomUUID().toString
    val initialCategory = "Code"
    val initialTopic = Topic(id = initialTopicId, title = "Initial Code Topic", category = initialCategory, dialogs = List(Dialog("req", "resp", "model", Instant.now(), "modelId")))
    val initialState = AppState.initialState.copy(
      topics = List(initialTopic),
      activeTopicId = Some(initialTopicId),
      lastActiveTopicPerCategory = Map(initialCategory -> initialTopicId)
    )
    val controller = setupController(initialState)

    // Act
    controller.startNewTopic()

    // Assert
    val finalState = readStateFile()
    finalState.topics.size shouldBe 2
    val newTopicOpt = finalState.topics.find(_.id != initialTopicId)
    newTopicOpt shouldBe defined
    val newTopic = newTopicOpt.get

    newTopic.category shouldBe initialCategory
    newTopic.title shouldBe "Новый топик" // Default title
    newTopic.dialogs shouldBe empty
    finalState.activeTopicId shouldBe Some(newTopic.id)
    finalState.lastActiveTopicPerCategory.get(initialCategory) shouldBe Some(newTopic.id)

    verify(mockFooter, times(1)).clearInput()
    // From synchronizeUIState call chain:
    verify(mockHeader, times(1)).setActiveButton(mockitoEq(initialCategory))
    verify(mockFooter, times(1)).setLocked(false) // isRequestInProgress should be false
  }

  it should "start a new topic if the last topic in a category is deleted" in {
    // Arrange
    val category = "TestCategory"
    val topicId = UUID.randomUUID().toString
    val initialTopic = Topic(id = topicId, title = "The Only Topic", category = category)
    val initialState = AppState.initialState.copy(
      topics = List(initialTopic),
      activeTopicId = Some(topicId),
      lastActiveTopicPerCategory = Map(category -> topicId)
    )
    val controller = setupController(initialState)
    // Assuming DialogUtils.showConfirmation would return OK for deletion.

    // Act
    controller.deleteTopic(topicId)
    // TestFX might be needed for proper Platform.runLater handling. Assume direct enough for state check.

    // Assert
    val finalState = readStateFile()
    finalState.topics.exists(_.id == topicId) shouldBe false
    finalState.topics.size shouldBe 1 // New one created
    val newTopic = finalState.topics.head
    newTopic.category shouldBe category
    newTopic.title shouldBe "Новый топик"
    finalState.activeTopicId shouldBe Some(newTopic.id)
    finalState.lastActiveTopicPerCategory.get(category) shouldBe Some(newTopic.id)
    
    verify(mockHeader, times(1)).setActiveButton(mockitoEq(category)) // From startNewTopic -> ... -> synchronizeUIState
    verify(mockFooter, times(1)).clearInput() // From startNewTopic
    verify(mockFooter, atLeastOnce()).setLocked(false) // From synchronizeUIState after new topic
  }

  it should "activate the next available topic when an active topic is deleted" in {
    // Arrange
    val category = "Work"
    val topic1Id = UUID.randomUUID().toString // To be deleted
    val topic2Id = UUID.randomUUID().toString // To become active
    val topic1 = Topic(id = topic1Id, title = "Old Task", category = category, lastUpdatedAt = Instant.now().minusSeconds(100))
    val topic2 = Topic(id = topic2Id, title = "New Task", category = category, lastUpdatedAt = Instant.now())
    val initialState = AppState.initialState.copy(
      topics = List(topic1, topic2),
      activeTopicId = Some(topic1Id),
      lastActiveTopicPerCategory = Map(category -> topic1Id)
    )
    val controller = setupController(initialState)

    // Act
    controller.deleteTopic(topic1Id)

    // Assert
    val finalState = readStateFile()
    finalState.topics.exists(_.id == topic1Id) shouldBe false
    finalState.topics.size shouldBe 1
    finalState.topics.head.id shouldBe topic2Id
    finalState.activeTopicId shouldBe Some(topic2Id)
    finalState.lastActiveTopicPerCategory.get(category) shouldBe Some(topic2Id)

    verify(mockHeader, times(1)).setActiveButton(mockitoEq(category)) // From setActiveTopic -> synchronizeUIState
    verify(mockFooter, times(1)).setLocked(false) // From synchronizeUIState
  }

  behavior of "MainController Category Switching"

  it should "switch category and activate appropriate topic via handleHeaderAction" in {
    // Arrange
    val categoryA = "Personal"
    val categoryB = "ProjectX"
    val topicAId = UUID.randomUUID().toString
    val topicBId = UUID.randomUUID().toString
    val topicA = Topic(id = topicAId, title = "Groceries", category = categoryA, lastUpdatedAt = Instant.now().minusSeconds(100))
    val topicB = Topic(id = topicBId, title = "Main Feature", category = categoryB, lastUpdatedAt = Instant.now())
    val initialState = AppState.initialState.copy(
      topics = List(topicA, topicB),
      activeTopicId = Some(topicAId),
      lastActiveTopicPerCategory = Map(categoryA -> topicAId, categoryB -> topicBId)
    )
    val controller = setupController(initialState)
    
    // Act
    controller.handleHeaderAction(categoryB)
    
    // Assert
    val finalState = readStateFile()
    finalState.activeTopicId shouldBe Some(topicBId)
    finalState.lastActiveTopicPerCategory.get(categoryB) shouldBe Some(topicBId)
    
    verify(mockHeader, times(1)).setActiveButton(mockitoEq(categoryB)) // Via setActiveTopic -> synchronizeUIState
    verify(mockFooter, times(1)).setLocked(false) // Via synchronizeUIState
  }
  
  it should "resync state by calling setActiveTopic when clicking the current active category via handleHeaderAction" in {
    // Arrange
    val categoryA = "General"
    val topicAId = UUID.randomUUID().toString
    val topicA = Topic(id = topicAId, title = "Notes", category = categoryA)
    val initialState = AppState.initialState.copy(
      topics = List(topicA),
      activeTopicId = Some(topicAId),
      lastActiveTopicPerCategory = Map(categoryA -> topicAId)
    )
    val controller = setupController(initialState)
        
    // Act
    controller.handleHeaderAction(categoryA) // Click same category
    
    // Assert
    val finalState = readStateFile() 
    finalState.activeTopicId shouldBe Some(topicAId) // Active topic remains
    
    verify(mockHeader, times(1)).setActiveButton(mockitoEq(categoryA)) // From setActiveTopic -> synchronizeUIState
    verify(mockFooter, times(1)).setLocked(false) // From synchronizeUIState
  }

  behavior of "MainController User Input and Request Execution"

  it should "lock UI and prepare for request when processUserInput is called with valid input" in {
    // Arrange
    // Ensure API key exists for this positive path test
    CredentialsService.saveApiKey("test-api-key") shouldBe a[Success[_]]
    val category = "Global"
    val initialState = AppState.initialState.copy(globalAiModel = "test-model", availableModels = List(ModelInfo("test-model", "Test Model")))
    val controller = setupController(initialState)
    val inputText = "test query"

    // Act
    controller.processUserInput(inputText)
    // Note: Actual async request execution part is hard to test without mocking internal RequestExecutionManager

    // Assert
    // UI locking and preparation:
    verify(mockFooter, times(1)).setLocked(true)
    verify(mockFooter, times(1)).clearInput()
    
    // Interactions with ResponseArea (static calls, hard to mock directly without PowerMock/refactor)
    // For now, we assume these would be called:
    // ResponseArea.addRequestTurn(inputText)
    // ResponseArea.showLoadingIndicatorForRequest(...)
    
    // isRequestInProgress should be true. Cannot check private field directly without reflection.
    // The setLocked(true) is an indicator.
  }

  it should "not proceed with request if input text is empty" in {
    // Arrange
    val controller = setupController(AppState.initialState)

    // Act
    controller.processUserInput("") // Empty input

    // Assert
    // No request should be initiated, footer should not be locked for sending
    verify(mockFooter, never()).setLocked(true)
    verify(mockFooter, never()).clearInput()
    // DialogUtils.showError should be called - difficult to verify static call
  }
  
  it should "not proceed with request if API key is missing" in {
    // Arrange
    Files.deleteIfExists(apiKeyPath) // Ensure no API key file
    val controller = setupController(AppState.initialState)

    // Act
    controller.processUserInput("some query")

    // Assert
    verify(mockFooter, never()).setLocked(true)
    // DialogUtils.showError for missing API key (static call)
  }

  behavior of "MainController Settings Management"

  it should "update global AI model successfully" in {
    // Arrange
    val modelA = ModelInfo("model-a", "Model A")
    val modelB = ModelInfo("model-b", "Model B")
    val initialState = AppState.initialState.copy(
      globalAiModel = modelA.name,
      availableModels = List(modelA, modelB)
    )
    val controller = setupController(initialState)

    // Act
    val result = controller.updateGlobalAIModel(modelB.name)
    result shouldBe a[Success[_]]

    // Assert
    val finalState = readStateFile()
    finalState.globalAiModel shouldBe modelB.name
    
    // synchronizeUIState should be called
    verify(mockHeader, times(1)).setActiveButton(any[String]) // Category might not change
    verify(mockFooter, times(1)).setLocked(false)
  }

  it should "fail to update global AI model if model name is invalid or unavailable" in {
    // Arrange
    val modelA = ModelInfo("model-a", "Model A")
    val initialState = AppState.initialState.copy(
      globalAiModel = modelA.name,
      availableModels = List(modelA)
    )
    val controller = setupController(initialState)

    // Act
    val result = controller.updateGlobalAIModel("non-existent-model")
    result shouldBe a[Failure[_]]

    // Assert
    val finalState = readStateFile()
    finalState.globalAiModel shouldBe modelA.name // Should not change
    
    // synchronizeUIState should NOT have been called if update fails early
    verify(mockHeader, never()).setActiveButton(any[String])
    verify(mockFooter, never()).setLocked(any[Boolean])
  }
}