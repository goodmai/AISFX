// src/main/scala/com/aiplatform/view/SettingsView.scala
package com.aiplatform.view

import com.aiplatform.controller.MainController
import com.aiplatform.model.{ModelInfo, PromptPreset}
import com.aiplatform.view.Header // Импорт Header для списка кнопок
import scalafx.Includes._
import scalafx.application.Platform
import scalafx.collections.ObservableBuffer
import scalafx.geometry.{Insets, Pos}
// Импорты JavaFX для Callback и контролов
import javafx.scene.control.{ListCell => JFXListCell, ListView => JFXListView, SpinnerValueFactory => JFXSpinnerValueFactory, TextFormatter => JFXTextFormatter}
import javafx.util.{Callback, StringConverter}
import java.util.function.UnaryOperator
// Импорты ScalaFX контролов
import scalafx.scene.control.{Alert, Button, ButtonType, ComboBox, Label, ListCell, ListView, PasswordField, ScrollPane, Separator, Slider, Spinner, Tab, TabPane, TextArea, TextField, Tooltip}
import scalafx.scene.layout.{BorderPane, ColumnConstraints, GridPane, HBox, Priority, VBox}
// import scalafx.scene.text.Font // Font больше не нужен здесь
import scalafx.scene.{Node, Parent, Scene}
import scalafx.stage.{Modality, Stage, StageStyle}
import org.slf4j.LoggerFactory
import scala.collection.mutable
import scala.util.{Try, Success, Failure}
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal // Для NonFatal

// --- Утилиты для диалогов (ВЫНЕСЕНЫ ИЗ КЛАССА) ---
object DialogUtils {
  private val logger = LoggerFactory.getLogger(getClass) // Логгер для утилит

  private def createBaseAlert(alertType: Alert.AlertType, ownerWindow: Option[Stage]): Alert = new Alert(alertType) {
    ownerWindow.foreach(ow => initOwner(ow.delegate))
    initStyle(StageStyle.Utility)
  }

  def showWarning(message: String, ownerWindow: Option[Stage]): Unit = {
    Platform.runLater(() => {
      val alert = createBaseAlert(Alert.AlertType.Warning, ownerWindow)
      alert.title = "Предупреждение"
      alert.headerText = "" // Используем пустую строку вместо null
      alert.contentText = message
      alert.showAndWait()
    })
  }

  def showConfirmation(message: String, header: String = "Подтверждение", ownerWindow: Option[Stage]): Option[ButtonType] = {
    val alert = createBaseAlert(Alert.AlertType.Confirmation, ownerWindow)
    alert.title = header
    alert.headerText = message
    alert.contentText = "Вы уверены?"
    alert.showAndWait()
  }

  def showError(message: String, ownerWindow: Option[Stage]): Unit = {
    Platform.runLater(() => {
      val alert = createBaseAlert(Alert.AlertType.Error, ownerWindow)
      alert.title = "Ошибка"
      alert.headerText = "" // Используем пустую строку вместо null
      alert.contentText = message
      alert.showAndWait()
    })
  }

  def showInfo(message: String, ownerWindow: Option[Stage]): Unit = {
    Platform.runLater(() => {
      val alert = createBaseAlert(Alert.AlertType.Information, ownerWindow)
      alert.title = "Информация"
      alert.headerText = "" // Используем пустую строку вместо null
      alert.contentText = message
      alert.showAndWait()
    })
  }
}

// --- DTO для передачи начальных настроек (без полей шрифта) ---
case class CurrentSettings(
                            apiKey: String,
                            model: String,
                            // fontFamily: String, // <<< УДАЛЕНО >>>
                            // fontSize: Int,     // <<< УДАЛЕНО >>>
                            availableModels: List[ModelInfo],
                            buttonMappings: Map[String, String],
                            defaultPresets: List[PromptPreset],
                            customPresets: List[PromptPreset]
                          )

