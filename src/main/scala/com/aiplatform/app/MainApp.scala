package com.aiplatform.app

import org.apache.pekko.actor.typed.ActorSystem
import scalafx.application.JFXApp3
import com.aiplatform.controller.MainController
import com.aiplatform.service.HistoryService
import scalafx.scene.Scene

object MainApp extends JFXApp3 {
  // Define the actor system first
  private implicit val actorSystem: ActorSystem[HistoryService.Command] =
    ActorSystem(HistoryService(), "AI-Platform-System")

  // Explicitly pass the system when creating the controller
  private val mainController = MainController()(actorSystem) // Pass it here

  override def start(): Unit = {
    stage = new JFXApp3.PrimaryStage {
      title = "AI Platform"
      scene = new Scene(1280, 720) {
        root = mainController.createUI()
      }
    }

    stage.setOnCloseRequest(_ => {
      mainController.saveState()
      actorSystem.terminate()
    })
  }
}
    