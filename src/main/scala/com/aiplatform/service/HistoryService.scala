package com.aiplatform.service

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import com.aiplatform.model.{AppState, Dialog}
import com.aiplatform.repository.StateRepository

object HistoryService {
  sealed trait Command
  case class AddDialog(dialog: Dialog) extends Command
  case class GetHistory(replyTo: ActorRef[List[Dialog]]) extends Command
  case object Save extends Command

  def apply(): Behavior[Command] = behavior(AppState.initialState.dialogHistory)

  private def behavior(history: List[Dialog]): Behavior[Command] =
    Behaviors.receive { (context, message) =>
      message match {
        case AddDialog(dialog) =>
          behavior(dialog :: history)
        case GetHistory(replyTo) =>
          replyTo ! history
          Behaviors.same
        case Save =>
          StateRepository.saveState(AppState.initialState.copy(dialogHistory = history))
          Behaviors.same
      }
    }
}