package com.aiplatform.view

import com.aiplatform.controller.MainController
import com.aiplatform.service.HistoryService
import org.apache.pekko.actor.typed.ActorSystem
import org.scalatest.BeforeAndAfterAll // Use BeforeAndAfterAll for manual setup/teardown
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scalafx.application.Platform
import scalafx.scene.Scene
import scalafx.stage.Stage
import scala.concurrent.Await
import scala.concurrent.duration._

// Use BeforeAndAfterAll instead of ActorSystemLifecycle
class MainContentSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  // Initialize JavaFX toolkit
  Platform.startup(() => {})

  // Declare the ActorSystem, will be initialized in beforeAll
  var testSystem: ActorSystem[HistoryService.Command] = _

  // Manually create the ActorSystem before tests run
  override def beforeAll(): Unit = {
    testSystem = ActorSystem(HistoryService(), "TestSystem")
    super.beforeAll() // Ensure parent's beforeAll is called if needed
  }

  // Manually terminate the ActorSystem after tests run
  override def afterAll(): Unit = {
    try {
      testSystem.terminate()
      Await.result(testSystem.whenTerminated, 5.seconds) // Wait for termination
    } finally {
      Platform.exit() // Clean up JavaFX
      super.afterAll() // Ensure parent's afterAll is called
    }
  }

  "MainContent" should "contain all header buttons" in {
    // Make the system implicitly available within the test scope
    implicit val system: ActorSystem[HistoryService.Command] = testSystem

    Platform.runLater {
      val stage = new Stage()
      // The implicit system will be picked up here
      val controller = new MainController()
      val mainContent = new MainContent(controller)

      val scene = new Scene {
        root = mainContent.create()
      }
      stage.setScene(scene)
      stage.show()

      val buttonNames = List("Research", "Code", "Review", "Test", "Deploy", "Audio", "Stream", "Exam", "Integrations", "Settings")
      val headerButtons = mainContent.create().lookupAll(".button").toArray.map(_.asInstanceOf[javafx.scene.control.Button].getText).toList

      buttonNames.foreach { name =>
        headerButtons should contain(name)
      }

      stage.close()
    }
    // Give Platform.runLater some time to execute
    Thread.sleep(500) // Adjust sleep time if necessary
  }
}