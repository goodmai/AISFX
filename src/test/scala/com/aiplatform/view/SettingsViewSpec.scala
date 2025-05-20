// src/test/scala/com/aiplatform/view/SettingsViewSpec.scala
package com.aiplatform.view

import com.aiplatform.controller.MainController
import com.aiplatform.model.{ModelInfo, PromptPreset}
// Removed HistoryService and ActorSystem as they are not directly used by SettingsView itself.
// import com.aiplatform.service.HistoryService
// import org.apache.pekko.actor.typed.ActorSystem
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.{times, verify, reset => mockReset} // Specific import for reset
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach} // Added BeforeAndAfterEach

import scalafx.application.Platform
import scalafx.embed.swing.SFXPanel
import scalafx.scene.Parent
import scalafx.scene.control.{Button => SFXButton, ComboBox => SFXComboBox, ListView => SFXListView, PasswordField => SFXPasswordField, TabPane => SFXTabPane, TextArea => SFXTextArea, TextField => SFXTextField, Spinner => SFXSpinner}
import javafx.scene.control.{ButtonType => JFXButtonType} // For DialogUtils mocking (if used)

import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.reflect.ClassTag
import scala.jdk.CollectionConverters._
import scala.util.{Success, Try}


class SettingsViewSpec extends AnyFlatSpec with Matchers with MockitoSugar with BeforeAndAfterAll with BeforeAndAfterEach {

  // Initialize JavaFX Toolkit once
  override def beforeAll(): Unit = {
    super.beforeAll()
    new SFXPanel() // Ensures an implicit Platform.runLater can execute.
    Platform.implicitExit = false
  }
  
  // No specific afterAll needed for FX if implicitExit = false
  
  var mockMainController: MainController = _

  // Reset mocks before each test
  override def beforeEach(): Unit = {
    super.beforeEach()
    mockMainController = mock[MainController]
  }

  // Helper to run a block of code on the JavaFX thread and wait for it
  private def onFxThread(block: => Unit): Unit = {
    val latch = new CountDownLatch(1)
    Platform.runLater {
      try {
        block
      } catch {
        case e: Throwable =>
          e.printStackTrace()
          fail(s"Exception on JavaFX thread: ${e.getMessage}", e)
      } finally {
        latch.countDown()
      }
    }
    if (!latch.await(10, TimeUnit.SECONDS)) { // Increased timeout for UI operations
      fail("Timeout waiting for Platform.runLater to execute.")
    }
  }
  
  // Enhanced helper to create SettingsView, execute a block, and handle its stage
  private def testSettingsView(initialSettings: CurrentSettings)(testCode: (SettingsView, Parent, Stage) => Unit): Unit = {
    onFxThread {
      // SettingsView constructor expects a Stage for owner, can be null for tests if handled by SettingsView.
      // For showAndWait(), it creates its own dialogStage.
      val settingsView = new SettingsView(null, mockMainController, initialSettings)
      
      // The actual layout root is created internally when dialogStage.scene.root is called.
      // To test display, we need to call showAndWait() or similar to trigger scene creation.
      // However, showAndWait() blocks. So, we access parts of the layout for testing.
      // For now, let's assume createLayout is accessible or called by showAndWait.
      // The test will interact with settingsView and then call its methods that would trigger controller calls.
      
      // We need a way to get the root node for findNode. SettingsView creates its own stage and scene.
      // Let's call show() and then immediately hide() for testing internal components,
      // or call methods on settingsView that don't require the stage to be showing long.
      // For simplicity, we'll get the layout and then simulate button clicks by calling handlers.
      
      val internalDialogStage = getPrivateUiField[Stage](settingsView, "dialogStage")
      val rootNode = internalDialogStage.scene.value.root.value // Get the root from the internal stage
      
      try {
        testCode(settingsView, rootNode, internalDialogStage) // Pass stage for context if needed
      } finally {
        if (internalDialogStage.showing.value) {
          internalDialogStage.close() // Ensure stage is closed after test
        }
      }
    }
  }
  
