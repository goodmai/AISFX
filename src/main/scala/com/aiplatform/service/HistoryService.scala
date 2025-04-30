// updated: scala/com/aiplatform/service/HistoryService.scala
package com.aiplatform.service

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import com.aiplatform.model.Dialog
// Убираем импорты AppState и StateRepository, т.к. актор больше не управляет состоянием
// import com.aiplatform.model.AppState
// import com.aiplatform.repository.StateRepository
import org.slf4j.LoggerFactory

object HistoryService {
  private val logger = LoggerFactory.getLogger(getClass)

  // --- ИЗМЕНЕНИЕ: Обновленные команды ---
  sealed trait Command
  // AddDialog теперь включает имя кнопки (контекст)
  case class AddDialogEvent(buttonName: String, dialog: Dialog) extends Command
  // Убираем GetHistory и Save, так как состояние управляется извне
  // case class GetHistory(replyTo: ActorRef[List[Dialog]]) extends Command
  // case object Save extends Command
  // ---------------------------------------

  // --- ИЗМЕНЕНИЕ: Упрощенное поведение ---
  // apply() больше не принимает начальное состояние
  def apply(): Behavior[Command] = behavior()

  // Убираем параметр state (history) из behavior
  private def behavior(): Behavior[Command] =
    Behaviors.receive { (context, message) =>
      message match {
        // Обрабатываем AddDialogEvent, но пока просто логируем
        case AddDialogEvent(buttonName, dialog) =>
          logger.debug(s"HistoryService received AddDialogEvent for button '$buttonName'. Request: ${dialog.request.take(30)}...")
          // Здесь можно добавить логику, не связанную с хранением основного состояния,
          // например, отправку события в другой сервис, аналитику и т.д.
          // На данный момент ничего не делаем с состоянием.
          Behaviors.same // Остаемся в том же поведении

        // Убраны case для GetHistory и Save
      }
    }
  // ------------------------------------
}