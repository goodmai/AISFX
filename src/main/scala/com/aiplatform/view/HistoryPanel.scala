// src/main/scala/com/aiplatform/view/HistoryPanel.scala
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import scala.jdk.CollectionConverters._
// Импортируем JavaFX ListCell и Callback для cellFactory
import javafx.scene.control.{ListCell => JFXListCell, ListView => JFXListView}
import javafx.util.Callback
import scala.util.control.NonFatal

/**
 * Объект-компаньон для панели истории (списка топиков).
 * Управляет отображением списка топиков и взаимодействием с MainController.
 */
object HistoryPanel {

  private val logger = LoggerFactory.getLogger(getClass)
  // Буфер для хранения топиков, отображаемых в списке
  private val topicItems = ObservableBuffer[Topic]()
  // Ссылка на экземпляр MainController (устанавливается в create)
  private var mainControllerInstance: Option[MainController] = None

  // Форматтер для отображения даты/времени последнего обновления топика
  private val dateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
      .withZone(ZoneId.systemDefault())

  // --- UI Элементы ---

  // ListView для отображения топиков
  private[view] lazy val topicsListView = new ListView[Topic](topicItems) {
    vgrow = Priority.Always // Растягивать по вертикали
    styleClass.add("history-list-view") // CSS класс
    selectionModel().selectionMode = SelectionMode.Single // Только одиночный выбор
    prefWidth = 250 // Предпочтительная ширина панели
    id = "historyTopicsListView" // ID для тестов

    // Фабрика для создания и обновления ячеек списка
    cellFactory = new Callback[JFXListView[Topic], JFXListCell[Topic]] {
      override def call(listView: JFXListView[Topic]): JFXListCell[Topic] = {
        // Передаем сохраненную ссылку на экземпляр контроллера
        new TopicListCell(mainControllerInstance)
      }
    }
  }

  /**
   * Создает корневой узел (Parent) для панели истории.
   * @param controller Ссылка на экземпляр MainController.
   * @return Корневой узел панели.
   */
  def create(controller: MainController): Parent = {
    mainControllerInstance = Some(controller) // Сохраняем ссылку на контроллер

    // Слушатель изменения выбора в ListView
    topicsListView.selectionModel().selectedItem.onChange { (_, _, selectedTopic) =>
      // Вызываем setActiveTopic контроллера ТОЛЬКО если выбор сделан пользователем
      mainControllerInstance.foreach { ctrl =>
        // Используем публичный getter из MainController
        if (!ctrl.getIsProgrammaticSelection) {
          val selectedTopicId = Option(selectedTopic).map(_.id)
          logger.trace(s"User selection changed in HistoryPanel. Informing controller. Selected ID: ${selectedTopicId.getOrElse("None")}")
          // Передаем Option[String] в контроллер
          ctrl.setActiveTopic(selectedTopicId)
        } else {
          logger.trace("Selection change ignored in HistoryPanel (programmatic selection).")
        }
      }
    }

    logger.info("HistoryPanel created.")
    // Возвращаем VBox, содержащий только ListView
    new VBox {
      children = Seq(topicsListView)
      vgrow = Priority.Always // VBox также должен растягиваться
      styleClass.add("history-panel") // CSS класс для VBox
    }
  }

  /**
   * Обновляет список топиков в UI (получает отфильтрованный список от контроллера).
   * Выполняется в потоке JavaFX. Добавлена проверка размера для оптимизации.
   *
   * @param filteredSortedTopics Отсортированный список топиков для текущей категории.
   * @param activeTopicId        Опциональный ID топика, который должен быть активным (используется для логгирования).
   */
  def updateTopics(filteredSortedTopics: List[Topic], activeTopicId: Option[String]): Unit = {
    Platform.runLater {
      val currentItems = topicItems.toList // Получаем текущие элементы

      // Оптимизация: сравниваем размеры и содержимое только если размеры разные или списки не равны
      if (currentItems.size != filteredSortedTopics.size || currentItems != filteredSortedTopics) {
        logger.trace(s"Updating topic list in UI. New size: ${filteredSortedTopics.size}, Old size: ${currentItems.size}. Active ID hint: ${activeTopicId.getOrElse("None")}")
        topicItems.setAll(filteredSortedTopics.asJava) // Обновляем буфер
      } else {
        logger.trace("Topic list update skipped, items are identical.")
      }
      // Выбор активного элемента происходит через controller.setActiveTopic -> historyPanel.selectTopic
    }
  }