// --- Кастомная ячейка для ComboBox моделей ---
private class ModelListCell extends JFXListCell[ModelInfo] {
  private val contentBox = new javafx.scene.layout.VBox(3)
  private val primaryLabel = new javafx.scene.control.Label()
  primaryLabel.setStyle("-fx-font-weight: bold;")
  private val descriptionLabel = new javafx.scene.control.Label()
  descriptionLabel.setStyle("-fx-font-size: 0.9em; -fx-opacity: 0.8;")
  descriptionLabel.setWrapText(true)
  descriptionLabel.setMaxWidth(250)
  private val cellTooltip = new javafx.scene.control.Tooltip()
  contentBox.getChildren.addAll(primaryLabel, descriptionLabel)

  override def updateItem(item: ModelInfo, empty: Boolean): Unit = {
    super.updateItem(item, empty)
    if (empty || item == null) {
      setText(null); setGraphic(null); setTooltip(null)
    } else {
      val primaryText = item.displayName + item.state.map(s => s" ($s)").getOrElse("")
      primaryLabel.setText(primaryText)
      val descriptionText = item.description.getOrElse("")
      descriptionLabel.setText(descriptionText)
      descriptionLabel.setVisible(descriptionText.nonEmpty)
      descriptionLabel.setManaged(descriptionText.nonEmpty)
      setGraphic(contentBox)
      setText(null)
      cellTooltip.setText(item.description.getOrElse(primaryText))
      setTooltip(cellTooltip)
    }
  }
}


