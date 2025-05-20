// src/main/scala/com/aiplatform/view/FileTreeView.scala
package com.aiplatform.view

import com.aiplatform.controller.MainController
import com.aiplatform.controller.manager.FileManager
import com.aiplatform.model.{FileNode, FileSelectionState, FileTreeContext} // Added FileSelectionState, FileTreeContext
import com.aiplatform.service.FileTreeService // Added FileTreeService

import javafx.scene.control.{TreeItem => JFXTreeItem, CheckBoxTreeItem => JFXCheckBoxTreeItem}
import javafx.scene.control.cell.CheckBoxTreeCell
import javafx.scene.Node // JavaFX graphic node
import javafx.scene.layout.StackPane
import javafx.scene.shape.SVGPath
import javafx.scene.paint.Color

import scalafx.Includes._
import scalafx.application.Platform
import scalafx.scene.control._
import scalafx.scene.layout.{BorderPane, VBox}
import scalafx.scene.Parent
import scalafx.stage.DirectoryChooser
import org.slf4j.LoggerFactory
import java.io.File
import scala.collection.mutable
import scala.util.{Failure, Success}


class FileTreeView(
                    fileManager: FileManager, // Retained for now, though FileTreeService might encapsulate it later
                    mainController: MainController,
                    fileTreeService: FileTreeService // Added FileTreeService
                  ) {
  private val logger = LoggerFactory.getLogger(getClass)

  private var currentRootDataNode: Option[FileNode] = None // Represents the data root of the currently displayed directory

  private object Icons {
    private def createIcon(svgContent: String, color: Color): Node = {
      val path = new SVGPath()
      path.setContent(svgContent)
      path.setFill(color)
      val stackPane = new StackPane(path)
      stackPane.setPrefSize(18, 18)
      stackPane.setMinSize(18,18)
      stackPane.setMaxSize(18,18)
      stackPane
    }
    def folderIcon: Node = createIcon("M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z", Color.GOLDENROD)
    def fileIcon: Node = createIcon("M6 2c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6H6zm7 7V3.5L18.5 9H13z", Color.SLATEGRAY)
  }

  private val jfxTreeView = new javafx.scene.control.TreeView[FileNode]()
  jfxTreeView.getStyleClass().add("file-tree-view")

  jfxTreeView.setCellFactory((_: javafx.scene.control.TreeView[FileNode]) => {
    val cell = new CheckBoxTreeCell[FileNode](
      (item: JFXTreeItem[FileNode]) => {
        if (item != null && item.getValue() != null) {
          item.getValue().selectedProperty
        } else {
          null
        }
      }
    )
    cell.treeItemProperty().addListener((_, _, treeItem) => {
      if (treeItem != null && treeItem.getValue() != null) {
        val fileNode = treeItem.getValue()
        cell.setGraphic(if (fileNode.isDirectory) Icons.folderIcon else Icons.fileIcon)
      } else {
        cell.setGraphic(null)
      }
    })
    cell
  })

  private val treeView: TreeView[FileNode] = new TreeView(jfxTreeView)

  // Buttons are now arranged vertically.
  private val openDirectoryButton = new Button("Open Directory...") { // Translated
    onAction = _ => handleOpenDirectory()
    styleClass.add("file-tree-button")
    maxWidth = Double.MaxValue // To make the button stretch to the width of the VBox.
  }

  private val refreshButton = new Button("Refresh") { // Translated
    onAction = _ => currentRootDataNode.foreach(node => loadDirectory(node.file, isRefresh = true))
    styleClass.add("file-tree-button")
    disable = true
    maxWidth = Double.MaxValue
  }

  private val clearSelectionButton = new Button("Clear Selection") { // Translated
    onAction = _ => {
      Option(jfxTreeView.getRoot()).foreach(clearTreeItemSelection)
      // Also clear the context in AppState if user clears selection manually
      mainController.clearFileTreeContextFromView()
    }
    styleClass.add("file-tree-button")
    maxWidth = Double.MaxValue
  }

  private val addContextButton = new Button("Add to Context") { // Translated
    onAction = _ => handleAddSelectedFilesToContext()
    styleClass.add("file-tree-add-context-button")
    tooltip = Tooltip("Add the content of selected files to the request context.") // Translated
    maxWidth = Double.MaxValue
  }

  private def handleOpenDirectory(): Unit = {
    val directoryChooser = new DirectoryChooser { title = "Select Root Directory to Display" } // Translated
    mainController.getMainStage match {
      case Some(sfxStage) =>
        val selectedDir = Option(directoryChooser.showDialog(sfxStage.delegate))
        selectedDir.foreach(dir => loadDirectory(dir, isRefresh = false))
      case None => logger.warn("Failed to get parent window for directory chooser dialog (mainController.getMainStage returned None).") // Translated
    }
  }

  private def loadDirectory(directory: File, isRefresh: Boolean): Unit = {
    logger.info(s"Loading directory into tree: ${directory.getAbsolutePath}")
    fileTreeService.scanDirectoryStructure(directory) match { // Uses fileTreeService
      case Success(scannedRootNode) =>
        currentRootDataNode = Some(scannedRootNode)
        Platform.runLater {
          val rootTreeItem = fileTreeService.createFxTreeItem(scannedRootNode) // Uses fileTreeService
          rootTreeItem.setExpanded(true)
          // Ensure independent selection for children by default for better UX
          // This can be set in FileTreeService or here. Let's assume service sets it if desirable.
          // rootTreeItem.setIndependent(false) // Usually root is not independent if children control parent state.
          // Iterating children to set them as independent if needed by design:
          // rootTreeItem.getChildren.forEach(child => child.asInstanceOf[JFXCheckBoxTreeItem[FileNode]].setIndependent(true))

          jfxTreeView.setRoot(rootTreeItem)
          if (!isRefresh) {
            // Optional: clear previous selection state or context
            // mainController.clearFileTreeContextFromView() // If new dir means new context
          }
          refreshButton.disable = false
          logger.info(s"Directory tree for '${directory.getName}' successfully built and displayed.") // Translated
        }
      case Failure(e) =>
        logger.error(s"Error scanning directory ${directory.getAbsolutePath}", e)
        Platform.runLater(
          DialogUtils.showError(s"Failed to load directory '${directory.getName}': ${e.getMessage()}", mainController.getMainStage) // Translated
        )
    }
  }

  // createTreeItem method removed, functionality moved to FileTreeService

  private def clearTreeItemSelection(treeItem: JFXTreeItem[FileNode]): Unit = {
    if (treeItem == null) return
    treeItem match {
      case cbItem: JFXCheckBoxTreeItem[FileNode] =>
        cbItem.setSelected(false)
        if (cbItem.getValue() != null) cbItem.getValue().setSelected(false)
      case _ =>
    }
    val children = treeItem.getChildren()
    for (i <- 0 until children.size()) {
      clearTreeItemSelection(children.get(i))
    }
  }

  private def handleAddSelectedFilesToContext(): Unit = {
    val selectedFileNodesJfx = Option(jfxTreeView.getRoot()) match {
      case Some(root) => fileTreeService.collectSelectedFileNodesJfx(root) // Uses fileTreeService
      case None       => List.empty[FileNode]
    }

    if (selectedFileNodesJfx.isEmpty) {
      Platform.runLater(
        DialogUtils.showInfo("No files selected to add to context.", mainController.getMainStage) // Translated
      )
      return
    }

    logger.info(s"Processing ${selectedFileNodesJfx.size} selected items for context.")

    val selectionStates = selectedFileNodesJfx.filter(_.isFile).map { fileNode =>
      fileTreeService.readFileContent(fileNode) match { // Uses fileTreeService
        case Success(content) =>
          FileSelectionState.Selected(fileNode.path, content)
        case Failure(e) =>
          val errorMsg = s"Failed to read content for ${fileNode.name}: ${e.getMessage}"
          logger.error(s"Error reading file ${fileNode.path} for context.", e)
          FileSelectionState.SelectionError(fileNode.path, errorMsg)
      }
    }

    val fileContext = FileTreeContext(selectionStates)
    mainController.updateFileTreeContext(fileContext) // New call to MainController
    // Old logic of appending to input area is removed.
  }

  // collectSelectedFileNodes method removed, functionality moved to FileTreeService

  /**
   * The root node of this UI component.
   * Control buttons are now arranged vertically above the tree.
   */
  def viewNode: Parent = {
    // Buttons are now in a VBox.
    val controlButtonsVBox = new VBox {
      children = Seq(openDirectoryButton, refreshButton, clearSelectionButton, addContextButton)
      spacing = 5 // Spacing between buttons
      padding = scalafx.geometry.Insets(5, 5, 10, 5) // Padding: top, right, bottom, left
      alignment = scalafx.geometry.Pos.TopCenter // Align buttons to the center of VBox
    }

    new BorderPane {
      styleClass.add("file-tree-view-pane")
      top = controlButtonsVBox // Vertical button block at the top.
      center = treeView
    }
  }
}