  // Reflection helper to access private fields (UI components)
  private def getPrivateUiField[T](instance: AnyRef, fieldName: String)(implicit ct: ClassTag[T]): T = {
    try {
      val field = instance.getClass.getDeclaredField(fieldName)
      field.setAccessible(true)
      field.get(instance) match {
        case t if ct.runtimeClass.isInstance(t) => t.asInstanceOf[T]
        // Handle Option fields that might wrap the component
        case Some(t) if ct.runtimeClass.isInstance(t) => t.asInstanceOf[T]
        case None => fail(s"Field '$fieldName' is None. Expected Some[${ct.runtimeClass.getName}]")
        case other => fail(s"Field '$fieldName' has unexpected type: ${Option(other).map(_.getClass.getName).getOrElse("null")}. Expected: ${ct.runtimeClass.getName}")
      }
    } catch {
      case e: NoSuchFieldException => fail(s"Field '$fieldName' not found in ${instance.getClass.getName}.", e)
      case e: Exception => fail(s"Failed to get private field '$fieldName' via reflection.", e)
    }
  }
  
  // Helper for finding nodes in the scene graph (from original test, adapted)
  private def findNode[T <: javafx.scene.Node](root: javafx.scene.Node, selector: String)(implicit ct: ClassTag[T]): T = {
    val node = root.lookup(selector)
    if (node == null) {
      fail(s"Node with selector '$selector' not found within root ${root.getClass.getName}.")
    }
    node match {
      case t if ct.runtimeClass.isInstance(t) => t.asInstanceOf[T]
      case other => fail(s"Node with selector '$selector' is type ${other.getClass.getName}, expected ${ct.runtimeClass.getName}.")
    }
  }
  // Overload for Parent
  private def findNode[T <: javafx.scene.Node](root: Parent, selector: String)(implicit ct: ClassTag[T]): T = findNode(root.delegate, selector)

  // Dummy waitForCondition (implementation was omitted, not strictly needed if onFxThread handles timing)
  private def waitForCondition(condition: () => Boolean, timeoutMillis: Long = 2000, intervalMillis: Long = 100): Unit = {
    val endTime = System.currentTimeMillis() + timeoutMillis
    while (!condition() && System.currentTimeMillis() < endTime) {
      Thread.sleep(intervalMillis)
      Platform.requestNextPulse() // Keep UI responsive if condition depends on UI updates
    }
    if (!condition()) {
      // fail(s"Timeout waiting for condition after ${timeoutMillis}ms.")
      println(s"WARN: Timeout waiting for condition after ${timeoutMillis}ms. Test might proceed with stale UI.")
    }
  }

  val commonInitialSettings: CurrentSettings = CurrentSettings(
    apiKey = "test-key",
    model = "model-1",
    availableModels = List(ModelInfo("model-1", "Model One"), ModelInfo("model-2", "Model Two")),
    buttonMappings = Map("Research" -> "Default Research", "Code" -> "My Coding Preset"),
    defaultPresets = List(PromptPreset("Default Research", "Research: {{INPUT}}", isDefault = true)),
    customPresets = List(PromptPreset("My Coding Preset", "Code: {{INPUT}}"))
  )

  behavior of "SettingsView - General Tab"

  it should "display initial general settings correctly" in {
    testSettingsView(commonInitialSettings) { (settingsView, rootNode, stage) =>
      // Arrange: UI elements are obtained via reflection
      val apiKeyField = getPrivateUiField[SFXPasswordField](settingsView, "apiKeyField")
      val modelComboBox = getPrivateUiField[SFXComboBox[ModelInfo]](settingsView, "globalModelComboBox")
      
      // Act: (Display is part of setup)

      // Assert
      apiKeyField.text.value shouldBe commonInitialSettings.apiKey
      modelComboBox.selectionModel.value.selectedItem.value.name shouldBe commonInitialSettings.model
    }
  }

