package com.aiplatform.view

import com.aiplatform.model.Dialog
import org.apache.pekko.actor.typed.ActorRef
import com.aiplatform.service.HistoryService
import com.aiplatform.repository.StateRepository // Добавлен импорт для загрузки начального состояния
import scalafx.collections.ObservableBuffer
import scalafx.scene.control.{ListCell, ListView, SelectionMode} // Добавлен SelectionMode
import scalafx.scene.layout.{Priority, VBox}
import scalafx.scene.Parent
import scalafx.scene.text.Text

// Объект для создания панели истории
object HistoryPanel {

  // Буфер для хранения элементов списка (должен быть Observable для автообновления ListView)
  private val historyItems = ObservableBuffer[Dialog]()

  // Сам список
  private val historyListView = new ListView[Dialog](historyItems) {
    vgrow = Priority.Always
    styleClass.add("history-list-view")
    //selectionModel().selectionMode_=(SelectionMode.Single) // Используем setter syntax `_=`
    selectionModel().setSelectionMode(SelectionMode.Single)
    // --- ИСПРАВЛЕНИЕ 1: Явный тип параметра для cellFactory ---
    cellFactory = (listView: ListView[Dialog]) => { // Указываем тип параметра listView
      new ListCell[Dialog] {
        // Привязываемся к изменению itemProperty ячейки
        item.onChange { (_, _, newDialog) =>
          if (newDialog != null) {
            // Формируем отображение для непустой ячейки
            val requestText = new Text(s"Q: ${newDialog.request.take(50)}...") {
              wrappingWidth = 180 // Ограничиваем ширину текста
            }
            val modelText = new Text(s"(${newDialog.model})") {
              style = "-fx-font-size: 0.8em; -fx-fill: gray;"
            }
            // Устанавливаем графику ячейки
            graphic = new VBox(requestText, modelText) {
              styleClass.add("history-list-cell")
            }
            text = null // Убираем стандартный текст ячейки
          } else {
            // Очищаем графику и текст для пустой ячейки
            graphic = null
            text = null
          }
        }
      }
    }
  }

  // Ссылка на актора для возможных действий (например, загрузка по клику)
  private var historyServiceActor: Option[ActorRef[HistoryService.Command]] = None

  def create(actor: ActorRef[HistoryService.Command]): Parent = {
    historyServiceActor = Some(actor)

    // --- ИСПРАВЛЕНИЕ 2: Используем addListener вместо onChange ---
    historyListView.selectionModel().selectedItemProperty.addListener {
      // Эта лямбда-функция будет вызвана при изменении выбора
      (observable, oldValue, newValue) => // Параметры ChangeListener
        if (newValue != null) {
          println(s"Selected history item: ${newValue.request.take(30)}...")
          // Пример действия: отобразить полный диалог в ResponseArea
          // ResponseArea.updateResponse(s"Запрос (${newValue.model}):\n${newValue.request}\n\nОтвет:\n${newValue.response}")
        }
    }

    // Загрузка начальной истории при создании панели
    val initialState = StateRepository.loadState()
    updateEntries(initialState.dialogHistory)

    // Создаем VBox, содержащий ListView
    new VBox {
      children = Seq(historyListView)
      vgrow = Priority.Always
      prefWidth = 220
      styleClass.add("history-panel")
    }
  }
  /** Добавляет запись в начало видимого списка истории */
  def addEntry(dialog: Dialog): Unit = {
    println(s"[HistoryPanel] Adding entry: ${dialog.request.take(20)}...")
    historyItems.insert(0, dialog) // Вставляем в начало ObservableBuffer
    // Опционально: прокрутить к новому элементу или ограничить размер
    // historyListView.scrollTo(0)
    // if (historyItems.length > 100) historyItems.remove(historyItems.length - 1)
  }

  /** Обновляет весь список истории (например, при начальной загрузке) */
  def updateEntries(dialogs: List[Dialog]): Unit = {
    println(s"[HistoryPanel] Updating all entries. Count: ${dialogs.length}")
    historyItems.clear()
    // Добавляем в буфер так, чтобы новые были наверху (если dialogs это List с новыми в начале)
    // Если dialogs отсортированы от старых к новым, используйте addAll(dialogs.reverse)
    historyItems.addAll(dialogs) // Предполагаем, что dialogs уже в нужном порядке (новые первыми)
  }
}