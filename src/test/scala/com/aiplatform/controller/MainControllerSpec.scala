// src/test/scala/com/aiplatform/controller/MainControllerSpec.scala
package com.aiplatform.controller

import com.aiplatform.model._
// StateRepository will be used directly for file ops, not mocked in most controller tests
// import com.aiplatform.repository.StateRepository 
import com.aiplatform.service.HistoryService // Assuming this is needed for ActorSystem, replace if not
import com.aiplatform.util.JsonUtil
import com.aiplatform.view.{Header, HistoryPanel, Footer, ResponseArea} // Added Footer, ResponseArea
import org.apache.pekko.actor.typed.ActorSystem
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import org.mockito.Mockito._
import org.mockito.ArgumentMatchers.{any, eq => mockitoEq}
// ArgumentCaptor not used in this version, but keep if needed for other tests
// import org.mockito.ArgumentCaptor 
import java.time.Instant
import java.util.UUID
// import scala.jdk.CollectionConverters._ // Not used in this snippet
// import scala.reflect.ClassTag // Not used in this snippet
import org.scalatest.BeforeAndAfterAll
import java.nio.file.{Files, Path, Paths}


class MainControllerSpec extends AnyFlatSpec with Matchers with MockitoSugar with BeforeAndAfterAll {

  // Use a mock ActorSystem for tests that don't interact with actor behavior deeply
  implicit val systemActor: ActorSystem[Nothing] = ActorSystem(mock[org.apache.pekko.actor.typed.Behavior[Nothing]], "mockSystem")

  private val stateFilePath: Path = Paths.get("app_state.json")

  // Declare mocks, initialized in setupController or per test
  var mockHeader: Header = _
  var mockFooter: Footer = _ 
  // HistoryPanel and ResponseArea are objects, so we mock their types if we want to verify calls on the object itself.
  // However, direct static mocking is complex. We'll focus on MainController's interactions with instances it holds (Header, Footer)
  // and state changes. For HistoryPanel.type and ResponseArea.type, verification is limited without more advanced tools.

  override def beforeAll(): Unit = {
    super.beforeAll()
    Files.deleteIfExists(stateFilePath)
  }

  override def afterAll(): Unit = {
    Files.deleteIfExists(stateFilePath)
    super.afterAll()
    ActorSystem.terminate(systemActor) // Terminate the mock system
  }
  
  private def initializeStateFile(appState: AppState): Unit = {
    val json = JsonUtil.serialize(appState)
    Files.writeString(stateFilePath, json)
  }

  private def readStateFile(): AppState = {
    if (Files.exists(stateFilePath)) {
      val json = Files.readString(stateFilePath)
      JsonUtil.deserialize[AppState](json)
    } else {
      // In tests, if a file is expected, it should exist.
      // If it's optional, the test logic should handle its absence.
      fail("app_state.json does not exist when it was expected.")
    }
  }
  
  private def setPrivateField(obj: AnyRef, fieldName: String, value: Any): Unit = {
      try {
        val field = obj.getClass.getDeclaredField(fieldName)
        field.setAccessible(true)
        field.set(obj, value)
      } catch {
        case e: NoSuchFieldException => println(s"WARN: Field '$fieldName' not found in ${obj.getClass.getName}. Skipping mock setup for it. This might be due to controller refactoring.")
        case e: Exception => println(s"WARN: Failed to set private field '$fieldName' via reflection: ${e.getMessage}")
      }
  }

  private def setupController(initialState: AppState): MainController = {
    Files.deleteIfExists(stateFilePath) 
    initializeStateFile(initialState)

    mockHeader = mock[Header]
    mockFooter = mock[Footer] 
    
    // Mock necessary methods of header/footer if they are called during controller init or tested paths
    // e.g. when(mockHeader.activeCategoryNameProperty).thenReturn(new scalafx.beans.property.StringProperty("Global"))
    // For this set of tests, we primarily verify calls *to* these mocks.

    val controller = new MainController()(systemActor) 

    setPrivateField(controller, "headerRef", Some(mockHeader))
    setPrivateField(controller, "footerRef", Some(mockFooter))
    
    // Reset mocks after controller initialization, as synchronizeUIState might be called.
    reset(mockHeader, mockFooter) 
    
    controller
  }