  it should "call MainController to update API key and global model when Save All is clicked" in {
    val newApiKey = "new-api-key-123"
    val newModel = commonInitialSettings.availableModels.last // "model-2"
    
    testSettingsView(commonInitialSettings) { (settingsView, rootNode, stage) =>
      // Arrange: Get UI elements and simulate changes
      val apiKeyField = getPrivateUiField[SFXPasswordField](settingsView, "apiKeyField")
      val modelComboBox = getPrivateUiField[SFXComboBox[ModelInfo]](settingsView, "globalModelComboBox")
      val saveAllButton = getPrivateUiField[SFXButton](settingsView, "saveAllButton")

      onFxThread { // Ensure UI manipulations are on FX thread
        apiKeyField.text = newApiKey
        modelComboBox.selectionModel.value.select(newModel)
      }
      
      // Act
      onFxThread { // Simulate button click on FX thread
         // Directly call handler as button.fire() can be complex in test env
        callPrivateMethod(settingsView, "handleSaveAllSettings")
      }

      // Assert
      verify(mockMainController, times(1)).updateApiKey(mockitoEq(newApiKey))
      verify(mockMainController, times(1)).updateGlobalAIModel(mockitoEq(newModel.name))
      // Also verify button mappings are updated, even if not changed in this test
      verify(mockMainController, times(1)).updateButtonMappings(mockitoEq(commonInitialSettings.buttonMappings))
    }
  }
  
  behavior of "SettingsView - Presets Tab"

  it should "display initial presets correctly and populate editor on selection" in {
    testSettingsView(commonInitialSettings) { (settingsView, rootNode, stage) =>
      // Arrange
      val defaultListView = getPrivateUiField[SFXListView[PromptPreset]](settingsView, "defaultPresetsListView")
      val customListView = getPrivateUiField[SFXListView[PromptPreset]](settingsView, "customPresetsListView")
      val presetNameField = getPrivateUiField[SFXTextField](settingsView, "presetNameField")
      val presetPromptArea = getPrivateUiField[SFXTextArea](settingsView, "presetPromptArea")
      val presetEditorPane = getPrivateUiField[VBox](settingsView, "presetEditorPane")
      
      callPrivateMethod(settingsView, "setupPresetSelectionHandling") // Ensure listeners are active

      // Assert initial display
      defaultListView.items.value.size shouldBe commonInitialSettings.defaultPresets.size
      customListView.items.value.size shouldBe commonInitialSettings.customPresets.size
      defaultListView.items.value.head.name shouldBe commonInitialSettings.defaultPresets.head.name
      customListView.items.value.head.name shouldBe commonInitialSettings.customPresets.head.name
      presetEditorPane.disable.value shouldBe true // Editor disabled initially

      // Act: Select a custom preset
      onFxThread {
        customListView.selectionModel.value.select(0) // Select the first custom preset
      }
      waitForCondition(() => !presetEditorPane.disable.value) // Wait for editor to enable
      
      // Assert: Editor populated
      presetNameField.text.value shouldBe commonInitialSettings.customPresets.head.name
      presetPromptArea.text.value shouldBe commonInitialSettings.customPresets.head.prompt
      presetNameField.editable.value shouldBe true // Custom preset name is editable
      presetEditorPane.disable.value shouldBe false
    }
  }

  it should "allow creating and saving a new custom preset" in {
    val newPresetName = "My Test Preset"
    val newPresetPrompt = "This is a test prompt."
    
    testSettingsView(commonInitialSettings) { (settingsView, rootNode, stage) =>
      // Arrange
      val newPresetButton = getPrivateUiField[SFXButton](settingsView, "newPresetButton")
      val savePresetButton = getPrivateUiField[SFXButton](settingsView, "savePresetButton")
      val presetNameField = getPrivateUiField[SFXTextField](settingsView, "presetNameField")
      val presetPromptArea = getPrivateUiField[SFXTextArea](settingsView, "presetPromptArea")
      val customListView = getPrivateUiField[SFXListView[PromptPreset]](settingsView, "customPresetsListView")
      
      callPrivateMethod(settingsView, "setupPresetSelectionHandling")
      when(mockMainController.saveCustomPreset(any[PromptPreset])).thenReturn(Success(())) // Mock successful save

      // Act: Click "New", fill details, click "Save Preset"
      onFxThread { callPrivateMethod(settingsView, "handleNewPreset") } // newPresetButton.fire() or call handler
      
      onFxThread {
        presetNameField.text = newPresetName
        presetPromptArea.text = newPresetPrompt
      }
      onFxThread { callPrivateMethod(settingsView, "handleSavePreset") } // savePresetButton.fire() or call handler

      // Assert
      val captor = ArgumentCaptor.forClass(classOf[PromptPreset])
      verify(mockMainController, times(1)).saveCustomPreset(captor.capture())
      val savedPreset = captor.getValue
      savedPreset.name shouldBe newPresetName
      savedPreset.prompt shouldBe newPresetPrompt
      savedPreset.isDefault shouldBe false
      
      // Check if it appears in the custom presets list view (indirectly, by checking buffer)
      val customPresetsBuffer = getPrivateUiField[ObservableBuffer[PromptPreset]](settingsView, "customPresetsBuffer")
      customPresetsBuffer.exists(_.name == newPresetName) shouldBe true
    }
  }
  
