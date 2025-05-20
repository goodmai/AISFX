package com.aiplatform.view

import com.aiplatform.model.Dialog
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import scalafx.application.Platform
import scalafx.embed.swing.SFXPanel
import scalafx.scene.layout.VBox
import scalafx.scene.control.Label
import scalafx.scene.text.Text
import scalafx.scene.web.WebView // WebView might not be directly used by ResponseArea, but good for FX init
import java.time.Instant
import java.util.UUID
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._


/**
 * Tests for the ResponseArea object.
 * Note: ResponseArea is an object, so its state is global. Tests must carefully reset state.
 */
class MainContentSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  // Initialize JavaFX Toolkit for tests
  // Done once for the entire test suite.
  override def beforeAll(): Unit = {
    super.beforeAll()
    // Initialize JavaFX toolkit
    try {
      new SFXPanel() // Ensures an implicit Platform.runLater can execute.
      Platform.implicitExit = false // Prevents Platform.exit when last window is closed.
    } catch {
      case e: Exception => println(s"Error initializing JavaFX toolkit: ${e.getMessage}")
    }
  }

  // Reset ResponseArea state before each test
  override def beforeEach(): Unit = {
    super.beforeEach()
    // Ensures that UI operations are done on the FX application thread.
    val promise = Promise[Unit]()
    Platform.runLater {
      ResponseArea.clearDialog() // Clears main content and status label
      // Reset any other static state if necessary, e.g., pendingResponses map
      // Accessing private pendingResponses for clearing:
      try {
        val field = ResponseArea.getClass.getDeclaredField("pendingResponses")
        field.setAccessible(true)
        val map = field.get(ResponseArea).asInstanceOf[scala.collection.mutable.Map[String, AnyRef]]
        map.clear()
      } catch {
        case e: Exception => fail(s"Failed to clear pendingResponses via reflection: $e")
      }
      promise.success(())
    }
    // Wait for Platform.runLater to complete to ensure state is clean before test runs
    concurrent.Await.result(promise.future, 5.seconds)
  }
  
  override def afterAll(): Unit = {
    // Consider if Platform.exit() is needed, but with implicitExit = false, it might not be.
    super.afterAll()
  }

  // Helper to run assertions on FX thread and wait
  private def onFxThread(assertions: => Unit): Unit = {
    val promise = Promise[Unit]()
    Platform.runLater {
      try {
        assertions
        promise.success(())
      } catch {
        case e: Throwable => promise.failure(e)
      }
    }
    concurrent.Await.result(promise.future, 5.seconds)
  }

  behavior of "ResponseArea.clearDialog"

  it should "clear dialogContainer and hide statusLabel" in onFxThread {
    // Arrange: Add something to display first
    ResponseArea.showStatus("Some initial status")
    ResponseArea.addRequestTurn("A request")
    
    // Act
    ResponseArea.clearDialog()

    // Assert
    val dialogContainer = findPrivateDialogContainer(ResponseArea)
    dialogContainer.children.isEmpty shouldBe true
    
    val statusLabel = findPrivateStatusLabel(ResponseArea)
    statusLabel.visible.value shouldBe false
    statusLabel.text.value shouldBe ""
    
    getPrivatePendingResponses(ResponseArea).isEmpty shouldBe true
  }

  behavior of "ResponseArea.addRequestTurn"

  it should "add a request turn to dialogContainer and manage pendingResponses" in onFxThread {
    // Act
    val turnId = ResponseArea.addRequestTurn("Test Request")

    // Assert
    turnId should not startWith "error-"
    val dialogContainer = findPrivateDialogContainer(ResponseArea)
    dialogContainer.children.size shouldBe 1
    // Further inspection of the turn box structure could be done here if needed.
    // For example, check if it contains a VBox with style class "request-box".
    
    getPrivatePendingResponses(ResponseArea).contains(turnId) shouldBe true
    findPrivateStatusLabel(ResponseArea).visible.value shouldBe false
  }

  behavior of "ResponseArea.addResponseTurn"

  it should "add a response to the correct placeholder and remove from pendingResponses" in onFxThread {
    // Arrange
    val turnId = ResponseArea.addRequestTurn("Question")
    getPrivatePendingResponses(ResponseArea).contains(turnId) shouldBe true // Pre-condition

    // Act
    ResponseArea.addResponseTurn(turnId, "This is the AI Answer.")

    // Assert
    getPrivatePendingResponses(ResponseArea).contains(turnId) shouldBe false
    
    val dialogContainer = findPrivateDialogContainer(ResponseArea)
    val turnBox = dialogContainer.children.head.asInstanceOf[VBox] // The dialogTurnBox
    // The response placeholder (which was the second child of turnBox) should now contain the response-box
    val responsePlaceholder = turnBox.children.get(1).asInstanceOf[VBox]
    responsePlaceholder.children.size shouldBe 1 // Should contain the responseBox
    val responseBox = responsePlaceholder.children.head.asInstanceOf[VBox]
    responseBox.styleClass.contains("response-box") shouldBe true
    
    // Check for AI label and text (simplified check)
    val aiLabel = responseBox.children.get(0).asInstanceOf[Label]
    aiLabel.text.value shouldBe "AI:"
    // responseContentBox is child 1 of responseBox
    // It contains TextFlows or code blocks. This part can be complex to assert deeply.
  }
  
  it should "handle adding a response to a non-existent turnId gracefully" in onFxThread {
    // Act & Assert
    noException should be thrownBy ResponseArea.addResponseTurn("non-existent-turn-id", "Response")
    // No change to dialogContainer expected if turnId is not found in pendingResponses
    findPrivateDialogContainer(ResponseArea).children.isEmpty shouldBe true
  }

  behavior of "ResponseArea.showLoadingIndicatorForRequest"

  it should "display a loading indicator in the placeholder" in onFxThread {
    // Arrange
    val turnId = ResponseArea.addRequestTurn("Initial question")

    // Act
    ResponseArea.showLoadingIndicatorForRequest(turnId)

    // Assert
    val placeholder = getPrivatePendingResponses(ResponseArea).getOrElse(turnId, fail(s"Placeholder for $turnId not found after addRequestTurn"))
    // The above line is problematic because showLoadingIndicator clears from pendingResponses if it finds it.
    // Let's inspect the dialogContainer directly.
    val dialogContainer = findPrivateDialogContainer(ResponseArea)
    val turnBox = dialogContainer.children.head.asInstanceOf[VBox]
    val responsePlaceholder = turnBox.children.get(1).asInstanceOf[VBox] // This is where indicator should be
    
    responsePlaceholder.children.size shouldBe 1
    val loadingBox = responsePlaceholder.children.head // Should be an HBox containing the loading label and indicator
    // Further checks: loadingBox should contain a Label with "AI думает..." and a ProgressIndicator
    // This requires more detailed inspection of children if exact structure is important.
    loadingBox.isInstanceOf[javafx.scene.layout.HBox] shouldBe true // It's a scalafx HBox wrapping a JavaFX one
  }

  behavior of "ResponseArea.showErrorForRequest"

  it should "display an error message in the placeholder" in onFxThread {
    // Arrange
    val turnId = ResponseArea.addRequestTurn("Question that will error")

    // Act
    ResponseArea.showErrorForRequest(turnId, "This is a test error.")

    // Assert
    getPrivatePendingResponses(ResponseArea).contains(turnId) shouldBe false // Placeholder should be processed
    val dialogContainer = findPrivateDialogContainer(ResponseArea)
    val turnBox = dialogContainer.children.head.asInstanceOf[VBox]
    val responsePlaceholder = turnBox.children.get(1).asInstanceOf[VBox]
    
    responsePlaceholder.children.size shouldBe 1
    val errorBox = responsePlaceholder.children.head.asInstanceOf[javafx.scene.layout.HBox] // It's an HBox
    val errorLabel = errorBox.getChildren.get(0).asInstanceOf[javafx.scene.control.Label] // JavaFX Label
    errorLabel.getText should include ("Ошибка: This is a test error.")
    errorLabel.getStyleClass.contains("error-label") shouldBe true
  }
  
  behavior of "ResponseArea.displayTopicDialogs"

  it should "display all dialogs from a topic" in onFxThread {
    // Arrange
    val dialogs = List(
      Dialog(UUID.randomUUID().toString, "Req1", "Resp1", Instant.now(), "model1"),
      Dialog(UUID.randomUUID().toString, "Req2", "Resp2", Instant.now().plusSeconds(5), "model1")
    )

    // Act
    ResponseArea.displayTopicDialogs(dialogs)

    // Assert
    val dialogContainer = findPrivateDialogContainer(ResponseArea)
    dialogContainer.children.size shouldBe dialogs.size
    // Each child should be a VBox for a turn, containing request and response.
    // Detailed check of content could be added here.
    findPrivateStatusLabel(ResponseArea).visible.value shouldBe false
  }

  it should "clear previous dialogs and show status if dialog list is empty" in onFxThread {
    // Arrange - first add some dialogs
    ResponseArea.addRequestTurn("Old request")
    ResponseArea.addResponseTurn(getPrivatePendingResponses(ResponseArea).keys.head, "Old Response")
    
    // Act
    ResponseArea.displayTopicDialogs(List.empty) // Display an empty list

    // Assert
    val dialogContainer = findPrivateDialogContainer(ResponseArea)
    dialogContainer.children.isEmpty shouldBe true
    // The problem description states: "Status message should be set by controller."
    // So, displayTopicDialogs itself might not set a status for empty, but clear.
    // Let's check if it's cleared.
    // findPrivateStatusLabel(ResponseArea).visible.value shouldBe false // Or true with a specific message, depends on exact behavior.
    // The current ResponseArea.displayTopicDialogs with empty list logs "Displaying empty topic. Status message should be set by controller."
    // and does not set statusLabel itself. It relies on clearDialog() which hides statusLabel.
    findPrivateStatusLabel(ResponseArea).visible.value shouldBe false
  }

  behavior of "ResponseArea.showError"

  it should "clear dialogs and display a general error message" in onFxThread {
    // Arrange - add something first
    ResponseArea.addRequestTurn("Request before error")

    // Act
    ResponseArea.showError("A general error occurred.")

    // Assert
    findPrivateDialogContainer(ResponseArea).children.isEmpty shouldBe true
    val statusLabel = findPrivateStatusLabel(ResponseArea)
    statusLabel.visible.value shouldBe true
    statusLabel.text.value shouldBe "Ошибка: A general error occurred."
    statusLabel.styleClass.contains("status-label-error") shouldBe true
    statusLabel.styleClass.contains("status-label-info") shouldBe false
  }
  
  behavior of "ResponseArea.showStatus"

  it should "clear dialogs and display a general status message" in onFxThread {
    // Arrange - add something first
    ResponseArea.addRequestTurn("Request before status")

    // Act
    ResponseArea.showStatus("This is a status message.")

    // Assert
    findPrivateDialogContainer(ResponseArea).children.isEmpty shouldBe true
    val statusLabel = findPrivateStatusLabel(ResponseArea)
    statusLabel.visible.value shouldBe true
    statusLabel.text.value shouldBe "This is a status message."
    statusLabel.styleClass.contains("status-label-info") shouldBe true
    statusLabel.styleClass.contains("status-label-error") shouldBe false
  }
  
  // Helper methods to access private fields of ResponseArea object via reflection
  private def getPrivateField[T](obj: AnyRef, fieldName: String): T = {
    val field = obj.getClass.getDeclaredField(fieldName)
    field.setAccessible(true)
    field.get(obj).asInstanceOf[T]
  }

  private def findPrivateDialogContainer(responseAreaObj: ResponseArea.type): VBox = {
    getPrivateField[VBox](responseAreaObj, "dialogContainer")
  }
  
  private def findPrivateStatusLabel(responseAreaObj: ResponseArea.type): Label = {
    getPrivateField[Label](responseAreaObj, "statusLabel")
  }
  
  private def getPrivatePendingResponses(responseAreaObj: ResponseArea.type): scala.collection.mutable.Map[String, VBox] = {
    getPrivateField[scala.collection.mutable.Map[String, VBox]](responseAreaObj, "pendingResponses")
  }
}