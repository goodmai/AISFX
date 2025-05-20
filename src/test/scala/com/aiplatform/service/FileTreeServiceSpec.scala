package com.aiplatform.service

import com.aiplatform.controller.manager.FileManager
import com.aiplatform.model.{FileNode, NodeType} // Assuming NodeType is in com.aiplatform.model
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import org.mockito.Mockito._
// import org.mockito.ArgumentMatchers.any // Not strictly needed if not using any() matcher for File

import java.io.File
import java.nio.file.{Files => JFiles, Paths} // Alias Files to JFiles to avoid collision if Scala Files is used
import scala.util.{Success, Failure, Try}

class FileTreeServiceSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  // Helper to create temporary files/directories for testing
  def createTempDir(prefix: String): File = {
    val dir = JFiles.createTempDirectory(prefix).toFile
    dir.deleteOnExit() // Schedule cleanup
    dir
  }

  def createTempFile(dir: File, name: String, content: String = ""): File = {
    val file = new File(dir, name)
    JFiles.writeString(file.toPath, content)
    file.deleteOnExit() // Schedule cleanup
    file
  }

  // Mock FileManager for most tests
  val mockFileManager: FileManager = mock[FileManager]
  val fileTreeService: FileTreeService = new FileTreeService(mockFileManager)

  behavior of "FileTreeService"

  it should "scan a directory structure using FileManager" in {
    val testDirFile = new File("testScanDir") // Use a File object
    // FileNode's primary constructor: file: File, nodeType: NodeType, children: List[FileNode]
    val expectedFileNode = FileNode(testDirFile, NodeType.Directory, Nil)
    when(mockFileManager.scanDirectory(testDirFile)).thenReturn(Success(expectedFileNode))

    val result = fileTreeService.scanDirectoryStructure(testDirFile)
    result shouldBe Success(expectedFileNode)
    verify(mockFileManager).scanDirectory(testDirFile)
  }

  it should "return Failure when FileManager fails to scan directory" in {
    val testDirFile = new File("testScanFailDir")
    val exception = new RuntimeException("Scan failed")
    when(mockFileManager.scanDirectory(testDirFile)).thenReturn(Failure(exception))

    val result = fileTreeService.scanDirectoryStructure(testDirFile)
    result.isFailure shouldBe true
    result.failed.get shouldBe exception
    verify(mockFileManager).scanDirectory(testDirFile)
  }

  // Tests for createFxTreeItem are more suitable for integration/UI testing
  // due to their dependency on JavaFX UI classes (JFXCheckBoxTreeItem).
  // Skipping direct unit tests for createFxTreeItem here.

  it should "read file content using FileManager for a file" in {
    val tempDir = createTempDir("readTest")
    val testFile = createTempFile(tempDir, "test.txt", "Hello World")
    // FileNode's primary constructor: file: File, nodeType: NodeType, children: List[FileNode]
    val fileNode = FileNode(testFile, NodeType.File, Nil)
    val expectedContent = "Hello World"

    when(mockFileManager.readFileContent(testFile)).thenReturn(Success(expectedContent))

    val result = fileTreeService.readFileContent(fileNode)
    result shouldBe Success(expectedContent)
    verify(mockFileManager).readFileContent(testFile)
    // tempDir and testFile will be deleted via deleteOnExit()
  }

  it should "return Failure when reading content for a directory" in {
    val tempDir = createTempDir("readDirTest")
    // FileNode's primary constructor: file: File, nodeType: NodeType, children: List[FileNode]
    val dirNode = FileNode(tempDir, NodeType.Directory, Nil)

    val result = fileTreeService.readFileContent(dirNode)
    result.isFailure shouldBe true
    result.failed.get shouldBe an[IllegalArgumentException]
    result.failed.get.getMessage shouldBe "Cannot read content of a directory."
    verify(mockFileManager, never()).readFileContent(org.mockito.ArgumentMatchers.any[File]) // Ensure it doesn't try to read
    // tempDir will be deleted via deleteOnExit()
  }

  it should "return Failure when FileManager fails to read file content" in {
    val tempDir = createTempDir("readFailTest")
    val testFile = createTempFile(tempDir, "test.txt")
    // FileNode's primary constructor: file: File, nodeType: NodeType, children: List[FileNode]
    val fileNode = FileNode(testFile, NodeType.File, Nil)
    val exception = new RuntimeException("Read error")

    when(mockFileManager.readFileContent(testFile)).thenReturn(Failure(exception))

    val result = fileTreeService.readFileContent(fileNode)
    result.isFailure shouldBe true
    result.failed.get shouldBe exception
    verify(mockFileManager).readFileContent(testFile)
    // tempDir and testFile will be deleted via deleteOnExit()
  }

  // Tests for collectSelectedFileNodesJfx also depend on JFX TreeItem structure.
  // These are better suited for UI/integration tests or require complex JFX mocking.
  // Skipping direct unit tests for collectSelectedFileNodesJfx.

  // Conceptual test for collectSelectedFileNodesJfx (if JFX items could be easily mocked):
  /*
  import javafx.scene.control.{TreeItem => JFXTreeItem, CheckBoxTreeItem => JFXCheckBoxTreeItem}

  it should "collect selected file nodes from JFX tree structure (conceptual)" in {
    val rootDirFile = createTempDir("collectSelectedRoot")
    val childFile1 = createTempFile(rootDirFile, "child1.txt")
    val childDir2 = createTempDir("childDir2") // Sibling to rootDirFile for simplicity of test structure here
    val grandchildFile3 = createTempFile(childDir2, "grandchild3.txt")


    val nodeRoot = FileNode(rootDirFile, NodeType.Directory)
    val nodeChild1 = FileNode(childFile1, NodeType.File)
    nodeChild1.setSelected(true) // Mark as selected
    val nodeChild2Dir = FileNode(childDir2, NodeType.Directory)
    val nodeGrandchild3 = FileNode(grandchildFile3, NodeType.File)
    nodeGrandchild3.setSelected(true) // Mark as selected

    // --- Mocking JFX TreeItems (this is the complex part) ---
    // This requires a way to instantiate or mock JFXCheckBoxTreeItem and its methods like
    // getValue, getChildren, etc. This is non-trivial without a JFX environment.
    // For true unit testing, one might need a JFX toolkit initialized or use a library like TestFX
    // which is more for integration/UI tests.

    // Assuming we could mock them:
    // val mockRootJfxItem = mock[JFXCheckBoxTreeItem[FileNode]]
    // val mockChild1JfxItem = mock[JFXCheckBoxTreeItem[FileNode]]
    // val mockChild2DirJfxItem = mock[JFXCheckBoxTreeItem[FileNode]]
    // val mockGrandchild3JfxItem = mock[JFXCheckBoxTreeItem[FileNode]]

    // when(mockRootJfxItem.getValue).thenReturn(nodeRoot)
    // when(mockRootJfxItem.getChildren).thenReturn(javafx.collections.FXCollections.observableArrayList(mockChild1JfxItem, mockChild2DirJfxItem))
    
    // when(mockChild1JfxItem.getValue).thenReturn(nodeChild1)
    // when(mockChild1JfxItem.getChildren).thenReturn(javafx.collections.FXCollections.emptyObservableList())

    // when(mockChild2DirJfxItem.getValue).thenReturn(nodeChild2Dir)
    // when(mockChild2DirJfxItem.getChildren).thenReturn(javafx.collections.FXCollections.observableArrayList(mockGrandchild3JfxItem))
    
    // when(mockGrandchild3JfxItem.getValue).thenReturn(nodeGrandchild3)
    // when(mockGrandchild3JfxItem.getChildren).thenReturn(javafx.collections.FXCollections.emptyObservableList())
    // --- End of JFX Mocking ---

    // If mocking was successful:
    // val selectedNodes = fileTreeService.collectSelectedFileNodesJfx(mockRootJfxItem)
    // selectedNodes should have size 2
    // selectedNodes should contain the FileNode representing childFile1
    // selectedNodes should contain the FileNode representing grandchildFile3
    // selectedNodes shouldnot contain nodeRoot
    // selectedNodes shouldnot contain nodeChild2Dir

    // Cleanup (deleteOnExit was scheduled, but direct deletion is cleaner for tests if possible)
    grandchildFile3.delete()
    childDir2.delete()
    childFile1.delete()
    rootDirFile.delete()
  }
  */
}