  it should "allow deleting a custom preset" in {
    testSettingsView(commonInitialSettings) { (settingsView, rootNode, stage) =>
      // Arrange
      val customListView = getPrivateUiField[SFXListView[PromptPreset]](settingsView, "customPresetsListView")
      val deletePresetButton = getPrivateUiField[SFXButton](settingsView, "deletePresetButton")
      val presetToSelect = commonInitialSettings.customPresets.head
      
      callPrivateMethod(settingsView, "setupPresetSelectionHandling")
      when(mockMainController.deleteCustomPreset(any[String])).thenReturn(Success(()))
      // Mock DialogUtils.showConfirmation to return OK
      // This is complex. Assume OK for now or refactor DialogUtils.

      // Act: Select a custom preset, then click delete
      onFxThread { customListView.selectionModel.value.select(presetToSelect) }
      waitForCondition(() => !deletePresetButton.disable.value)
      onFxThread { callPrivateMethod(settingsView, "handleDeletePreset", Some(JFXButtonType.OK)) } // Assuming OK

      // Assert
      verify(mockMainController, times(1)).deleteCustomPreset(mockitoEq(presetToSelect.name))
      val customPresetsBuffer = getPrivateUiField[ObservableBuffer[PromptPreset]](settingsView, "customPresetsBuffer")
      customPresetsBuffer.exists(_.name == presetToSelect.name) shouldBe false
    }
  }

  behavior of "SettingsView - Mappings Tab"
  
  // Existing test, slightly refactored for clarity and new helpers
  it should "display the correct preset assigned to a button" in {
    // Arrange
    val initialSettings = commonInitialSettings.copy(
      buttonMappings = Map(
        "Research" -> commonInitialSettings.defaultPresets.head.name, // "Default Research"
        "Code" -> commonInitialSettings.customPresets.head.name,     // "My Coding Preset"
        "Review" -> "NonExistentPreset" // Test case for unassigned/invalid
      )
    )
    
    testSettingsView(initialSettings) { (settingsView, rootNode, stage) =>
      // Ensure the mapping grid is populated. showAndWait calls this, but direct call is safer for test logic.
      onFxThread { settingsView.populateMappingGrid() }
      
      val tabPane = findNode[javafx.scene.control.TabPane](rootNode, ".tab-pane") // Standard TabPane selector
      // Select the Mappings Tab (assuming it's the 3rd tab, index 2)
      onFxThread { tabPane.getSelectionModel.select(2) }
      waitForCondition(() => tabPane.getSelectionModel.getSelectedIndex == 2) // Wait for tab selection
      
      val mappingGrid = findNode[javafx.scene.layout.GridPane](tabPane.getTabs.get(2).getContent, "#mappingGrid")
      waitForCondition(() => !mappingGrid.getChildren.isEmpty, timeoutMillis = 3000) // Wait for grid to populate

      // Helper to get selected preset name from a ComboBox for a given button/category name
      def getSelectedPresetNameForButton(buttonCategoryName: String): Option[String] = {
        mappingGrid.getChildren.asScala.collectFirst {
          case label: javafx.scene.control.Label if label.getText == s"$buttonCategoryName:" =>
            val rowIndex = javafx.scene.layout.GridPane.getRowIndex(label)
            mappingGrid.getChildren.asScala.collectFirst {
              case cb: javafx.scene.control.ComboBox[PromptPreset] @unchecked if javafx.scene.layout.GridPane.getRowIndex(cb) == rowIndex =>
                Option(cb.getSelectionModel.getSelectedItem).map(_.name)
            }.flatten
        }.flatten
      }

      // Assert
      getSelectedPresetNameForButton("Research") shouldBe Some(initialSettings.defaultPresets.head.name)
      getSelectedPresetNameForButton("Code") shouldBe Some(initialSettings.customPresets.head.name)
      getSelectedPresetNameForButton("Review") shouldBe None // NonExistentPreset should result in no selection
      // Assuming "Test" is a valid category button name from Header.categoryButtonNames not in initialMappings
      getSelectedPresetNameForButton("Test") shouldBe None 
    }
  }

