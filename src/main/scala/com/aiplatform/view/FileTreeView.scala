// src/main/scala/com/aiplatform/view/FileTreeView.scala
package com.aiplatform.view

import com.aiplatform.controller.MainController
import com.aiplatform.controller.manager.FileManager
import com.aiplatform.model.FileNode

import javafx.scene.control.{TreeItem => JFXTreeItem, CheckBoxTreeItem => JFXCheckBoxTreeItem}
import javafx.scene.control.cell.CheckBoxTreeCell
import javafx.scene.Node // Графический узел JavaFX
import javafx.scene.layout.StackPane
import javafx.scene.shape.SVGPath
import javafx.scene.paint.Color

import scalafx.Includes._
import scalafx.application.Platform
import scalafx.scene.control._
import scalafx.scene.layout.{BorderPane, VBox} // VBox уже импортирован
import scalafx.scene.Parent
import scalafx.stage.DirectoryChooser
import org.slf4j.LoggerFactory
import java.io.File
import scala.collection.mutable
import scala.util.{Failure, Success}


class FileTreeView(fileManager: FileManager, mainController: MainController) {
  private val logger = LoggerFactory.getLogger(getClass)

  private var currentRootDataNode: Option[FileNode] = None

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

  // Кнопки теперь будут располагаться вертикально
  private val openDirectoryButton = new Button("Открыть каталог...") {
    onAction = _ => handleOpenDirectory()
    styleClass.add("file-tree-button")
    maxWidth = Double.MaxValue // Чтобы кнопка растягивалась по ширине VBox
  }

  private val refreshButton = new Button("Обновить") {
    onAction = _ => currentRootDataNode.foreach(node => loadDirectory(node.file, isRefresh = true))
    styleClass.add("file-tree-button")
    disable = true
    maxWidth = Double.MaxValue
  }

  private val clearSelectionButton = new Button("Снять выделение") {
    onAction = _ => Option(jfxTreeView.getRoot()).foreach(clearTreeItemSelection)
    styleClass.add("file-tree-button")
    maxWidth = Double.MaxValue
  }

  private val addContextButton = new Button("Добавить контекст") { // Текст немного сокращен
    onAction = _ => handleAddSelectedFilesToContext()
    styleClass.add("file-tree-add-context-button") // Можно задать отдельный стиль, если нужно
    tooltip = Tooltip("Добавить содержимое отмеченных файлов в поле ввода основного запроса.")
    maxWidth = Double.MaxValue
  }

  private def handleOpenDirectory(): Unit = {
    val directoryChooser = new DirectoryChooser { title = "Выберите корневой каталог для отображения" }
    mainController.getMainStage match {
      case Some(sfxStage) =>
        val selectedDir = Option(directoryChooser.showDialog(sfxStage.delegate))
        selectedDir.foreach(dir => loadDirectory(dir, isRefresh = false))
      case None => logger.warn("Не удалось получить родительское окно для диалога выбора каталога (mainController.getMainStage вернул None).")
    }
  }

  private def loadDirectory(directory: File, isRefresh: Boolean): Unit = {
    logger.info("Загрузка каталога в дерево: {}", directory.getAbsolutePath())
    fileManager.scanDirectory(directory) match {
      case Success(scannedRootNode) =>
        currentRootDataNode = Some(scannedRootNode)
        Platform.runLater {
          val rootTreeItem = createTreeItem(scannedRootNode)
          rootTreeItem.setExpanded(true)
          jfxTreeView.setRoot(rootTreeItem)
          if (!isRefresh) {
            // clearTreeItemSelection(rootTreeItem)
          }
          refreshButton.disable = false
          logger.info("Дерево каталогов для '{}' успешно построено и отображено.", directory.getName())
        }
      case Failure(e) =>
        logger.error(s"Ошибка при сканировании каталога ${directory.getAbsolutePath()}", e)
        Platform.runLater(
          DialogUtils.showError(s"Не удалось загрузить каталог '${directory.getName()}': ${e.getMessage()}", mainController.getMainStage)
        )
    }
  }

  private def createTreeItem(dataNode: FileNode): JFXCheckBoxTreeItem[FileNode] = {
    val item = new JFXCheckBoxTreeItem[FileNode](dataNode)
    item.setIndependent(false)

    if (dataNode.isDirectory) {
      item.setExpanded(false)
      dataNode.children
        .sortBy(childNode => (!childNode.isDirectory, childNode.name.toLowerCase))
        .foreach(childDataNode => item.getChildren().add(createTreeItem(childDataNode)))
    }
    item
  }

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
    val selectedFileNodes = mutable.ListBuffer[FileNode]()
    Option(jfxTreeView.getRoot()).foreach(collectSelectedFileNodes(_, selectedFileNodes))

    if (selectedFileNodes.isEmpty) {
      Platform.runLater(
        DialogUtils.showInfo("Нет выбранных файлов для добавления в контекст.", mainController.getMainStage)
      )
      return
    }

    logger.info("Добавление контекста из {} выбранных элементов.", selectedFileNodes.size)
    val contextBuilder = new StringBuilder()
    contextBuilder.append("\n\n--- Начало Контекста из Выбранных Файлов ---\n")

    selectedFileNodes.filter(_.isFile).foreach { fileNode =>
      contextBuilder.append(s"\n### Файл: ${fileNode.path}\n")
      fileManager.readFileContent(fileNode.file) match {
        case Success(content) =>
          contextBuilder.append("```\n")
          contextBuilder.append(content.trim())
          contextBuilder.append("\n```\n")
        case Failure(e) =>
          val errorMsg = s"[Не удалось прочитать содержимое файла ${fileNode.name}: ${e.getMessage()}]\n"
          contextBuilder.append(errorMsg)
          logger.error(s"Ошибка чтения файла ${fileNode.path} для контекста.", e)
      }
    }
    contextBuilder.append("\n--- Конец Контекста из Выбранных Файлов ---\n")
    mainController.appendToInputArea(contextBuilder.toString())
  }

  private def collectSelectedFileNodes(treeItem: JFXTreeItem[FileNode], acc: mutable.ListBuffer[FileNode]): Unit = {
    if (treeItem == null || treeItem.getValue() == null) return

    val fileNode = treeItem.getValue()
    if (fileNode.isSelected) {
      acc.append(fileNode)
    }
    val children = treeItem.getChildren()
    for (i <- 0 until children.size()) {
      collectSelectedFileNodes(children.get(i), acc)
    }
  }

  /**
   * Корневой узел этого UI компонента.
   * Кнопки управления теперь располагаются вертикально над деревом.
   */
  def viewNode: Parent = {
    // ИСПРАВЛЕНО: Кнопки теперь в VBox
    val controlButtonsVBox = new VBox {
      children = Seq(openDirectoryButton, refreshButton, clearSelectionButton, addContextButton)
      spacing = 5 // Отступ между кнопками
      padding = scalafx.geometry.Insets(5, 5, 10, 5) // Отступы: сверху, справа, снизу, слева
      alignment = scalafx.geometry.Pos.TopCenter // Выравнивание кнопок по центру VBox
    }

    new BorderPane {
      styleClass.add("file-tree-view-pane")
      top = controlButtonsVBox // Вертикальный блок кнопок сверху
      center = treeView
    }
  }
}