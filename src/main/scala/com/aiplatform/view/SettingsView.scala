// updated: scala/com/aiplatform/view/SettingsView.scala
package com.aiplatform.view

import com.aiplatform.controller.MainController
import com.aiplatform.model.{ModelInfo, PromptPreset}
import scalafx.Includes._
import scalafx.application.Platform
import scalafx.collections.ObservableBuffer
import scalafx.geometry.{Insets, Pos}
// Импорты JavaFX
import javafx.scene.control.{ListCell => JFXListCell, ListView => JFXListView, SpinnerValueFactory => JFXSpinnerValueFactory, TextFormatter => JFXTextFormatter}
import javafx.util.{Callback, StringConverter}
import java.util.function.UnaryOperator
// Импорты ScalaFX
import scalafx.scene.control.{Alert, Button, ButtonType, ComboBox, Label, ListCell, ListView, PasswordField, ScrollPane, Separator, Slider, Spinner, Tab, TabPane, TextArea, TextField, Tooltip}
import scalafx.scene.layout.{BorderPane, ColumnConstraints, GridPane, HBox, Priority,  VBox}
import scalafx.scene.text.Font
import scalafx.scene.{Node, Parent, Scene}
import scalafx.stage.{Modality, Stage, StageStyle}
import org.slf4j.LoggerFactory
import scala.collection.mutable
import scala.util.{Try, Success, Failure}
import scala.jdk.CollectionConverters._

// Утилиты диалогов (без изменений)
object DialogUtils {
  private def createBaseAlert(alertType: Alert.AlertType, ownerWindow: Stage): Alert = new Alert(alertType) { initOwner(ownerWindow.delegate); initStyle(StageStyle.Utility) }
  def showWarning(message: String, ownerWindow: Stage): Unit = { Platform.runLater(() => { val alert = createBaseAlert(Alert.AlertType.Warning, ownerWindow); alert.title = "Предупреждение"; alert.headerText = ""; alert.contentText = message; alert.showAndWait() }) }
  def showConfirmation(message: String, header: String = "Подтверждение", ownerWindow: Stage): Option[ButtonType] = { val alert = createBaseAlert(Alert.AlertType.Confirmation, ownerWindow); alert.title = header; alert.headerText = message; alert.contentText = "Вы уверены?"; alert.showAndWait() }
  def showError(message: String, ownerWindow: Stage): Unit = { Platform.runLater(() => { val alert = createBaseAlert(Alert.AlertType.Error, ownerWindow); alert.title = "Ошибка"; alert.headerText = ""; alert.contentText = message; alert.showAndWait() }) }
}

// DTO для передачи настроек (без изменений)
case class CurrentSettings(apiKey: String, model: String, fontFamily: String, fontSize: Int, availableModels: List[ModelInfo], buttonMappings: Map[String, String], defaultPresets: List[PromptPreset], customPresets: List[PromptPreset])

// Класс ячейки для ComboBox моделей (использует JavaFX ListCell)
private class ModelListCell extends JFXListCell[ModelInfo] {
  private val contentBox: VBox = new VBox() { padding = Insets(3) }
  private val primaryLabel = new Label() { style = "-fx-font-weight: bold;" }
  private val descriptionLabel = new Label() { style = "-fx-font-size: 0.9em; -fx-opacity: 0.8;"; wrapText = true; maxWidth = 250 }
  private val cellTooltip = new Tooltip()

  override def updateItem(item: ModelInfo, empty: Boolean): Unit = {
    super.updateItem(item, empty)
    if (empty || item == null) {
      setText(null); setGraphic(null); setTooltip(null)
    } else {
      val pt = item.displayName + item.state.map(s => s" ($s)").getOrElse("")
      primaryLabel.text = pt
      val dt = item.description.getOrElse("")
      contentBox.children = if (dt.nonEmpty) { descriptionLabel.text = dt; Seq(primaryLabel, descriptionLabel) } else { Seq(primaryLabel) }
      setGraphic(contentBox); setText(null)
      cellTooltip.text = item.description.getOrElse(""); setTooltip(cellTooltip)
    }
  }
}

