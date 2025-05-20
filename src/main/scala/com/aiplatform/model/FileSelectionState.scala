package com.aiplatform.model

// Represents the state of a file considered for context.
sealed trait FileSelectionState {
  def filePath: String
}

object FileSelectionState {
  // Indicates a file successfully selected and its content read.
  case class Selected(filePath: String, content: String) extends FileSelectionState
  // Indicates an error occurred trying to process a selected file.
  case class SelectionError(filePath: String, error: String) extends FileSelectionState
}
