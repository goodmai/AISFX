package com.aiplatform.model

// Represents the collection of files selected from the FileTreeView.
case class FileTreeContext(
  // List of states for files that were part of the selection attempt.
  selectedFiles: List[FileSelectionState]
)

object FileTreeContext {
  import upickle.default.*
  implicit val rw: ReadWriter[FileTreeContext] = macroRW

  // Companion object for potential future utility methods, e.g., empty context.
  def empty: FileTreeContext = FileTreeContext(List.empty)
}
