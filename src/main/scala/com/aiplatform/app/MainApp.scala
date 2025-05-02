// src/main/scala/com/aiplatform/app/MainApp.scala
package com.aiplatform.app

import org.apache.pekko.actor.typed.ActorSystem
import scalafx.application.{JFXApp3, Platform}
import com.aiplatform.controller.MainController
import com.aiplatform.service.HistoryService
import scalafx.scene.{Parent, Scene}
import org.slf4j.LoggerFactory
import scalafx.stage.{Stage, StageStyle}
import scalafx.scene.control.{Alert, Label, TextArea} // <<< Добавлены Label, TextArea
import scalafx.scene.layout.{GridPane, Priority}   // <<< Добавлены GridPane, Priority
import scala.util.{Try, Success, Failure}
import scala.util.control.NonFatal
import java.io.{PrintWriter, StringWriter} // <<< Добавлены для StackTrace

/**
 * Главный объект приложения, точка входа.
 */
object MainApp extends JFXApp3 {

  private val logger = LoggerFactory.getLogger(getClass)
  logger.info("MainApp object initializing...")

  // --- Инициализация ActorSystem ---
  private implicit lazy val actorSystem: ActorSystem[HistoryService.Command] = {
    logger.info("Initializing ActorSystem...")
    val system = ActorSystem(HistoryService(), "AI-Platform-System")
    logger.info("ActorSystem initialized.")
    system
  }

  // --- Инициализация MainController ---
  private lazy val mainControllerTry: Try[MainController] = Try {
    logger.debug("Attempting to initialize MainController...")
    val controller = MainController()(actorSystem)
    logger.info("MainController initialized successfully instance.")
    controller
  }

  private var mainControllerInstance: Option[MainController] = None

  /**
   * Метод, вызываемый JavaFX при запуске приложения.
   */
  override def start(): Unit = {
    logger.info(">>> MainApp.start() entered on JavaFX Application Thread: {}", Thread.currentThread().getName)

    mainControllerTry match {
      case Success(controller) =>
        mainControllerInstance = Some(controller)
        logger.info("MainController instance obtained.")

        logger.debug("Creating Primary Stage...")
        stage = new JFXApp3.PrimaryStage {
          initStyle(StageStyle.Decorated)
          this.title = "AI Platform" // Устанавливаем заголовок окна Stage
          minWidth = 800
          minHeight = 600
          logger.info("Primary Stage created.")

          logger.debug("Calling controller.createUI()...")
          val rootNodeResult: Try[Parent] = Try(controller.createUI(this)) // 'this' здесь это Stage
          logger.debug("controller.createUI() returned.")

          rootNodeResult match {
            case Success(rootNode) =>
              logger.info("UI Root Node created successfully.")
              logger.debug("Creating Scene...")
              scene = new Scene(1280, 768) {
                root = rootNode // Устанавливаем корневой узел
                // Загружаем стили
                try {
                  val cssUrl = getClass.getResource("/styles/main.css")
                  if (cssUrl != null) {
                    stylesheets.add(cssUrl.toExternalForm)
                    logger.info("Stylesheet /styles/main.css added to scene.")
                  } else {
                    logger.error("Stylesheet /styles/main.css not found!")
                  }
                } catch {
                  case NonFatal(e) =>
                    logger.error("Error adding stylesheet to scene!", e)
                }
              }
              logger.info("Scene created and assigned to stage.")

              // === Вызываем начальную настройку ПОСЛЕ создания сцены ===
              Platform.runLater {
                logger.info("Scheduling initial UI setup (fonts + sync) via Platform.runLater...")
                Try(controller.performInitialUISetup()).recover { // Оборачиваем в Try
                  case e: Throwable => logger.error("Exception during performInitialUISetup!", e)
                }
                logger.info("Initial UI setup scheduled.")
              }
            // ===========================================================

            case Failure(uiError) =>
              // Критическая ошибка при создании UI
              logger.error("Failed to create UI Root Node!", uiError)
              showCriticalErrorAndExit(
                alertTitle = "Критическая ошибка UI",
                alertHeader = "Не удалось инициализировать корневой узел интерфейса.",
                exception = Some(uiError),
                ownerWindowOpt = Some(this) // 'this' здесь Stage
              )
              // Устанавливаем пустую сцену, чтобы избежать дальнейших ошибок FX
              scene = new Scene(300, 100) { root = new scalafx.scene.layout.Pane() }
          }
        } // Конец создания Stage

        // Устанавливаем обработчик закрытия окна
        stage.onCloseRequest = event => {
          logger.info(">>> Application close request received.")
          mainControllerInstance.foreach { ctrl =>
            logger.debug("Shutting down controller...")
            Try(ctrl.shutdown()).recover { case NonFatal(e) => logger.error("Error during controller shutdown:", e) }
            logger.debug("Controller shutdown requested.")
          }
          if (actorSystem != null && !actorSystem.whenTerminated.isCompleted) {
            logger.info("Terminating ActorSystem...")
            actorSystem.terminate()
            logger.info("ActorSystem termination initiated.")
          } else { logger.warn("ActorSystem was null or already terminated on close request.") }
          logger.info("Proceeding with application exit handlers.")
        }
        logger.info("Primary Stage onCloseRequest handler set.")
        logger.info(">>> MainApp.start() completed initialization (Stage show pending).")

      case Failure(controllerError) =>
        // Критическая ошибка при инициализации контроллера
        logger.error("Failed to initialize MainController!", controllerError)
        showCriticalErrorAndExit(
          alertTitle = "Критическая ошибка Запуска",
          alertHeader = "Не удалось инициализировать основной контроллер приложения.",
          exception = Some(controllerError),
          ownerWindowOpt = None // Stage еще не создан
        )
    }
    logger.info(">>> MainApp.start() exiting (FX platform will now show stage).")
  } // Конец start()