  it should "call MainController to update button mappings when Save All is clicked" in {
    val newMappingForResearch = commonInitialSettings.customPresets.head // "My Coding Preset"
    val initialSettings = commonInitialSettings.copy(
        buttonMappings = Map("Research" -> commonInitialSettings.defaultPresets.head.name)
    )

    testSettingsView(initialSettings) { (settingsView, rootNode, stage) =>
        // Arrange
        onFxThread { settingsView.populateMappingGrid() } // Populate grid
        val tabPane = findNode[javafx.scene.control.TabPane](rootNode, ".tab-pane")
        onFxThread { tabPane.getSelectionModel.select(2) } // Select Mappings tab
        waitForCondition(() => tabPane.getSelectionModel.getSelectedIndex == 2)
        
        val mappingGrid = findNode[javafx.scene.layout.GridPane](tabPane.getTabs.get(2).getContent, "#mappingGrid")
        waitForCondition(() => !mappingGrid.getChildren.isEmpty)

        // Find ComboBox for "Research" and change its selection
        mappingGrid.getChildren.asScala.collectFirst {
            case label: javafx.scene.control.Label if label.getText == "Research:" =>
                val rowIndex = javafx.scene.layout.GridPane.getRowIndex(label)
                mappingGrid.getChildren.asScala.collectFirst {
                case cb: javafx.scene.control.ComboBox[PromptPreset] @unchecked if javafx.scene.layout.GridPane.getRowIndex(cb) == rowIndex =>
                    onFxThread { cb.getSelectionModel.select(newMappingForResearch) }
                }
        }
        
        // Act: Click "Save All & Close" by calling its handler
        onFxThread { callPrivateMethod(settingsView, "handleSaveAllSettings") }

        // Assert
        val expectedMappings = Map(
            "Research" -> newMappingForResearch.name // Updated
            // Other mappings from commonInitialSettings might also be included if they exist for other buttons.
            // For this test, we only modified "Research".
            // The currentButtonMappings map in SettingsView will reflect changes.
            // We need to ensure *all* current mappings are passed.
        ) ++ commonInitialSettings.buttonMappings.filterKeys(_ != "Research")


        val mappingsCaptor = ArgumentCaptor.forClass(classOf[Map[String, String]])
        verify(mockMainController, times(1)).updateButtonMappings(mappingsCaptor.capture())
        
        // Ensure the captured map contains the change and any other original mappings
        val captured = mappingsCaptor.getValue
        captured.get("Research") shouldBe Some(newMappingForResearch.name)
        // Check if other mappings from initialSettings (like "Code") are preserved
        commonInitialSettings.buttonMappings.filterKeys(_ != "Research").foreach { case (k, v) =>
            captured.get(k) shouldBe Some(v)
        }
    }
  }
  
  // Helper to call private methods on SettingsView instance (use with caution)
  private def callPrivateMethod(instance: SettingsView, methodName: String, args: Any*): AnyRef = {
    val argClasses = args.map(_.getClass)
    val method = instance.getClass.getDeclaredMethods.find { m =>
      m.getName == methodName && m.getParameterTypes.toList.zip(argClasses).forall { case (paramType, argClass) =>
        paramType.isAssignableFrom(argClass) || (paramType.isPrimitive && isPrimitiveWrapper(argClass, paramType))
      }
    }.getOrElse(fail(s"Private method '$methodName' with compatible args not found."))
    method.setAccessible(true)
    method.invoke(instance, args.map(_.asInstanceOf[AnyRef]): _*)
  }
  
  // Helper for callPrivateMethod to check primitive compatibility
  private def isPrimitiveWrapper(wrapper: Class[?], primitive: Class[?]): Boolean = {
    (primitive == classOf[Int] && wrapper == classOf[Integer]) ||
    (primitive == classOf[Boolean] && wrapper == classOf[java.lang.Boolean]) ||
    // Add other primitives as needed (Double, Float, etc.)
    false
  }

}