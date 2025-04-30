package com.aiplatform.view

import com.aiplatform.controller.MainController
import com.aiplatform.model.Topic
import scalafx.Includes._
import scalafx.application.Platform
import scalafx.collections.ObservableBuffer
import scalafx.geometry.{Insets, Pos}
import scalafx.scene.control._
import scalafx.scene.layout.{BorderPane, HBox, Priority, VBox}
import scalafx.scene.Parent
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import scala.jdk.CollectionConverters._ // Импорт для .asJava

object HistoryPanel {

  private val logger = LoggerFactory.getLogger(getClass)
  private val topicItems = ObservableBuffer[Topic]()
  private var mainControllerOpt: Option[MainController] = None

  private val dateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
      .withZone(ZoneId.systemDefault())

  // --- UI Элементы ---
  private lazy val topicsListView = new ListView[Topic](topicItems) {
    vgrow = Priority.Always
    styleClass.add("history-list-view")
    selectionModel().setSelectionMode(SelectionMode.Single)
    prefWidth = 250

    cellFactory = (listView: ListView[Topic]) => {
      new ListCell[Topic] {
        val topicTitleLabel = new Label() { style = "-fx-font-weight: bold;"; maxWidth=180; wrapText=true; minHeight = 30 }
        val topicDateLabel = new Label() { style = "-fx-font-size: 0.8em; -fx-fill: gray;" }
        val textVBox = new VBox(topicTitleLabel, topicDateLabel) { spacing = 2 }
        val deleteButton = new Button("-") {
          style = "-fx-font-size: 9px; -fx-padding: 1px 4px; -fx-cursor: hand;"
          tooltip = Tooltip("Удалить этот топик")
          onAction = _ => {
            Option(item.value).foreach { topic =>
              logger.debug(s"Delete button clicked for topic: ${topic.title} (ID: ${topic.id})")
              mainControllerOpt.foreach(_.deleteTopic(topic.id))
            }
          }
        }
        val buttonBox = new HBox(5, deleteButton) { alignment = Pos.CenterRight }
        val cellLayout = new BorderPane {
          styleClass.add("topic-list-cell")
          left = textVBox
          right = buttonBox
          padding = Insets(5)
        }

        item.onChange { (_, _, newTopic) =>
          if (newTopic != null && !empty.value) {
            topicTitleLabel.text = newTopic.title
            topicDateLabel.text = dateTimeFormatter.format(newTopic.lastUpdatedAt)
            tooltip = Tooltip(s"Топик: ${newTopic.title}\nКатегория: ${newTopic.category}\nСоздан: ${newTopic.createdAt}\nОбновлен: ${newTopic.lastUpdatedAt}\nСообщений: ${newTopic.dialogs.size}")
            graphic = cellLayout
            text = null
          } else {
            graphic = null; text = null; tooltip = null
          }
        }
      }
    }
  }

  /**
   * Создает корневой узел панели истории.
   */
  def create(controller: MainController): Parent = {
    mainControllerOpt = Some(controller)

    topicsListView.selectionModel().selectedItem.onChange { (_, _, selectedTopic) =>
      val selectedTopicId = Option(selectedTopic).map(_.id)
      logger.trace(s"UI selection changed. Informing controller about selected topic ID: ${selectedTopicId.getOrElse("None")}")
      mainControllerOpt.foreach(_.setActiveTopic(selectedTopicId.orNull))
    }

    logger.info("HistoryPanel created.")
    new VBox { children = Seq(topicsListView); vgrow = Priority.Always; styleClass.add("history-panel") }
  }

  /**
   * Обновляет список топиков (получает отфильтрованный список).
   */
  def updateTopics(filteredSortedTopics: List[Topic], activeTopicId: Option[String]): Unit = {
    Platform.runLater {
      val currentSelectionId = Option(topicsListView.selectionModel().selectedItem.value).map(_.id)
      topicItems.setAll(filteredSortedTopics.asJava) // Обновляем буфер

      val topicToSelectOpt = activeTopicId.flatMap(id => filteredSortedTopics.find(_.id == id))

      topicToSelectOpt match {
        case Some(topicToSelect) =>
          if (currentSelectionId != Some(topicToSelect.id)) {
            topicsListView.selectionModel().select(topicToSelect)
            topicsListView.scrollTo(topicToSelect)
            logger.debug(s"Active topic '${topicToSelect.title}' selected in list.")
          } else { logger.trace(s"Topic '${topicToSelect.title}' already selected.") }
        case None =>
          if (currentSelectionId.isDefined) {
            topicsListView.selectionModel().clearSelection()
            logger.debug("Active topic not in filtered list. Cleared selection.")
          } else { logger.trace("List updated, no active/selected topic.") }
      }
    }
  }

  /**
   * Программно выбирает топик в списке.
   */
  def selectTopic(topicId: String): Unit = {
    Platform.runLater {
      val topicToSelectOpt = Option(topicId).flatMap(id => topicItems.find(_.id == id))
      val currentSelectionOpt = Option(topicsListView.selectionModel().selectedItem.value)

      if (topicToSelectOpt.isDefined) {
        val topicToSelect = topicToSelectOpt.get
        if (currentSelectionOpt != topicToSelectOpt) {
          topicsListView.selectionModel().select(topicToSelect)
          topicsListView.scrollTo(topicToSelect)
          logger.debug(s"Programmatically selected topic '${topicToSelect.title}'.")
        } else { logger.trace(s"Topic '${topicToSelect.title}' already selected programmatically.") }
      } else { // Топик не найден в списке или topicId = null
        if (currentSelectionOpt.isDefined) {
          topicsListView.selectionModel().clearSelection()
          logger.debug(s"Programmatic selection requested for '$topicId'/null, not visible/null. Selection cleared.")
        } else { logger.trace(s"Programmatic selection for '$topicId'/null, not visible/null, nothing selected.") }
      }
    }
  }

} 