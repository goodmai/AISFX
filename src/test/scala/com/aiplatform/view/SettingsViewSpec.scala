// src/test/scala/com/aiplatform/view/SettingsViewSpec.scala
package com.aiplatform.view

import com.aiplatform.controller.MainController
import com.aiplatform.model.{ModelInfo, PromptPreset}
import com.aiplatform.service.HistoryService
import org.apache.pekko.actor.typed.ActorSystem
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import scalafx.application.Platform
import scalafx.embed.swing.SFXPanel
import scalafx.scene.Scene
import scalafx.scene.Parent
import scalafx.stage.Stage
import java.util.concurrent.{CountDownLatch, TimeUnit, TimeoutException}
import scalafx.scene.control.{ComboBox, TabPane, Tab, Label, ScrollPane}
import javafx.scene.layout.{GridPane, BorderPane}
import javafx.scene.Node
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.jdk.CollectionConverters._
import scala.util.{Try, Success, Failure}


class SettingsViewSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  new SFXPanel()
  Platform.implicitExit = false
  implicit val system: ActorSystem[HistoryService.Command] = ActorSystem(HistoryService(), "SettingsViewTestSystem")
  implicit val ec: ExecutionContext = system.executionContext

  // Вспомогательная функция runAndWaitSettingsView (без изменений)
  def runAndWaitSettingsView(initialSettings: CurrentSettings)(block: (SettingsView, Parent) => Unit): Unit = {
    val latch = new CountDownLatch(1)
    Platform.runLater {
      val mockController = mock[MainController]
      val settingsView = new SettingsView(null, mockController, initialSettings)
      val rootNode = settingsView.createLayout()
      try {
        block(settingsView, rootNode)
      } catch {
        case e: Throwable =>
          println(s"Error during runAndWait block execution: ${e.getMessage}")
          e.printStackTrace()
          fail("Exception during Platform.runLater block", e)
      } finally {
        latch.countDown()
      }
    }
    if (!latch.await(10, TimeUnit.SECONDS)) {
      fail("Timeout waiting for Platform.runLater")
    }
  }

  // Вспомогательный метод для поиска узлов (без изменений)
  def findNode[T <: javafx.scene.Node](root: javafx.scene.Node, selector: String)(implicit ct: ClassTag[T]): T = {
    val node = root.lookup(selector)
    if (node == null) {
      fail(s"Node with selector '$selector' not found within the provided root ${root.getClass.getName}.") // Добавил тип рута в сообщение
    }
    node match {
      case t if ct.runtimeClass.isInstance(t) => t.asInstanceOf[T]
      case other => fail(s"Node with selector '$selector' found, but has unexpected type: ${other.getClass.getName}. Expected: ${ct.runtimeClass.getName}")
    }
  }
  def findNode[T <: javafx.scene.Node](root: Parent, selector: String)(implicit ct: ClassTag[T]): T = { findNode(root.delegate, selector) }

  // Вспомогательная функция waitForCondition (без изменений)
  def waitForCondition(condition: () => Boolean, timeoutMillis: Long = 5000): Unit = { /*...*/ } // Полный код опущен для краткости


  // --- Тесты ---
  "SettingsView - Mappings Tab" should "display the correct preset assigned to a button" in {
    // 1. Подготовка тестовых данных
    val model1 = ModelInfo("model-1", "Model One")
    val presetDefault = PromptPreset("Default Research", "Research: {{INPUT}}", isDefault = true)
    val presetCustom1 = PromptPreset("My Coding Preset", "Code: {{INPUT}}")
    val presetCustom2 = PromptPreset("My Review Preset", "Review: {{INPUT}}")
    val initialSettings = CurrentSettings(
      apiKey = "test-key", model = model1.name, fontFamily = "System", fontSize = 12,
      availableModels = List(model1),
      buttonMappings = Map(
        "Research" -> presetDefault.name,
        "Code" -> presetCustom1.name,
        "Review" -> "NonExistentPreset"
      ),
      defaultPresets = List(presetDefault), customPresets = List(presetCustom1, presetCustom2)
    )

    var selectedPresetForCode: Option[PromptPreset] = None
    var selectedPresetForResearch: Option[PromptPreset] = None
    var selectedPresetForReview: Option[PromptPreset] = None
    var selectedPresetForTest: Option[PromptPreset] = None

    // 2. Запуск UI и выполнение проверок
    runAndWaitSettingsView(initialSettings) { (settingsView, root) =>
      val tabPane = findNode[javafx.scene.control.TabPane](root, ".tab-pane")
      tabPane.getSelectionModel.select(2) // Вкладка "Назначение"
      Platform.requestNextPulse()
      Thread.sleep(100)

      val mappingTab: javafx.scene.control.Tab = tabPane.getTabs.get(2)
      val mappingTabContentNode: javafx.scene.Node = mappingTab.getContent
      if (mappingTabContentNode == null) { fail("Content of the 'Назначение' tab is null.") }

      // Находим GridPane по ID внутри содержимого вкладки
      val gridPaneOpt = Try(findNode[javafx.scene.layout.GridPane](mappingTabContentNode, "#mappingGrid")).toOption

      gridPaneOpt match {
        case Some(gridPane) =>
          // Явно вызываем populateMappingGrid
          settingsView.populateMappingGrid()
          // Ждем заполнения GridPane ПОСЛЕ вызова populate
          waitForCondition(() => !gridPane.getChildren.isEmpty)
          println(s"Debug: Wait finished. GridPane #mappingGrid now has ${gridPane.getChildren.size} children.")

          // Проверяем, что дети действительно есть
          if (gridPane.getChildren.isEmpty) {
            fail("GridPane remained empty even after waiting post-populateMappingGrid call.")
          }

          // --- ИЗМЕНЕНИЕ: Возврат к поиску по индексам + детальная отладка ---
          def findComboForButton(buttonName: String): Option[javafx.scene.control.ComboBox[PromptPreset]] = {
            println(s"\nDebug: Searching for Label '$buttonName:' using index logic")
            val labelOpt = gridPane.getChildren.asScala.find { node =>
              node.isInstanceOf[javafx.scene.control.Label] &&
                node.asInstanceOf[javafx.scene.control.Label].getText == s"$buttonName:"
            }

            labelOpt.flatMap { label =>
              val labelRowIndex: Int = Option(javafx.scene.layout.GridPane.getRowIndex(label)).map(_.toInt).getOrElse(0)
              val labelColIndex: Int = Option(javafx.scene.layout.GridPane.getColumnIndex(label)).map(_.toInt).getOrElse(0)
              val targetColIndex = labelColIndex + 1
              println(s"Debug: Found Label '$buttonName:' with calculated indices (row:$labelRowIndex, col:$labelColIndex). Target col: $targetColIndex")

              println(s"Debug: Searching for ComboBox at (row:$labelRowIndex, col:$targetColIndex) among ${gridPane.getChildren.size} children...")
              val foundCombo = gridPane.getChildren.asScala.find { node =>
                val nodeRowIndex: Int = Option(javafx.scene.layout.GridPane.getRowIndex(node)).map(_.toInt).getOrElse(0)
                val nodeColIndex: Int = Option(javafx.scene.layout.GridPane.getColumnIndex(node)).map(_.toInt).getOrElse(0)
                val isCombo = node.isInstanceOf[javafx.scene.control.ComboBox[?]]
                val rowMatch = nodeRowIndex == labelRowIndex
                val colMatch = nodeColIndex == targetColIndex

                // Печатаем информацию о каждом узле при поиске ComboBox
                println(f"  Checking node: ${node.getClass.getSimpleName}%-20s | isCombo: $isCombo%-5s | NodeIdx: (row:$nodeRowIndex%d, col:$nodeColIndex%d) | TargetIdx: (row:$labelRowIndex%d, col:$targetColIndex%d) | rowMatch: $rowMatch%-5s | colMatch: $colMatch%-5s")

                rowMatch && colMatch && isCombo
              }.map(_.asInstanceOf[javafx.scene.control.ComboBox[PromptPreset]])

              foundCombo match {
                case Some(_) => println(s"Debug: FOUND ComboBox for '$buttonName:' using index logic.")
                case None    => println(s"Debug: ComboBox for '$buttonName:' NOT FOUND using index logic.")
              }
              foundCombo
            }
          }
          // --- ------------------------------------------------------------ ---

          // Получаем выбранные значения
          selectedPresetForCode = findComboForButton("Code").map(_.getSelectionModel.getSelectedItem)
          selectedPresetForResearch = findComboForButton("Research").map(_.getSelectionModel.getSelectedItem)
          selectedPresetForReview = findComboForButton("Review").map(_.getSelectionModel.getSelectedItem)
          selectedPresetForTest = findComboForButton("Test").map(_.getSelectionModel.getSelectedItem)

        case None => fail(s"Could not find the mapping GridPane with ID '#mappingGrid'.")
      }
    }

    // 3. Проверки (Assertions)
    selectedPresetForCode should not be empty
    selectedPresetForCode.get.name shouldBe presetCustom1.name
    selectedPresetForResearch should not be empty
    selectedPresetForResearch.get.name shouldBe presetDefault.name
    selectedPresetForReview should (be (empty) or be (Some(null)))
    selectedPresetForTest should (be (empty) or be (Some(null)))
  }
}