// Основной класс окна настроек
class SettingsView(
                    ownerStage: Stage,
                    controller: MainController,
                    initialSettings: CurrentSettings
                  ) {

  private val logger = LoggerFactory.getLogger(getClass)

  // --- UI Элементы ---
  private val apiKeyField = new PasswordField { text = initialSettings.apiKey; promptText = "Gemini API ключ..." }
  private val globalModelComboBox = createModelComboBox(initialSettings.availableModels, Some(initialSettings.model))
  private val availableFontFamilies = Font.families.sorted
  private val fontFamilyComboBox = new ComboBox[String](availableFontFamilies.toSeq) { value = initialSettings.fontFamily; prefWidth = 200 }

  // --- Spinner для размера шрифта ---
  private val fontSizeValueFactoryDelegate: JFXSpinnerValueFactory[Integer] = new JFXSpinnerValueFactory.IntegerSpinnerValueFactory(8, 72, initialSettings.fontSize)
  private val fontSizeSpinner = new Spinner[Int](fontSizeValueFactoryDelegate.asInstanceOf[JFXSpinnerValueFactory[Int]]) {
    editable = true
    prefWidth = 80
    // Фильтр (JavaFX тип)
    // --- ИСПРАВЛЕНИЕ: Тип фильтра ---
    private val integerFilter: UnaryOperator[JFXTextFormatter.Change] = change => {
      // -------------------------------
      val newText = change.getControlNewText
      if (newText.matches("[0-9]*")) {
        Try(newText.toInt).map { value => if (value >= 8 && value <= 72) change else null }
          .getOrElse(if (newText.isEmpty) change else null)
      } else { null }
    }
    // Конвертер (JavaFX тип)
    private val converter = new StringConverter[Int] {
      override def toString(i: Int): String = i.toString
      override def fromString(s: String): Int = Try(s.toInt).getOrElse(initialSettings.fontSize)
    }
    // --- ИСПРАВЛЕНИЕ: Конструктор TextFormatter ---
    delegate.editor.value.setTextFormatter(new JFXTextFormatter[Int](converter, initialSettings.fontSize, integerFilter))
    // ------------------------------------------
  }

  private val defaultPresetsBuffer = ObservableBuffer.from(initialSettings.defaultPresets.sortBy(_.name.toLowerCase))
  private val customPresetsBuffer = ObservableBuffer.from(initialSettings.customPresets.sortBy(_.name.toLowerCase))
  private val defaultPresetsListView = createPresetsListView(defaultPresetsBuffer, "Стандартные пресеты")
  private val customPresetsListView = createPresetsListView(customPresetsBuffer, "Пользовательские пресеты")
  private val presetNameField = new TextField { promptText = "Имя пресета..."; editable = false }
  private val presetPromptArea = new TextArea { promptText = "Текст промпта..."; wrapText = true; prefRowCount = 6; editable = false }
  private val presetModelComboBox = createModelComboBox(initialSettings.availableModels, None)
  private val presetModelLabel = new Label("Модель для пресета:")
  private val presetTempSlider = createSlider(minValue = 0.0d, maxValue = 1.0d, initial = 0.7d)
  private val presetTopPSlider = createSlider(minValue = 0.0d, maxValue = 1.0d, initial = 0.95d)

  // --- Spinner для Top K ---
  private val topKDefaultValue = 40
  private val topKValueFactoryDelegate: JFXSpinnerValueFactory[Integer] = new JFXSpinnerValueFactory.IntegerSpinnerValueFactory(1, 100, topKDefaultValue)
  private val presetTopKSpinner = new Spinner[Int](topKValueFactoryDelegate.asInstanceOf[JFXSpinnerValueFactory[Int]]) {
    editable = true
    prefWidth = 80
    // Фильтр (JavaFX тип)
    // --- ИСПРАВЛЕНИЕ: Тип фильтра ---
    private val topKFilter: UnaryOperator[JFXTextFormatter.Change] = change => {
      // -------------------------------
      val newText = change.getControlNewText
      if (newText.matches("[0-9]*")) {
        Try(newText.toInt).map { value => if (value >= 1 && value <= 100) change else null }
          .getOrElse(if (newText.isEmpty) change else null)
      } else { null }
    }
    // Конвертер (JavaFX тип)
    private val topKConverter = new StringConverter[Int] {
      override def toString(i: Int): String = i.toString
      override def fromString(s: String): Int = Try(s.toInt).getOrElse(topKDefaultValue)
    }
    // --- ИСПРАВЛЕНИЕ: Конструктор TextFormatter ---
    delegate.editor.value.setTextFormatter(new JFXTextFormatter[Int](topKConverter, topKDefaultValue, topKFilter))
    // ------------------------------------------
    Try(topKValueFactoryDelegate.setValue(initialSettings.defaultPresets.headOption.map(_.topK).orElse(initialSettings.customPresets.headOption.map(_.topK)).getOrElse(topKDefaultValue)))
  }

  private val tempValueLabel = new Label { style = "-fx-font-size: 0.9em;" }
  private val topPValueLabel = new Label { style = "-fx-font-size: 0.9em;" }
  tempValueLabel.text <== presetTempSlider.value.map(v => f"(${v.doubleValue()}%.2f)")
  topPValueLabel.text <== presetTopPSlider.value.map(v => f"(${v.doubleValue()}%.2f)")

  private val presetEditorPane = new VBox { spacing = 5; disable = true }
  private val newPresetButton = new Button("Новый") { onAction = { _ => handleNewPreset() } }
  private val savePresetButton = new Button("Сохранить") { onAction = { _ => handleSavePreset() }; disable = true }
  private val deletePresetButton = new Button("Удалить") { onAction = { _ => handleDetetePreset() }; disable = true }
  private val currentButtonMappings = mutable.Map.from(initialSettings.buttonMappings)
  private val mappingGridPane = new GridPane { vgap = 8; hgap = 10; padding = Insets(15) }
  private val saveAllButton = new Button("Сохранить все и закрыть") { onAction = { _ => handleSaveAllSettings() } }
  private val cancelButton = new Button("Отмена") { onAction = { _ => handleCancel() } }

  private lazy val dialogStage: Stage = new Stage() {
    initOwner(ownerStage.delegate); initModality(Modality.WindowModal); initStyle(StageStyle.Utility)
    title = "Настройки"; scene = new Scene(750, 650) { root = createLayout() }
    onCloseRequest = { _ => logger.debug("Settings closed via 'X'.") }
  }
  private var selectedPresetIsDefault: Boolean = false // Меняем имя для ясности

  // --- Инициализация UI ---
  Platform.runLater {
    setupPresetSelectionHandling()
    populatePresetEditorPane()
    populateMappingGrid()
    logger.info(s"SettingsView initialized...")
    if(initialSettings.availableModels.isEmpty) logger.warn("Model list empty!")
    if(defaultPresetsBuffer.isEmpty && customPresetsBuffer.isEmpty) logger.warn("Preset lists empty!")
  }

  // --- Фабричные методы для UI элементов ---
  private def createModelComboBox(models: List[ModelInfo], initialSelectionName: Option[String]): ComboBox[ModelInfo] = {
    val modelBuffer = ObservableBuffer.from(models.sortBy(_.displayName))
    new ComboBox[ModelInfo](modelBuffer) {
      // --- ИСПРАВЛЕНИЕ: Используем Callback ---
      cellFactory = new Callback[JFXListView[ModelInfo], JFXListCell[ModelInfo]] {
        override def call(listView: JFXListView[ModelInfo]): JFXListCell[ModelInfo] = new ModelListCell()
      }
      // ------------------------------------
      buttonCell = new ListCell[ModelInfo] {
        item.onChange { (_, _, mi) => text = Option(mi).map(m => if(m.displayName.nonEmpty) m.displayName else m.name).getOrElse("") }
      }
      initialSelectionName.flatMap(name => models.find(_.name == name)).foreach(selectionModel().select(_))
      prefWidth = 250
      placeholder = new Label("Нет доступных моделей")
    }
  }

  private def createPresetsListView(buffer: ObservableBuffer[PromptPreset], title: String): ListView[PromptPreset] = {
    new ListView[PromptPreset](buffer) {
      cellFactory = (lv: ListView[PromptPreset]) => new ListCell[PromptPreset] {
        item.onChange { (_, _, p) =>
          if (p != null) {
            text = p.name // Просто имя
            tooltip = Tooltip(p.prompt.take(100) + "...")
            style = if (p.isDefault) "-fx-font-style: italic; -fx-opacity: 0.8;" else ""
          } else { text = null; tooltip = null; style = "" }
        }
      }
      prefHeight = 180
      placeholder = new Label(s"Нет ${if (title.contains("Польз")) "пользовательских" else "стандартных"} пресетов")
    }
  }

  private def createSlider(minValue: Double, maxValue: Double, initial: Double): Slider = {
    new Slider(minValue, maxValue, initial) { showTickLabels=true; showTickMarks=true; majorTickUnit=(maxValue-minValue)/2.0; minorTickCount=4; blockIncrement=(maxValue-minValue)/10.0 }
  }

  // --- Логика UI ---
  private def setupPresetSelectionHandling(): Unit = {
    defaultPresetsListView.selectionModel().selectedItem.onChange { (_, _, sp) => if (sp != null) { customPresetsListView.selectionModel().clearSelection(); handlePresetSelection(sp, isDefault = true) } } // isDefault = true
    customPresetsListView.selectionModel().selectedItem.onChange { (_, _, sp) => if (sp != null) { defaultPresetsListView.selectionModel().clearSelection(); handlePresetSelection(sp, isDefault = false) } } // isDefault = false
  }

  // Параметр теперь isDefault
  private def handlePresetSelection(preset: PromptPreset, isDefault: Boolean): Unit = {
    selectedPresetIsDefault = isDefault // Используем новое имя переменной
    logger.debug(s"Preset selected: '${preset.name}', isDefault: $isDefault")
    populatePresetFields(preset)
    presetEditorPane.disable = false
    presetNameField.editable = !isDefault // Имя меняем только у НЕ стандартных
    presetPromptArea.editable = true // Промпт и параметры меняем у всех
    presetTempSlider.disable = false
    presetTopPSlider.disable = false
    presetTopKSpinner.disable = false
    presetModelComboBox.disable = false
    savePresetButton.disable = false // Сохранять можно все
    deletePresetButton.disable = isDefault // Удалять НЕЛЬЗЯ стандартные
  }

  private def populatePresetFields(preset: PromptPreset): Unit = {
    presetNameField.text = preset.name; presetPromptArea.text = preset.prompt
    presetTempSlider.value = preset.temperature; presetTopPSlider.value = preset.topP
    Try(topKValueFactoryDelegate.setValue(preset.topK))
    initialSettings.availableModels.find(m => preset.modelOverride.contains(m.name)) match {
      case Some(mi) => presetModelComboBox.selectionModel().select(mi)
      case None => presetModelComboBox.selectionModel().clearSelection()
    }
  }

  private def populatePresetEditorPane(): Unit = {
    val paramsGrid = new GridPane {
      hgap = 10; vgap = 8
      add(new Label("Температура:"), 0, 0); add(presetTempSlider, 1, 0); add(tempValueLabel, 2, 0)
      add(new Label("Top P:"), 0, 1); add(presetTopPSlider, 1, 1); add(topPValueLabel, 2, 1)
      add(new Label("Top K:"), 0, 2); add(presetTopKSpinner, 1, 2)
      columnConstraints = Seq( new ColumnConstraints { prefWidth = 80 }, new ColumnConstraints { hgrow = Priority.Always }, new ColumnConstraints { prefWidth = 40 })
    }
    presetEditorPane.children = Seq(
      new Label("Имя:"), presetNameField,
      new Label("Промпт:"), presetPromptArea,
      new Separator(), presetModelLabel, presetModelComboBox,
      new Separator(), new Label("Параметры генерации:"), paramsGrid
    )
    presetEditorPane.padding = Insets(10)
  }

  private def clearPresetFieldsAndDisable(): Unit = {
    defaultPresetsListView.selectionModel().clearSelection(); customPresetsListView.selectionModel().clearSelection()
    presetNameField.text = ""; presetPromptArea.text = "";
    presetTempSlider.value = 0.7; presetTopPSlider.value = 0.95;
    Try(topKValueFactoryDelegate.setValue(topKDefaultValue));
    presetModelComboBox.selectionModel().clearSelection();
    presetEditorPane.disable = true; savePresetButton.disable = true; deletePresetButton.disable = true;
    presetNameField.editable = false; presetPromptArea.editable = false;
    selectedPresetIsDefault = false // Сбрасываем флаг
    logger.trace("Preset editor cleared.")
  }

  private def populateMappingGrid(): Unit = {
    mappingGridPane.children.clear()
    val allPresets = (defaultPresetsBuffer.toList ++ customPresetsBuffer.toList).sortBy(_.name.toLowerCase)
    if (allPresets.isEmpty) { mappingGridPane.add(new Label("Нет пресетов."), 0, 0); return }
    val presetBuffer = ObservableBuffer.from(allPresets)

    Header.buttonNames.filterNot(_ == "Settings").zipWithIndex.foreach { case (buttonName, index) =>
      val buttonLabel = new Label(s"$buttonName:")
      val presetComboBox = new ComboBox[PromptPreset](presetBuffer) {
        // --- ИСПРАВЛЕНИЕ: Явный тип ListCell ---
        cellFactory = (lv: ListView[PromptPreset]) => new ListCell[PromptPreset] {
          item.onChange { (_, _, p) => text = Option(p).map(_.name).getOrElse(""); style = if (Option(p).exists(_.isDefault)) "-fx-font-style: italic; -fx-opacity: 0.8;" else "" }
        }
        // ------------------------------------
        buttonCell = new ListCell[PromptPreset] { item.onChange((_, _, p) => text = Option(p).map(_.name).getOrElse("")); style = "-fx-text-fill: black;" }
        prefWidth = 250; placeholder = new Label("...")
        currentButtonMappings.get(buttonName).flatMap(name => allPresets.find(_.name.equalsIgnoreCase(name))).foreach(selectionModel().select(_))
        selectionModel().selectedItem.onChange { (_, _, sel) => if (sel != null) currentButtonMappings.put(buttonName, sel.name) else currentButtonMappings.remove(buttonName); logger.trace(s"Map UI: $buttonName -> ${Option(sel).map(_.name)}") }
      }
      mappingGridPane.add(buttonLabel, 0, index); mappingGridPane.add(presetComboBox, 1, index)
    }
    logger.debug("Mapping grid populated.")
  }

  def showAndWait(): Unit = {
    if (!dialogStage.showing.value) { logger.info("Showing settings."); clearPresetFieldsAndDisable(); dialogStage.showAndWait() }
    else { logger.warn("Settings already showing."); dialogStage.toFront() }
  }

  private def createLayout(): Parent = {
    val gsGrid = new GridPane { hgap=10; vgap=15; padding=Insets(20); add(new Label("API Key:"),0,0); add(apiKeyField,1,0); add(new Label("Глобальная модель:"),0,1); add(globalModelComboBox,1,1); add(new Label("Шрифт:"),0,2); add(new HBox(5,fontFamilyComboBox,fontSizeSpinner),1,2); GridPane.setHgrow(apiKeyField,Priority.Always); GridPane.setHgrow(globalModelComboBox,Priority.Always) }
    val pListsPane = new HBox(15){ padding=Insets(10); children=Seq(new VBox(5,new Label("Стандартные:"),defaultPresetsListView){hgrow=Priority.Always}, new VBox(5,new Label("Пользовательские:"),customPresetsListView){hgrow=Priority.Always}); hgrow=Priority.Always }
    val pButtons = new HBox(10,newPresetButton,savePresetButton,deletePresetButton){ alignment=Pos.CenterLeft; padding=Insets(0,10,10,10) }
    val pMgmtPane = new BorderPane{ top=pListsPane; center=new ScrollPane{content=presetEditorPane;fitToWidth=true}; bottom=pButtons }
    val mapScrollPane = new ScrollPane{content=mappingGridPane;fitToWidth=true;padding=Insets(10)}
    val mapDesc = new Label("Назначьте пресет кнопкам:"){padding=Insets(10,10,0,15); style="-fx-font-style: italic;"}
    val mapPane = new BorderPane{ top=mapDesc; center=mapScrollPane }
    val tabPane = new TabPane{ tabs=Seq(new Tab{text="Общие";content=gsGrid;closable=false}, new Tab{text="Пресеты";content=pMgmtPane;closable=false}, new Tab{text="Назначение";content=mapPane;closable=false}) }
    val winButtons = new HBox{ spacing=10;alignment=Pos.CenterRight;padding=Insets(15);children=Seq(saveAllButton,cancelButton) }
    new BorderPane{ center=tabPane; bottom=winButtons }
  }

  // --- Обработчики кнопок ---
  private def handleNewPreset(): Unit = {
    logger.debug("New Preset clicked."); clearPresetFieldsAndDisable();
    presetEditorPane.disable=false; presetNameField.editable=true; presetPromptArea.editable=true;
    presetTempSlider.disable=false; presetTopPSlider.disable=false; presetTopKSpinner.disable=false;
    presetModelComboBox.disable=false; savePresetButton.disable=false; deletePresetButton.disable=true;
    selectedPresetIsDefault=false; // Новый - не стандартный
    presetNameField.requestFocus()
  }

  private def handleSavePreset(): Unit = {
    val name = presetNameField.text.value.trim
    val prompt = presetPromptArea.text.value.trim
    val temp = presetTempSlider.value.value
    val topP = presetTopPSlider.value.value
    val topK = Try(presetTopKSpinner.value.value).getOrElse(topKDefaultValue)
    val modelOverride = Option(presetModelComboBox.selectionModel().selectedItem.value).map(_.name)

    if (name.isEmpty || prompt.isEmpty) { DialogUtils.showWarning("Имя и промпт не могут быть пустыми.", dialogStage); return }

    // --- ИСПРАВЛЕНИЕ: Используем Option(...) ---
    val currentSelectionOpt = if (selectedPresetIsDefault) Option(defaultPresetsListView.selectionModel().selectedItem.value)
    else Option(customPresetsListView.selectionModel().selectedItem.value)
    // -----------------------------------------

    val otherPresets = (defaultPresetsBuffer.toList ++ customPresetsBuffer.toList)
      .filterNot(p => currentSelectionOpt.exists(_.name.equalsIgnoreCase(p.name)))
    if (otherPresets.exists(_.name.equalsIgnoreCase(name))) { DialogUtils.showWarning(s"Имя '$name' уже используется.", dialogStage); return }

    val finalName = if (selectedPresetIsDefault) currentSelectionOpt.map(_.name).getOrElse(name) else name
    val preset = PromptPreset(finalName, prompt, temp, topP, topK, modelOverride, selectedPresetIsDefault)

    logger.info("Saving {} preset: {}", if(selectedPresetIsDefault) "default" else "custom", finalName)

    val saveFuture = if (selectedPresetIsDefault) Try(controller.saveDefaultPreset(preset)) else Try(controller.saveCustomPreset(preset))

    saveFuture match {
      case Success(_) =>
        logger.info("Preset '{}' saved.", finalName)
        val buffer = if (selectedPresetIsDefault) defaultPresetsBuffer else customPresetsBuffer
        val list = if (selectedPresetIsDefault) defaultPresetsListView else customPresetsListView
        buffer.find(_.name.equalsIgnoreCase(finalName)) match {
          case Some(ex) => val idx = buffer.indexOf(ex); if(idx >= 0) buffer.update(idx, preset)
          case None if !selectedPresetIsDefault => buffer.add(preset) // Добавляем только новые пользовательские
          case _ => logger.error("Preset not found in buffer after save.")
        }
        buffer.setAll(buffer.sortBy(_.name.toLowerCase).asJava) // Обновляем с сортировкой
        populateMappingGrid()
        list.selectionModel().select(preset)
        handlePresetSelection(preset, selectedPresetIsDefault)
      case Failure(e @ (_ : IllegalArgumentException | _ : NoSuchElementException))=> logger.warn(s"Save failed: ${e.getMessage}"); DialogUtils.showError(s"Ошибка: ${e.getMessage}", dialogStage)
      case Failure(e: Throwable)=> logger.error("Save failed.", e); DialogUtils.showError(s"Ошибка: ${e.getMessage}", dialogStage)
    }
  }


  private def handleDetetePreset(): Unit = {
    // --- ИСПРАВЛЕНИЕ: Используем Option(...) ---
    Option(customPresetsListView.selectionModel().selectedItem.value) match {
      // -----------------------------------------
      case None => logger.warn("Delete clicked, nothing selected.")
      case Some(selectedPreset) if selectedPresetIsDefault => /* Кнопка должна быть неактивна */ logger.error("Attempt to delete default preset!")
      case Some(selectedPreset) => // Удаляем пользовательский
        DialogUtils.showConfirmation(s"Удалить пресет '${selectedPreset.name}'?", ownerWindow = dialogStage, header = "Удаление") match {
          case Some(ButtonType.OK) =>
            logger.info("Deleting preset: {}", selectedPreset.name)
            Try(controller.deleteCustomPreset(selectedPreset.name)) match {
              case Success(_) =>
                logger.info("Preset deleted."); customPresetsBuffer.remove(selectedPreset)
                currentButtonMappings.filterInPlace { case (_, name) => !name.equalsIgnoreCase(selectedPreset.name) }
                populateMappingGrid(); clearPresetFieldsAndDisable()
              case Failure(e @ (_:NoSuchElementException | _:IllegalArgumentException)) => logger.warn(s"Delete failed: ${e.getMessage}"); DialogUtils.showError(s"Ошибка: ${e.getMessage}", dialogStage)
              case Failure(e: Throwable) => logger.error("Delete failed.", e); DialogUtils.showError(s"Ошибка: ${e.getMessage}", dialogStage)
            }
          case _ => logger.debug("Deletion cancelled.")
        }
    }
  }

  private def handleSaveAllSettings(): Unit = {
    logger.info("Save All clicked.")
    val newApiKey=apiKeyField.text.value.trim
    val newGlobalModelOpt=Option(globalModelComboBox.selectionModel().selectedItem.value).map(_.name)
    val newFontFamily=fontFamilyComboBox.value.value
    val newFontSize=Try(fontSizeSpinner.value.value).getOrElse(initialSettings.fontSize)

    if(newApiKey.isEmpty){ DialogUtils.showWarning("API ключ пуст.", dialogStage); return }
    if(newGlobalModelOpt.isEmpty){ DialogUtils.showWarning("Глоб. модель не выбрана.", dialogStage); return }

    Try {
      controller.updateApiKey(newApiKey)
      controller.updateGlobalAIModel(newGlobalModelOpt.get)
      controller.updateFontSettings(newFontFamily, newFontSize)
      controller.updateButtonMappings(currentButtonMappings.toMap)
    } match {
      case Success(_) => logger.info("All settings saved."); if(dialogStage.showing.value) dialogStage.close()
      case Failure(e:IllegalArgumentException) => logger.warn(s"Save all failed: ${e.getMessage}"); DialogUtils.showError(s"Ошибка: ${e.getMessage}", dialogStage)
      case Failure(e:Throwable) => logger.error("Save all failed.", e); DialogUtils.showError(s"Ошибка: ${e.getMessage}", dialogStage)
    }
  }

  private def handleCancel(): Unit = { logger.info("Cancel clicked."); if (dialogStage.showing.value) dialogStage.close() }

} // Конец SettingsView