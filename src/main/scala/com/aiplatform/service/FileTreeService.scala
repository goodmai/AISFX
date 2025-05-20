package com.aiplatform.service

import com.aiplatform.controller.manager.FileManager
import com.aiplatform.model.FileNode
import com.aiplatform.view.FileTreeView // May be needed for specific types if not refactored out
import javafx.scene.control.{TreeItem => JFXTreeItem, CheckBoxTreeItem => JFXCheckBoxTreeItem} // Keep JFX imports for now
import org.slf4j.LoggerFactory
import java.io.File
import scala.collection.mutable
import scala.util.Try

class FileTreeService(fileManager: FileManager) {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Scans a directory and builds a tree of FileNode objects.
   * This is based on the initial part of `loadDirectory` in FileTreeView.
   */
  def scanDirectoryStructure(directory: File): Try[FileNode] = {
    logger.info(s"Scanning directory structure for: ${directory.getAbsolutePath}")
    // This will delegate to FileManager, similar to how FileTreeView currently does.
    // The error handling and Platform.runLater parts will remain in FileTreeView for UI updates.
    fileManager.scanDirectory(directory)
  }

  /**
   * Creates a JavaFX CheckBoxTreeItem structure from a FileNode structure.
   * This is based on `createTreeItem` in FileTreeView.
   * The FileNode itself should already contain its children.
   */
  def createFxTreeItem(dataNode: FileNode): JFXCheckBoxTreeItem[FileNode] = {
    val item = new JFXCheckBoxTreeItem[FileNode](dataNode)
    // The 'independent' property of CheckBoxTreeItem determines if a parent's selection state
    // affects its children and vice-versa. Default is true (independent).
    // For typical file trees, children are often independent. If specific behavior is needed (e.g., parent selects all children),
    // this property could be set to false for children, or managed by a custom TreeCell.
    // item.setIndependent(false) // Example: if children should follow parent state.

    if (dataNode.isDirectory) {
      item.setExpanded(false) // Default state
      dataNode.children
        .sortBy(childNode => (!childNode.isDirectory, childNode.name.toLowerCase))
        .foreach(childDataNode => item.getChildren().add(createFxTreeItem(childDataNode))) // Recursive call
    }
    item
  }

  /**
   * Collects all FileNode objects that are marked as selected from a JavaFX TreeView root.
   * This is based on `collectSelectedFileNodes` in FileTreeView.
   * It traverses the JavaFX tree structure.
   */
  def collectSelectedFileNodesJfx(rootItem: JFXTreeItem[FileNode]): List[FileNode] = {
    val selectedNodes = mutable.ListBuffer[FileNode]()
    collectSelectedNodesRecursiveJfx(rootItem, selectedNodes)
    selectedNodes.toList
  }

  private def collectSelectedNodesRecursiveJfx(treeItem: JFXTreeItem[FileNode], acc: mutable.ListBuffer[FileNode]): Unit = {
    if (treeItem == null || treeItem.getValue == null) return

    // Assuming selection is determined by the FileNode's own 'selectedProperty'
    // which is bound to the CheckBoxTreeItem's selected state.
    val fileNode = treeItem.getValue
    if (fileNode.isSelected) {
      acc.append(fileNode)
    }

    val children = treeItem.getChildren
    for (i <- 0 until children.size()) {
      collectSelectedNodesRecursiveJfx(children.get(i), acc)
    }
  }

  /**
   * Reads the content of a given FileNode.
   * Delegates to FileManager.
   */
  def readFileContent(fileNode: FileNode): Try[String] = {
    logger.info(s"Reading content for file: ${fileNode.path}")
    if (fileNode.isFile) {
      fileManager.readFileContent(fileNode.file)
    } else {
      Try(throw new IllegalArgumentException("Cannot read content of a directory."))
    }
  }
}
