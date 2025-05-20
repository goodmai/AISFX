package com.aiplatform.model

// Represents the state of a file considered for context.
sealed trait FileSelectionState {
  def filePath: String
}

object FileSelectionState {
  import upickle.default.*

  // Indicates a file successfully selected and its content read.
  case class Selected(filePath: String, content: String) extends FileSelectionState
  // Indicates an error occurred trying to process a selected file.
  case class SelectionError(filePath: String, error: String) extends FileSelectionState

  implicit val selectedRw: ReadWriter[Selected] = macroRW
  implicit val selectionErrorRw: ReadWriter[SelectionError] = macroRW
  implicit val rw: ReadWriter[FileSelectionState] = macroRW
}