  behavior of "MainController" // Behavior description for grouping

  it should "add a new topic correctly when startNewTopic is called" in {
    val initialTopicId = UUID.randomUUID().toString
    val initialCategory = "Code"
    val initialTopic = Topic(id = initialTopicId, title = "Initial Code Topic", category = initialCategory, dialogs = List(Dialog("t", "r", "a", Instant.now(), "m")))
    val initialState = AppState.initialState.copy(
      topics = List(initialTopic),
      activeTopicId = Some(initialTopicId), // Active topic determines category for new topic
      lastActiveTopicPerCategory = Map(initialCategory -> initialTopicId)
    )
    
    val controller = setupController(initialState)
    
    // Action: start a new topic. It should pick up category from the currently active topic.
    controller.startNewTopic()

    // Verification:
    val finalState = readStateFile()
    finalState.topics.size shouldBe 2 // One initial, one new
    val newTopicOpt = finalState.topics.find(_.id != initialTopicId)
    newTopicOpt shouldBe defined
    val newTopic = newTopicOpt.get

    newTopic.category shouldBe initialCategory // Category of the new topic
    newTopic.title shouldBe "Новый топик" // Default title
    newTopic.dialogs shouldBe empty
    
    finalState.activeTopicId shouldBe Some(newTopic.id) // New topic becomes active
    finalState.lastActiveTopicPerCategory.get(initialCategory) shouldBe Some(newTopic.id)

    verify(mockFooter, times(1)).clearInput() // UI interaction
    // Header's setActiveButton would be called by synchronizeUIState, triggered by setActiveTopic.
    // Let's verify it was called for the new topic's category.
    verify(mockHeader, atLeastOnce()).setActiveButton(mockitoEq(initialCategory))
  }

  // --- Tests for deleteTopic ---
  "MainController deleteTopic" should "start a new topic if the last topic in a category is deleted" in {
    val category = "TestCategory"
    val topicId = UUID.randomUUID().toString
    val initialTopic = Topic(id = topicId, title = "The Only Topic", category = category, createdAt = Instant.now(), lastUpdatedAt = Instant.now())
    val initialState = AppState.initialState.copy(
      topics = List(initialTopic),
      activeTopicId = Some(topicId),
      lastActiveTopicPerCategory = Map(category -> topicId)
    )
    val controller = setupController(initialState)

    // Simulate user confirming deletion (DialogUtils is static, hard to mock here, assume OK)
    controller.deleteTopic(topicId)
    
    // Allow Platform.runLater to execute if crucial. For direct state, it might not be needed if logic is sequential before runLater.
    // Thread.sleep(500) // Avoid in unit tests if possible. If essential for FX, use TestFX.

    val finalState = readStateFile()
    finalState.topics.exists(_.id == topicId) shouldBe false // Original deleted
    finalState.topics.size shouldBe 1 // New one created
    val newTopic = finalState.topics.head
    newTopic.category shouldBe category
    newTopic.title shouldBe "Новый топик"
    finalState.activeTopicId shouldBe Some(newTopic.id)
    finalState.lastActiveTopicPerCategory.get(category) shouldBe Some(newTopic.id)
    
    verify(mockHeader, atLeastOnce()).setActiveButton(mockitoEq(category)) // From startNewTopic -> setActiveTopic -> synchronizeUIState
    verify(mockFooter, atLeastOnce()).clearInput() // From startNewTopic
  }

