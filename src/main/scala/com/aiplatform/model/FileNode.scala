package com.aiplatform.model

import java.io.File

// Assuming NodeType is defined in the same package or imported correctly.
// sealed trait NodeType
// object NodeType {
//   case object File extends NodeType
//   case object Directory extends NodeType
// }

/**
 * Represents a node in a file tree.
 *
 * @param file The actual java.io.File represented by this node.
 * @param nodeType The type of the node (File or Directory).
 * @param children A list of child nodes, empty for files.
 */
case class FileNode(
                     file: File, // The actual file system object
                     nodeType: NodeType,
                     children: List[FileNode] = List.empty // Default to empty list, especially for files
                   ) {
  // Derived properties based on the 'file' object
  def name: String = file.getName
  def isDirectory: Boolean = file.isDirectory
  // Path can be accessed via file.getPath or file.getAbsolutePath
}

object FileNode {
  /**
   * Helper constructor to create a FileNode from a java.io.File.
   * Determines NodeType and initializes children to empty (can be populated later for directories).
   */
  def apply(file: File, children: List[FileNode] = List.empty): FileNode = {
    val nodeType = if (file.isDirectory) NodeType.Directory else NodeType.File
    // If it's a directory but children are not provided, they remain empty.
    // The expectation is that FileManager will populate children for directories.
    new FileNode(file, nodeType, children)
  }

  /**
   * Overloaded apply for convenience when children are not immediately known or for files.
   */
  def apply(file: File): FileNode = {
    val nodeType = if (file.isDirectory) NodeType.Directory else NodeType.File
    new FileNode(file, nodeType, List.empty)
  }
}