// --- Основной класс окна настроек ---
class SettingsView(
                    ownerStage: Stage, // Родительское окно
                    controller: MainController, // Ссылка на контроллер
                    initialSettings: CurrentSettings // Начальные данные (обновленный case class)
                  ) {

  private val logger = LoggerFactory.getLogger(getClass)
  private val ownerStageOpt: Option[Stage] = Option(ownerStage)

  // --- UI Элементы ---

  // Вкладка "Общие"
  private val apiKeyField = new PasswordField { text = initialSettings.apiKey; promptText = "Gemini API ключ..." }
  private val globalModelComboBox = createModelComboBox(initialSettings.availableModels, Some(initialSettings.model))
  // Элементы для шрифта удалены
  // private val availableFontFamilies = Font.families.sorted
  // private val fontFamilyComboBox = new ComboBox[String](availableFontFamilies.toSeq) { value = initialSettings.fontFamily; prefWidth = 200 }
  // private val fontSizeSpinner = createFontSizeSpinner(initialSettings.fontSize)

  // Вкладка "Пресеты"
  private val defaultPresetsBuffer = ObservableBuffer.from(initialSettings.defaultPresets.sortBy(_.name.toLowerCase))
  private val customPresetsBuffer = ObservableBuffer.from(initialSettings.customPresets.sortBy(_.name.toLowerCase))
  private val defaultPresetsListView = createPresetsListView(defaultPresetsBuffer, isDefault = true)
  private val customPresetsListView = createPresetsListView(customPresetsBuffer, isDefault = false)
  private val presetNameField = new TextField { promptText = "Имя пресета..."; editable = false }
  private val presetPromptArea = new TextArea { promptText = "Текст промпта..."; wrapText = true; prefRowCount = 6; editable = false }
  private val presetModelComboBox = createModelComboBox(initialSettings.availableModels, None)
  private val presetTempSlider = createSlider(0.0, 1.0, 0.7)
  private val presetTopPSlider = createSlider(0.0, 1.0, 0.95)
  private val presetTopKSpinner = createTopKSpinner(40)
  private val tempValueLabel = new Label() { style = "-fx-font-size: 0.9em;" }
  private val topPValueLabel = new Label() { style = "-fx-font-size: 0.9em;" }
  private lazy val presetEditorPane: VBox = buildPresetEditorPane()
  private val newPresetButton = new Button("Новый") { onAction = _ => handleNewPreset() }
  private val savePresetButton = new Button("Сохранить") { onAction = _ => handleSavePreset(); disable = true }
  private val deletePresetButton = new Button("Удалить") { onAction = _ => handleDeletePreset(); disable = true }

  // Вкладка "Назначение"
  private val currentButtonMappings = mutable.Map.from(initialSettings.buttonMappings)
  private val mappingGridPane = new GridPane { vgap = 8; hgap = 10; padding = Insets(15); id = "mappingGrid" }

  // Кнопки окна
  private val saveAllButton = new Button("Сохранить все и закрыть") { onAction = _ => handleSaveAllSettings() }
  private val cancelButton = new Button("Отмена") { onAction = _ => handleCancel() }

  // --- Состояние View ---
  private var selectedPresetIsDefault: Boolean = false
  private var selectedPresetOriginalName: Option[String] = None

  // --- Определение Layout (ДО dialogStage) ---
  /** Создает основной layout окна настроек. */
  private def createLayout(): Parent = {
    // Вкладка "Общие"
    val generalSettingsGrid = new GridPane {
      hgap = 10; vgap = 15; padding = Insets(20)
      add(new Label("API Key:"), 0, 0); add(apiKeyField, 1, 0)
      add(new Label("Глобальная модель:"), 0, 1); add(globalModelComboBox, 1, 1)
      // Строка для шрифтов удалена
      // add(new Label("Шрифт интерфейса:"), 0, 2); add(new HBox(5, fontFamilyComboBox, fontSizeSpinner), 1, 2)
      GridPane.setHgrow(apiKeyField, Priority.Always)
      GridPane.setHgrow(globalModelComboBox, Priority.Always)
    }
    val generalTab = new Tab { text = "Общие"; content = generalSettingsGrid; closable = false }

    // Вкладка "Пресеты"
    val presetListsPane = new HBox(15) {
      padding = Insets(10)
      children = Seq(
        new VBox(5, new Label("Стандартные:"), defaultPresetsListView) { hgrow = Priority.Always },
        new VBox(5, new Label("Пользовательские:"), customPresetsListView) { hgrow = Priority.Always }
      )
      hgrow = Priority.Always
    }
    val presetButtons = new HBox(10, newPresetButton, savePresetButton, deletePresetButton) {
      alignment = Pos.CenterLeft
      padding = Insets(0, 10, 10, 10)
    }
    val presetManagementPane = new BorderPane {
      top = presetListsPane
      center = new ScrollPane { content = presetEditorPane; fitToWidth = true }
      bottom = presetButtons
    }
    val presetsTab = new Tab { text = "Пресеты"; content = presetManagementPane; closable = false }

    // Вкладка "Назначение"
    val mappingScrollPane = new ScrollPane { content = mappingGridPane; fitToWidth = true; padding = Insets(10) }
    val mappingDescription = new Label("Назначьте пресет кнопкам категорий:") {
      padding = Insets(10, 10, 0, 15); style = "-fx-font-style: italic;"
    }
    val mappingPane = new BorderPane { top = mappingDescription; center = mappingScrollPane }
    val mappingTab = new Tab { text = "Назначение"; content = mappingPane; closable = false }

    // Вкладка "Горячие клавиши" (плейсхолдер)
    val hotkeysPlaceholder = new BorderPane {
      center = new Label("Настройка горячих клавиш (в разработке)") { style = "-fx-font-style: italic; -fx-text-fill: gray;" }
      padding = Insets(20)
    }
    val hotkeysTab = new Tab { text = "Горячие клавиши"; content = hotkeysPlaceholder; closable = false }

    // Собираем TabPane
    val tabPane = new TabPane { tabs = Seq(generalTab, presetsTab, mappingTab, hotkeysTab) }

    // Кнопки окна
    val windowButtons = new HBox {
      spacing = 10; alignment = Pos.CenterRight; padding = Insets(15)
      children = Seq(saveAllButton, cancelButton)
    }

    // Корневой BorderPane окна
    new BorderPane { center = tabPane; bottom = windowButtons }
  }


  // --- Окно диалога настроек (ленивая инициализация ПОСЛЕ createLayout) ---
  private lazy val dialogStage: Stage = new Stage() {
    initOwner(ownerStageOpt.map(_.delegate).orNull)
    initModality(Modality.WindowModal)
    initStyle(StageStyle.Utility)
    title = "Настройки"
    scene = new Scene(750, 650) {
      root = createLayout() // Теперь createLayout определен
    }
    onCloseRequest = _ => logger.debug("Settings window close request received.")
  }

  // --- Фабричные методы для UI элементов ---

  /** Создает ComboBox для выбора AI модели. */
  private def createModelComboBox(models: List[ModelInfo], initialSelectionName: Option[String]): ComboBox[ModelInfo] = {
    val modelBuffer = ObservableBuffer.from(models.sortBy(_.displayName))
    val comboBox = new ComboBox[ModelInfo](modelBuffer) {
      cellFactory = (listView: JFXListView[ModelInfo]) => new ModelListCell()
      buttonCell = new JFXListCell[ModelInfo] {
        override def updateItem(item: ModelInfo, empty: Boolean): Unit = {
          super.updateItem(item, empty)
          setText(if (empty || item == null) null else item.displayName)
        }
      }
      prefWidth = 250
      placeholder = new Label(if (models.isEmpty) "Нет доступных моделей" else "Выберите модель")
    }
    initialSelectionName
      .flatMap(name => models.find(_.name == name))
      .foreach(comboBox.selectionModel().select(_))
    comboBox
  }

  /* <<< НАЧАЛО УДАЛЕНИЯ createFontSizeSpinner >>> */
  /*
  private def createFontSizeSpinner(initialSize: Int): Spinner[Int] = {
    // ... весь метод удален ...
  }
  */
  /* <<< КОНЕЦ УДАЛЕНИЯ >>> */

  /** Создает Spinner для выбора Top K. */
  private def createTopKSpinner(initialValue: Int): Spinner[Int] = {
    val minValue = 1
    val maxValue = 100
    val valueFactory = new JFXSpinnerValueFactory.IntegerSpinnerValueFactory(minValue, maxValue, initialValue)
    val spinner = new Spinner[Int](valueFactory.asInstanceOf[JFXSpinnerValueFactory[Int]]) {
      editable = true
      prefWidth = 80
    }
    val topKFilter: UnaryOperator[JFXTextFormatter.Change] = change => {
      val newText = change.getControlNewText
      if (newText.matches("[0-9]*")) {
        Try(newText.toInt).toOption match {
          case Some(value) if value >= minValue && value <= maxValue => change
          case _ if newText.isEmpty => change
          case _ => null
        }
      } else { null }
    }
    val topKConverter = new StringConverter[Int] {
      override def toString(i: Int): String = i.toString
      override def fromString(s: String): Int = Try(s.toInt).getOrElse(initialValue)
    }
    spinner.delegate.getEditor.setTextFormatter(new JFXTextFormatter[Int](topKConverter, initialValue, topKFilter))
    spinner
  }


  /** Создает ListView для отображения пресетов. */
  private def createPresetsListView(buffer: ObservableBuffer[PromptPreset], isDefault: Boolean): ListView[PromptPreset] = {
    new ListView[PromptPreset](buffer) {
      cellFactory = (listView: JFXListView[PromptPreset]) => new JFXListCell[PromptPreset] {
        override def updateItem(item: PromptPreset, empty: Boolean): Unit = {
          super.updateItem(item, empty)
          if (empty || item == null) {
            setText(null); setTooltip(null); setStyle("")
          } else {
            setText(item.name)
            setTooltip(new javafx.scene.control.Tooltip(item.prompt.take(150) + (if (item.prompt.length > 150) "..." else "")))
            setStyle(if (item.isDefault) "-fx-font-style: italic; -fx-opacity: 0.8;" else "")
          }
        }
      }
      prefHeight = 180
      placeholder = new Label(s"Нет ${if (!isDefault) "пользовательских" else "стандартных"} пресетов")
    }
  }

  /** Создает Slider с заданными параметрами. */
  private def createSlider(minValue: Double, maxValue: Double, initial: Double): Slider = {
    new Slider(minValue, maxValue, initial) {
      showTickLabels = true
      showTickMarks = true
      majorTickUnit = (maxValue - minValue) / 2.0
      minorTickCount = 4
      blockIncrement = (maxValue - minValue) / 10.0
    }
  }

  // --- Логика UI ---

  /** Настраивает обработчики выбора пресета в списках. */
  private def setupPresetSelectionHandling(): Unit = {
    defaultPresetsListView.selectionModel().selectedItem.onChange { (_, _, selectedPreset) =>
      if (selectedPreset != null) {
        customPresetsListView.selectionModel().clearSelection()
        handlePresetSelection(selectedPreset, isDefault = true)
      }
    }
    customPresetsListView.selectionModel().selectedItem.onChange { (_, _, selectedPreset) =>
      if (selectedPreset != null) {
        defaultPresetsListView.selectionModel().clearSelection()
        handlePresetSelection(selectedPreset, isDefault = false)
      }
    }
  }

  /** Обрабатывает выбор пресета в одном из списков. */
  private def handlePresetSelection(preset: PromptPreset, isDefault: Boolean): Unit = {
    selectedPresetIsDefault = isDefault
    selectedPresetOriginalName = Some(preset.name)
    logger.debug(s"Preset selected: '${preset.name}', isDefault: $isDefault")
    populatePresetEditor(preset)
    presetEditorPane.disable = false
    presetNameField.editable = !isDefault
    presetPromptArea.editable = true
    presetModelComboBox.disable = false
    presetTempSlider.disable = false
    presetTopPSlider.disable = false
    presetTopKSpinner.disable = false
    savePresetButton.disable = false
    deletePresetButton.disable = isDefault
  }

  /** Заполняет поля редактора пресетов данными из объекта PromptPreset. */
  private def populatePresetEditor(preset: PromptPreset): Unit = {
    presetNameField.text = preset.name
    presetPromptArea.text = preset.prompt
    presetTempSlider.value = preset.temperature
    presetTopPSlider.value = preset.topP
    Try(presetTopKSpinner.delegate.getValueFactory.setValue(preset.topK))
    initialSettings.availableModels.find(m => preset.modelOverride.contains(m.name)) match {
      case Some(modelInfo) => presetModelComboBox.selectionModel().select(modelInfo)
      case None => presetModelComboBox.selectionModel().clearSelection()
    }
  }

  /** Собирает layout для панели редактора пресетов. */
  private def buildPresetEditorPane(): VBox = {
    tempValueLabel.text <== presetTempSlider.value.map(v => f"(${v.doubleValue()}%.2f)")
    topPValueLabel.text <== presetTopPSlider.value.map(v => f"(${v.doubleValue()}%.2f)")
    val paramsGrid = new GridPane {
      hgap = 10; vgap = 8
      add(new Label("Температура:"), 0, 0); add(presetTempSlider, 1, 0); add(tempValueLabel, 2, 0)
      add(new Label("Top P:"), 0, 1);        add(presetTopPSlider, 1, 1); add(topPValueLabel, 2, 1)
      add(new Label("Top K:"), 0, 2);        add(presetTopKSpinner, 1, 2)
      columnConstraints = Seq(
        new ColumnConstraints { prefWidth = 100 },
        new ColumnConstraints { hgrow = Priority.Always },
        new ColumnConstraints { prefWidth = 50 }
      )
    }
    new VBox {
      spacing = 8
      padding = Insets(10)
      children = Seq(
        new Label("Имя:"), presetNameField,
        new Label("Промпт:"), presetPromptArea,
        new Separator(),
        new Label("Модель для пресета (оставьте пустой для глобальной):"), presetModelComboBox,
        new Separator(),
        new Label("Параметры генерации (для этой модели):"), paramsGrid
      )
      disable = true // Начальное состояние - неактивен
    }
  }

  /** Очищает поля редактора пресетов и деактивирует его. */
  private def clearAndDisablePresetEditor(): Unit = {
    defaultPresetsListView.selectionModel().clearSelection()
    customPresetsListView.selectionModel().clearSelection()
    presetNameField.text = ""
    presetPromptArea.text = ""
    presetTempSlider.value = 0.7
    presetTopPSlider.value = 0.95
    Try(presetTopKSpinner.delegate.getValueFactory.setValue(40))
    presetModelComboBox.selectionModel().clearSelection()
    presetEditorPane.disable = true
    savePresetButton.disable = true
    deletePresetButton.disable = true
    presetNameField.editable = false
    presetPromptArea.editable = false
    selectedPresetIsDefault = false
    selectedPresetOriginalName = None
    logger.trace("Preset editor cleared and disabled.")
  }

  /** Заполняет GridPane для назначения пресетов кнопкам категорий. */
  def populateMappingGrid(): Unit = {
    mappingGridPane.children.clear()
    val allPresets = (defaultPresetsBuffer.toList ++ customPresetsBuffer.toList).sortBy(_.name.toLowerCase)
    if (allPresets.isEmpty) {
      mappingGridPane.add(new Label("Нет доступных пресетов для назначения."), 0, 0, 2, 1)
      return
    }
    val presetBuffer = ObservableBuffer.from(allPresets)

    // Используем реальные имена кнопок из Header
    val categoryButtons = Header.categoryButtonNames.filterNot(_ == "Global") // Убираем Global, если для него не нужен маппинг

    categoryButtons.zipWithIndex.foreach { case (buttonName, index) =>
      val buttonLabel = new Label(s"$buttonName:") { minWidth = 80 }
      val presetComboBox = new ComboBox[PromptPreset](presetBuffer) {
        // Настройка cellFactory для отображения имени пресета
        cellFactory = (listView: JFXListView[PromptPreset]) => new JFXListCell[PromptPreset] {
          override def updateItem(item: PromptPreset, empty: Boolean): Unit = {
            super.updateItem(item, empty)
            setText(if (empty || item == null) null else item.name)
          }
        }
        // Настройка buttonCell для отображения имени выбранного пресета на самой кнопке ComboBox
        buttonCell = new JFXListCell[PromptPreset] {
          override def updateItem(item: PromptPreset, empty: Boolean): Unit = {
            super.updateItem(item, empty)
            setText(if (empty || item == null) null else item.name)
          }
        }
        prefWidth = 250
        placeholder = new Label("...") // Текст, если ничего не выбрано

        // Устанавливаем начальное значение из currentButtonMappings
        currentButtonMappings.get(buttonName)
          .flatMap(name => allPresets.find(_.name.equalsIgnoreCase(name)))
          .foreach(selectionModel().select(_))

        // Обновляем карту при изменении выбора
        selectionModel().selectedItem.onChange { (_, _, selectedPreset) =>
          if (selectedPreset != null) {
            currentButtonMappings.put(buttonName, selectedPreset.name)
            logger.trace(s"Mapping UI updated: $buttonName -> ${selectedPreset.name}")
          } else {
            currentButtonMappings.remove(buttonName) // Удаляем, если выбор очищен
            logger.trace(s"Mapping UI removed for $buttonName")
          }
        }
      }
      mappingGridPane.add(buttonLabel, 0, index)
      mappingGridPane.add(presetComboBox, 1, index)
    }
    logger.debug("Mapping grid populated.")
  }


  // --- Обработчики кнопок окна настроек ---

  /** Обработчик нажатия кнопки "Новый пресет". */
  private def handleNewPreset(): Unit = {
    logger.debug("New Preset button clicked.")
    clearAndDisablePresetEditor() // Очищаем редактор
    presetEditorPane.disable = false // Активируем
    presetNameField.editable = true
    presetPromptArea.editable = true
    presetModelComboBox.disable = false
    presetTempSlider.disable = false
    presetTopPSlider.disable = false
    presetTopKSpinner.disable = false
    savePresetButton.disable = false // Разрешаем сохранение
    deletePresetButton.disable = true  // Удалять пока нечего
    selectedPresetIsDefault = false // Это будет пользовательский пресет
    selectedPresetOriginalName = None
    Platform.runLater(presetNameField.requestFocus()) // Фокус на поле имени
  }

  /**
   * Обработчик нажатия кнопки "Сохранить пресет".
   */
  private def handleSavePreset(): Unit = {
    val name = presetNameField.text.value.trim
    val prompt = presetPromptArea.text.value.trim
    val temp = presetTempSlider.value.value
    val topP = presetTopPSlider.value.value
    val topK = Try(presetTopKSpinner.value.value).getOrElse(40)
    val modelOverride = Option(presetModelComboBox.selectionModel().selectedItem.value).map(_.name)

    if (name.isEmpty || prompt.isEmpty) {
      DialogUtils.showWarning("Имя и текст промпта не могут быть пустыми.", ownerStageOpt)
      return
    }

    // Если это НЕ стандартный пресет ИЛИ это стандартный, но имя НЕ менялось
    val finalName = if (!selectedPresetIsDefault || selectedPresetOriginalName.contains(name)) {
      name
    } else {
      // Попытка переименовать стандартный пресет - запрещено
      DialogUtils.showError("Нельзя переименовывать стандартные пресеты.", ownerStageOpt)
      return
    }

    val presetToSave = PromptPreset(finalName, prompt, temp, topP, topK, modelOverride, selectedPresetIsDefault)

    logger.info("Attempting to save preset: {}", presetToSave.name)
    val saveAction: Try[Unit] = if (presetToSave.isDefault) controller.saveDefaultPreset(presetToSave) else controller.saveCustomPreset(presetToSave)

    saveAction match {
      case Success(_) =>
        logger.info("Preset '{}' saved successfully via controller.", presetToSave.name)
        val buffer = if (presetToSave.isDefault) defaultPresetsBuffer else customPresetsBuffer
        val listView = if (presetToSave.isDefault) defaultPresetsListView else customPresetsListView

        // Обновляем буфер и список
        val existingIndex = buffer.indexWhere(_.name.equalsIgnoreCase(presetToSave.name))
        if (existingIndex >= 0) {
          buffer.update(existingIndex, presetToSave) // Обновляем
        } else if (!presetToSave.isDefault) {
          buffer.add(presetToSave) // Добавляем новый пользовательский
        } else {
          logger.error("Tried to add a new default preset or update failed to find existing one in buffer.")
        }
        // Пересортируем буфер
        buffer.setAll(buffer.sortBy(_.name.toLowerCase).asJava)

        populateMappingGrid() // Обновляем маппинги, т.к. список пресетов изменился
        listView.selectionModel().select(presetToSave) // Выбираем сохраненный элемент

        // Обновляем состояние редактора для сохраненного пресета
        handlePresetSelection(presetToSave, presetToSave.isDefault)

        DialogUtils.showInfo(s"Пресет '${presetToSave.name}' сохранен.", ownerStageOpt)
      case Failure(e) =>
        logger.error(s"Failed to save preset '${presetToSave.name}' via controller.", e)
        DialogUtils.showError(s"Не удалось сохранить пресет:\n${e.getMessage}", ownerStageOpt)
    }
  }


  /** Обработчик нажатия кнопки "Удалить пресет". */
  private def handleDeletePreset(): Unit = {
    if (!selectedPresetIsDefault) {
      selectedPresetOriginalName.foreach { presetName =>
        DialogUtils.showConfirmation(s"Удалить пользовательский пресет '$presetName'?", ownerWindow = ownerStageOpt) match {
          case Some(ButtonType.OK) =>
            logger.info("Attempting to delete custom preset: {}", presetName)
            controller.deleteCustomPreset(presetName) match {
              case Success(_) =>
                logger.info("Preset '{}' deleted successfully via controller.", presetName)
                customPresetsBuffer.find(_.name.equalsIgnoreCase(presetName)).foreach(customPresetsBuffer.remove(_))
                currentButtonMappings.filterInPlace { case (_, mappedName) => !mappedName.equalsIgnoreCase(presetName) }
                populateMappingGrid() // Обновляем маппинги
                clearAndDisablePresetEditor() // Очищаем редактор
                DialogUtils.showInfo(s"Пресет '$presetName' удален.", ownerStageOpt)
              case Failure(e) =>
                logger.error(s"Failed to delete custom preset '$presetName' via controller.", e)
                DialogUtils.showError(s"Не удалось удалить пресет:\n${e.getMessage}", ownerStageOpt)
            }
          case _ => logger.debug("Preset deletion cancelled by user.")
        }
      }
    } else { logger.warn("Attempted to delete a default preset.") }
  }

  /**
   * Обработчик кнопки "Сохранить все и закрыть".
   */
  private def handleSaveAllSettings(): Unit = {
    logger.info("Save All & Close button clicked.")

    val newApiKey = apiKeyField.text.value.trim
    val newGlobalModelOpt = Option(globalModelComboBox.selectionModel().selectedItem.value)
    // Поля шрифта удалены
    // val newFontFamily = fontFamilyComboBox.value.value
    // val newFontSize = Try(fontSizeSpinner.value.value).getOrElse(initialSettings.fontSize)
    val finalMappings = currentButtonMappings.toMap

    var closeWindow = true
    val results = mutable.Buffer[Try[Unit]]()

    // 1. Обновляем API ключ
    Try(controller.updateApiKey(newApiKey)).recover { case NonFatal(e) =>
      logger.error("Unexpected error during controller.updateApiKey call.", e)
    }

    // 2. Обновляем глобальную модель
    newGlobalModelOpt match {
      case Some(selectedModelInfo) =>
        results += controller.updateGlobalAIModel(selectedModelInfo.name)
      case None if initialSettings.availableModels.nonEmpty =>
        DialogUtils.showWarning("Глобальная модель AI не выбрана.", ownerStageOpt)
        closeWindow = false
        results += Failure(new IllegalStateException("Глобальная модель не выбрана."))
      case None => logger.debug("No global model selected and no models available.")
    }

    // 3. Обновляем шрифт (УДАЛЕНО)
    // results += controller.updateFontSettings(newFontFamily, newFontSize)

    // 4. Обновляем маппинги
    results += controller.updateButtonMappings(finalMappings)

    // --- Анализируем результаты и закрываем окно ---
    val failures = results.collect { case Failure(e) => e }

    if (failures.isEmpty && closeWindow) {
      logger.info("All settings saved successfully. Closing settings window.")
      if (dialogStage.showing.value) dialogStage.close()
    } else {
      val errorMessages = failures.map(_.getMessage).mkString("\n - ")
      logger.error(s"Failed to save some settings:\n - $errorMessages")
      if (closeWindow) {
        DialogUtils.showError(s"Не удалось сохранить некоторые настройки:\n - $errorMessages", ownerStageOpt)
        logger.warn("Window kept open due to save errors.")
      } else {
        logger.warn("Window kept open due to validation errors or save failures.")
        if (failures.nonEmpty) {
          DialogUtils.showError(s"Также не удалось сохранить некоторые настройки:\n - $errorMessages", ownerStageOpt)
        }
      }
    }
  }


  /** Обработчик кнопки "Отмена". */
  private def handleCancel(): Unit = {
    logger.info("Cancel button clicked.")
    if (dialogStage.showing.value) {
      dialogStage.close()
    }
  }

  // --- Публичный метод для показа окна ---

  /**
   * Показывает модальное окно настроек и ожидает его закрытия.
   * Инициализирует UI элементы перед показом.
   */
  def showAndWait(): Unit = {
    setupPresetSelectionHandling()
    populateMappingGrid()
    clearAndDisablePresetEditor()

    // Обновляем ComboBox моделей АКТУАЛЬНЫМИ данными из initialSettings ПЕРЕД показом
    val currentAvailableModels = initialSettings.availableModels.sortBy(_.displayName)
    val currentGlobalModelName = initialSettings.model

    globalModelComboBox.items = ObservableBuffer.from(currentAvailableModels)
    presetModelComboBox.items = ObservableBuffer.from(currentAvailableModels)
    // Повторно выбираем глобальную модель
    currentAvailableModels.find(_.name == currentGlobalModelName)
      .foreach(globalModelComboBox.selectionModel().select(_))

    // Показываем окно
    if (!dialogStage.showing.value) {
      logger.info("Showing settings window.")
      dialogStage.showAndWait()
    } else {
      logger.warn("Settings window is already showing.")
      dialogStage.toFront()
    }
  }
}