  /**
   * Метод, вызываемый JavaFX для остановки приложения.
   */
  override def stopApp(): Unit = {
    logger.info(">>> MainApp.stopApp() called.")
    if (actorSystem != null && !actorSystem.whenTerminated.isCompleted) {
      logger.warn("stopApp(): ActorSystem termination might not be complete yet.")
    }
    super.stopApp() // Вызов родительского метода
    logger.info(">>> MainApp.stopApp() finished.")
  }

  /**
   * Отображает диалог с критической ошибкой и завершает работу приложения.
   */
  private def showCriticalErrorAndExit( // Параметры переименованы
                                        alertTitle: String,
                                        alertHeader: String,
                                        exception: Option[Throwable],
                                        ownerWindowOpt: Option[Stage]
                                      ): Unit = {
    val errorMessage = alertHeader + exception.map(e => s"\nПричина: ${e.getClass.getName} - ${e.getMessage}").getOrElse("")
    logger.error(s"CRITICAL ERROR: $errorMessage", exception.getOrElse(new RuntimeException("Unknown critical error")))

    // Убедимся, что выполняем в FX потоке
    if (Platform.isFxApplicationThread) {
      showErrorAlertAndExitPlatform(alertTitle, alertHeader, exception, ownerWindowOpt)
    } else {
      Platform.runLater {
        showErrorAlertAndExitPlatform(alertTitle, alertHeader, exception, ownerWindowOpt)
      }
    }
  }

  /**
   * Вспомогательный метод для отображения Alert и выхода (уже в FX потоке).
   * Теперь включает отображение StackTrace.
   */
  private def showErrorAlertAndExitPlatform( // Параметры переименованы
                                             alertTitle: String,
                                             alertHeader: String,
                                             exception: Option[Throwable],
                                             ownerWindowOpt: Option[Stage]
                                           ): Unit = {
    try {
      // Создаем Alert
      val alert = new Alert(Alert.AlertType.Error) {
        initStyle(StageStyle.Utility)
        ownerWindowOpt.foreach(ownerStage => initOwner(ownerStage.delegate))
        this.title = alertTitle // Устанавливаем заголовок окна Alert
        headerText = alertHeader // Устанавливаем текст вверху Alert
        contentText = exception.map(e => s"${e.getClass.getName}: ${e.getMessage}").getOrElse("Нет деталей.")

        // --- ДОБАВЛЕНО: Отображение StackTrace ---
        exception.foreach { t =>
          // Форматируем StackTrace в строку
          val sw = new StringWriter()
          t.printStackTrace(new PrintWriter(sw)) // Пишем стектрейс в StringWriter
          val stackTraceStr = sw.toString

          // Создаем TextArea для отображения стектрейса
          val textArea = new TextArea {
            text = stackTraceStr
            editable = false // Запрещаем редактирование
            wrapText = true  // Переносим строки
            maxWidth = Double.MaxValue
            maxHeight = Double.MaxValue
            prefRowCount = 15 // Задаем предпочтительное количество строк
            prefColumnCount = 80 // Задаем предпочтительную ширину
            // Растягиваем TextArea в GridPane
            GridPane.setVgrow(this, Priority.Always)
            GridPane.setHgrow(this, Priority.Always)
          }

          // Создаем GridPane как разворачиваемую панель
          val expandableContent = new GridPane {
            maxWidth = Double.MaxValue
            // Добавляем метку и TextArea
            add(new Label("Подробности (Stack Trace):"), 0, 0)
            add(textArea, 0, 1)
          }

          // Устанавливаем созданный GridPane как expandable content
          // Обращаемся к dialogPane через 'delegate' для JavaFX объекта
          this.delegate.getDialogPane.setExpandableContent(expandableContent)
          // Устанавливаем свернутое состояние через 'delegate'
          this.delegate.getDialogPane.setExpanded(false) // Используем JavaFX метод setExpanded
        }
        // --- КОНЕЦ ДОБАВЛЕНИЯ StackTrace ---
      } // Конец настройки Alert

      alert.showAndWait() // Показываем и ждем закрытия Alert
    } catch {
      case NonFatal(alertEx) => logger.error("Failed to show critical error alert!", alertEx)
    } finally {
      logger.info("Exiting application due to critical error...")
      Platform.exit() // Корректное завершение JavaFX приложения
      System.exit(1) // Принудительное завершение JVM
    }
  }

}