  /**
   * Программно выбирает топик в списке по ID.
   * Вызывается из MainController для синхронизации выбора с состоянием.
   * ВАЖНО: Не должен вызывать обратный вызов setActiveTopic в контроллере.
   *
   * @param topicId ID топика для выбора (или null/пустая строка для снятия выбора).
   */
  def selectTopic(topicId: String): Unit = {
    Platform.runLater {
      val topicIdOpt = Option(topicId).filter(_.nonEmpty) // Преобразуем в Option, игнорируя пустые строки
      val topicToSelectOpt = topicIdOpt.flatMap(id => topicItems.find(_.id == id))
      val currentSelectionOpt = Option(topicsListView.selectionModel().selectedItem.value)

      // Выбираем только если новый выбор отличается от текущего
      if (topicToSelectOpt != currentSelectionOpt) {
        topicToSelectOpt match {
          case Some(topicToSelect) =>
            topicsListView.selectionModel().select(topicToSelect)
            // Плавно прокручиваем к выбранному элементу (если он видим)
            topicsListView.scrollTo(topicToSelect)
            logger.debug(s"Programmatically selected topic '${topicToSelect.title}' (ID: ${topicToSelect.id}).")
          case None => // topicId был null, пустой или не найден
            topicsListView.selectionModel().clearSelection()
            logger.debug(s"Programmatic selection cleared (requested ID: '${topicIdOpt.getOrElse("None")}').")
        }
      } else {
        // Даже если ID тот же, убедимся, что элемент видим
        topicToSelectOpt.foreach(topicsListView.scrollTo)
        logger.trace(s"Programmatic selection for topic '${topicIdOpt.getOrElse("None")}' skipped (already selected or no change needed). Ensured visibility.")
      }
    }
  }
}

/**
 * Кастомная ячейка для отображения информации о топике в ListView.
 * Использует стандартный метод updateItem для обновления содержимого.
 *
 * @param controllerOpt Опциональная ссылка на экземпляр MainController для обработки удаления.
 */
private class TopicListCell(controllerOpt: Option[MainController]) extends JFXListCell[Topic] {

  private val logger = LoggerFactory.getLogger(getClass)

  // --- UI элементы ячейки (создаются один раз) ---
  private val topicTitleLabel = new Label() {
    style = "-fx-font-weight: bold;"
    maxWidth = 180 // Ограничиваем ширину, чтобы поместилась кнопка
    wrapText = true
    minHeight = 30 // Минимальная высота для переноса
  }
  private val topicDateLabel = new Label() {
    style = "-fx-font-size: 0.85em; -fx-text-fill: #666;" // Меньший шрифт, серый цвет
  }
  private val textVBox = new VBox(topicTitleLabel, topicDateLabel) {
    spacing = 2 // Небольшой отступ между заголовком и датой
  }
  private val deleteButton = new Button("X") { // Используем "X" для удаления
    style = "-fx-font-size: 10px; -fx-padding: 1px 5px; -fx-cursor: hand; -fx-text-fill: #555;" // Стиль кнопки
    tooltip = Tooltip("Удалить этот топик") // Подсказка
    // Обработчик нажатия устанавливается в updateItem, чтобы иметь доступ к 'item'
  }
  private val buttonBox = new HBox(deleteButton) { // HBox для кнопки
    alignment = Pos.CenterRight // Выравнивание кнопки справа
    padding = Insets(0, 0, 0, 5) // Отступ слева от кнопки
  }
  // Основной layout ячейки с использованием BorderPane
  private val cellLayout = new BorderPane {
    styleClass.add("topic-list-cell") // CSS класс
    left = textVBox     // Текст слева
    center = buttonBox  // Кнопка справа (в центре по вертикали)
    padding = Insets(5) // Внутренние отступы ячейки
  }
  // Tooltip для всей ячейки
  private val cellTooltip = new Tooltip()

  // Форматтер даты/времени (тот же, что и в объекте-компаньоне)
  private val dateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
      .withZone(ZoneId.systemDefault())

  // --- Метод обновления ячейки (стандартный подход) ---
  override def updateItem(topic: Topic, empty: Boolean): Unit = {
    super.updateItem(topic, empty) // Обязательный вызов родительского метода

    if (empty || topic == null) {
      // Если ячейка пустая или элемент null, очищаем все
      setText(null)
      setGraphic(null)
      setTooltip(null)
      deleteButton.onAction = null // Убираем обработчик
    } else {
      // Если есть данные (topic), настраиваем отображение
      topicTitleLabel.text = topic.title // Устанавливаем заголовок
      topicDateLabel.text = dateTimeFormatter.format(topic.lastUpdatedAt) // Форматируем дату

      // Настраиваем Tooltip для ячейки
      cellTooltip.text = s"""
        Топик: ${topic.title}
        Категория: ${topic.category}
        Создан: ${dateTimeFormatter.format(topic.createdAt)}
        Обновлен: ${dateTimeFormatter.format(topic.lastUpdatedAt)}
        Сообщений: ${topic.dialogs.size}
        """.stripMargin // Убираем лишние пробелы в начале строк
      setTooltip(cellTooltip.delegate) // Устанавливаем Tooltip

      // Устанавливаем обработчик для кнопки удаления (имея доступ к 'topic')
      deleteButton.onAction = _ => {
        // Добавим проверку на null на всякий случай
        Option(topic).foreach { t =>
          logger.debug(s"Delete button clicked for topic: ${t.title} (ID: ${t.id})")
          // Вызываем метод удаления в контроллере, если он доступен
          controllerOpt.foreach(_.deleteTopic(t.id))
        }
      }

      // Устанавливаем кастомный layout как графику ячейки
      setGraphic(cellLayout)
      setText(null) // Убираем стандартный текст ячейки
    }
  }
}
