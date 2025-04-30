package com.aiplatform.app

import org.apache.pekko.actor.typed.ActorSystem
import scalafx.application.JFXApp3
import com.aiplatform.controller.MainController
import com.aiplatform.service.HistoryService
import scalafx.scene.Scene
import org.slf4j.LoggerFactory
import scalafx.stage.Stage // Убедитесь, что Stage импортирован

object MainApp extends JFXApp3 {

  private val logger = LoggerFactory.getLogger(getClass)
  private implicit val actorSystem: ActorSystem[HistoryService.Command] =
    ActorSystem(HistoryService(), "AI-Platform-System")

  // Создаем контроллер, передавая систему акторов
  private val mainController = MainController()(actorSystem) // Использует implicit actorSystem

  override def start(): Unit = {
    logger.info("MainApp starting...") // Используем info вместо started
    stage = new JFXApp3.PrimaryStage {
      title = "AI Platform"

      // --- ИСПРАВЛЕНИЕ: Передаем текущий stage (this) в createUI ---
      // Сначала создаем корневой узел, передавая stage
      val rootNode = mainController.createUI(this) // 'this' здесь это JFXApp3.PrimaryStage
      // --- ------------------------------------------------------- ---

      // Затем создаем сцену с этим узлом
      scene = new Scene(1280, 720) {
        root = rootNode
        // Можно подключить CSS глобально здесь, если нужно
        // stylesheets.add(getClass.getResource("/styles/main.css").toExternalForm)
      }
    }

    // Обработчик закрытия окна
    stage.setOnCloseRequest { _ => // Используем фигурные скобки для блока
      logger.info("Close request received.")
      mainController.saveState()
      mainController.shutdown() // Вызываем shutdown контроллера
      // Важно: Завершать работу ActorSystem после завершения всех операций
      // Можно добавить ожидание завершения Future из shutdown(), если он возвращает Future
      actorSystem.terminate()
      logger.info("Actor system terminated. Exiting.")
      // Platform.exit() // Не всегда нужно, JFXApp3 обрабатывает выход
    }
    logger.info("MainApp started successfully.")
  }

  // Метод stopApp для дополнительной логики завершения, если нужно
  override def stopApp(): Unit = {
    logger.info("stopApp called.")
    // Здесь можно добавить код, который должен выполниться *после* закрытия окна,
    // но до полного завершения JVM, если setOnCloseRequest недостаточно.
    // Важно: Не вызывайте здесь terminate() / shutdown() повторно, если они уже есть в setOnCloseRequest.
    super.stopApp() // Вызов метода суперкласса важен
  }
}