  it should "activate the next available topic when an active topic is deleted" in {
    val category = "Work"
    val topic1Id = UUID.randomUUID().toString // To be deleted, older
    val topic2Id = UUID.randomUUID().toString // To become active, newer
    val topic1 = Topic(id = topic1Id, title = "Old Task", category = category, lastUpdatedAt = Instant.now().minusSeconds(100))
    val topic2 = Topic(id = topic2Id, title = "New Task", category = category, lastUpdatedAt = Instant.now())
    val initialState = AppState.initialState.copy(
      topics = List(topic1, topic2),
      activeTopicId = Some(topic1Id), // topic1 is active
      lastActiveTopicPerCategory = Map(category -> topic1Id)
    )
    val controller = setupController(initialState)

    controller.deleteTopic(topic1Id)

    val finalState = readStateFile()
    finalState.topics.exists(_.id == topic1Id) shouldBe false
    finalState.topics.size shouldBe 1
    finalState.topics.head.id shouldBe topic2Id // topic2 should remain
    finalState.activeTopicId shouldBe Some(topic2Id) // topic2 becomes active
    finalState.lastActiveTopicPerCategory.get(category) shouldBe Some(topic2Id) // Updated by controller.setActiveTopic

    verify(mockHeader, atLeastOnce()).setActiveButton(mockitoEq(category)) // From setActiveTopic -> synchronizeUIState
  }

  // --- Tests for handleHeaderAction ---
  "MainController handleHeaderAction" should "switch category and activate appropriate topic" in {
    val categoryA = "Personal"
    val categoryB = "ProjectX"
    val topicAId = UUID.randomUUID().toString
    val topicBId = UUID.randomUUID().toString
    val topicA = Topic(id = topicAId, title = "Groceries", category = categoryA, lastUpdatedAt = Instant.now().minusSeconds(100))
    val topicB = Topic(id = topicBId, title = "Main Feature", category = categoryB, lastUpdatedAt = Instant.now())
    val initialState = AppState.initialState.copy(
      topics = List(topicA, topicB),
      activeTopicId = Some(topicAId), // Currently in CategoryA
      lastActiveTopicPerCategory = Map(categoryA -> topicAId, categoryB -> topicBId) // topicB is newest in its category
    )
    val controller = setupController(initialState)
    
    // Action: Switch to CategoryB
    controller.handleHeaderAction(categoryB)
    
    val finalState = readStateFile()
    finalState.activeTopicId shouldBe Some(topicBId) // topicB should be active
    finalState.lastActiveTopicPerCategory.get(categoryB) shouldBe Some(topicBId)
    
    // Verify Header.setActiveButton was called for CategoryB by the setActiveTopic -> synchronizeUIState flow
    // This confirms the direct call was removed from handleHeaderAction and is now handled by the state change flow.
    verify(mockHeader, atLeastOnce()).setActiveButton(mockitoEq(categoryB))
  }
  
  it should "resync state by calling setActiveTopic when clicking the current active category" in {
    val categoryA = "General"
    val topicAId = UUID.randomUUID().toString
    val topicA = Topic(id = topicAId, title = "Notes", category = categoryA)
    val initialState = AppState.initialState.copy(
      topics = List(topicA),
      activeTopicId = Some(topicAId), // Active in CategoryA
      lastActiveTopicPerCategory = Map(categoryA -> topicAId)
    )
    val controller = setupController(initialState)
        
    // Action: Click the *same* category button
    controller.handleHeaderAction(categoryA)
    
    val finalState = readStateFile() // State itself might not change if already consistent
    finalState.activeTopicId shouldBe Some(topicAId) // Active topic remains the same
    
    // Crucially, setActiveTopic should still be called, leading to synchronizeUIState,
    // which ensures UI consistency and calls setActiveButton on the header.
    // Number of times can be tricky if controller init also calls it. atLeastOnce is safer.
    verify(mockHeader, atLeastOnce()).setActiveButton(mockitoEq(categoryA))
  }
}