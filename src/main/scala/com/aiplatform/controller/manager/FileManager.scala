// src/main/scala/com/aiplatform/controller/manager/FileManager.scala
package com.aiplatform.controller.manager

import com.aiplatform.model.{FileNode, NodeType}
import com.aiplatform.view.{DialogUtils, Footer}
import javafx.stage.{DirectoryChooser, FileChooser}
import org.slf4j.LoggerFactory
import scalafx.application.Platform
import scalafx.stage.Stage

import java.io.{File, IOException}
import java.nio.file.{Files, Path, Paths}
import scala.io.Source
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try, Using}
import scala.util.control.NonFatal

class FileManager(ownerStage: Stage, footerRefOption: => Option[Footer]) {
  private val logger = LoggerFactory.getLogger(getClass)

  private val MAX_FILE_SIZE_BYTES: Int = 500000
  private val MAX_SCAN_DEPTH: Int = 15
  private val GEMINI_IGNORE_FILENAME: String = ".geminiignore"

  private val defaultExclusions: Set[String] = Set(
    ".git", ".svn", ".hg",
    ".idea", ".vscode", ".project", ".classpath", ".settings",
    "target", "build", "dist", "out",
    "node_modules", "vendor", "bower_components",
    "__pycache__", ".DS_Store", ".AppleDouble", "Thumbs.db",
    "*.class", "*.pyc", "*.pyo",
    "*.jar", "*.war", "*.ear", "*.zip", "*.tar", "*.gz", "*.rar", "*.7z",
    "*.log",
    "*.tmp", "*.temp", "*.bak", "*.swp", "*.swo"
  )

  private def loadIgnorePatterns(scanRootDir: File): Set[String] = {
    val ignoreFilePath: Path = Paths.get(scanRootDir.getAbsolutePath, GEMINI_IGNORE_FILENAME)
    if (Files.exists(ignoreFilePath) && Files.isReadable(ignoreFilePath)) {
      Try {
        Using.resource(Source.fromFile(ignoreFilePath.toFile)("UTF-8")) { source =>
          source.getLines()
            .map(_.trim)
            .filter(line => line.nonEmpty && !line.startsWith("#"))
            .toSet
        }
      } match {
        case Success(patterns) =>
          logger.info("Загружены {} паттернов из файла {}", patterns.size, ignoreFilePath.toString)
          patterns
        case Failure(e) =>
          logger.error(s"Ошибка чтения файла ${ignoreFilePath.toString}. Используются только стандартные исключения.", e)
          Set.empty[String]
      }
    } else {
      logger.debug("Файл {} не найден в {}. Используются только стандартные исключения.", GEMINI_IGNORE_FILENAME, scanRootDir.getAbsolutePath)
      Set.empty[String]
    }
  }

  private def isExcluded(file: File, customIgnorePatterns: Set[String], scanRootDir: File): Boolean = {
    val name = file.getName
    val path = file.getAbsolutePath
    val relativePath = Try(scanRootDir.toPath.relativize(file.toPath).toString.replace(File.separatorChar, '/')).getOrElse(name)
    val isDir = file.isDirectory

    val isEffectivelyHidden = file.isHidden &&
      !path.equals(System.getProperty("user.home")) &&
      !File.listRoots().exists(root => path.equals(root.getAbsolutePath))
    if (isEffectivelyHidden && name != "." && name != "..") {
      logger.trace("Исключение скрытого элемента: {}", path)
      return true
    }

    val excludedByDefault = defaultExclusions.exists { pattern =>
      (pattern.startsWith("*.") && name.endsWith(pattern.substring(1))) || pattern == name
    }
    if (excludedByDefault) {
      logger.trace("Исключение по стандартному паттерну для: {}", path)
      return true
    }

    customIgnorePatterns.exists { pattern =>
      val patternIsDirectoryOnly = pattern.endsWith("/")
      val effectivePattern = if (patternIsDirectoryOnly) pattern.dropRight(1) else pattern

      val matches: Boolean =
        if (pattern.startsWith("/")) {
          val rootAnchoredPattern = effectivePattern.drop(1)
          if (patternIsDirectoryOnly) isDir && relativePath == rootAnchoredPattern
          else relativePath == rootAnchoredPattern || (!isDir && name == rootAnchoredPattern)
        } else if (pattern.contains("/")) {
          if (patternIsDirectoryOnly) isDir && relativePath.startsWith(effectivePattern)
          else relativePath.startsWith(effectivePattern)
        } else {
          if (patternIsDirectoryOnly) isDir && name == effectivePattern
          else if (effectivePattern.startsWith("*.")) !isDir && name.endsWith(effectivePattern.substring(1))
          else name == effectivePattern
        }
      if (matches) logger.trace("Исключение по пользовательскому паттерну '{}' для: {}", pattern, path)
      matches
    }
  }

  def scanDirectory(
                     directory: File,
                     currentDepth: Int = 0,
                     initialCustomPatterns: Option[Set[String]] = None
                   ): Try[FileNode] = Try {
    if (!directory.isDirectory) {
      throw new IllegalArgumentException(s"Путь не является каталогом: ${directory.getAbsolutePath}")
    }

    val effectiveCustomPatterns = initialCustomPatterns.getOrElse {
      if (currentDepth == 0) loadIgnorePatterns(directory) else Set.empty[String]
    }

    val parentForExclusionCheck = if (currentDepth == 0) directory else Option(directory.getParentFile).getOrElse(directory)

    if (currentDepth > 0 && isExcluded(directory, effectiveCustomPatterns, parentForExclusionCheck )) {
      logger.debug("Каталог {} на глубине {} исключен правилами.", directory.getAbsolutePath, currentDepth)
      throw new Exception(s"Каталог ${directory.getName} исключен.")
    }

    if (currentDepth > MAX_SCAN_DEPTH) {
      logger.warn("Достигнута максимальная глубина сканирования ({}) для каталога: {}", MAX_SCAN_DEPTH, directory.getAbsolutePath)
      FileNode(directory, NodeType.Directory, children = List.empty) // Это значение будет обернуто в Success внешним Try
    }

    val childrenNodes: List[FileNode] =
      Option(directory.listFiles())
        .map(_.toList)
        .getOrElse(List.empty[File])
        .filterNot(f =>
          isExcluded(f, effectiveCustomPatterns, directory)
        )
        .flatMap { file =>
          val nodeTry: Try[FileNode] = if (file.isDirectory) {
            scanDirectory(file, currentDepth + 1, Some(effectiveCustomPatterns))
          } else {
            Success(FileNode(file, NodeType.File))
          }

          nodeTry.recoverWith {
            case ex: Exception if ex.getMessage != null && ex.getMessage.endsWith("исключен.") =>
              logger.debug("Подкаталог {} был исключен рекурсией: {}", file.getName, ex.getMessage)
              Failure(ex)
            case NonFatal(e) =>
              logger.error("Не удалось обработать элемент ФС {}: {}", file.getAbsolutePath, e.getMessage)
              Failure(e)
          }.toOption
        }
        .sortBy(node => (!node.isDirectory, node.name.toLowerCase))

    FileNode(directory, NodeType.Directory, children = childrenNodes)
  }


  def readFileContent(file: File): Try[String] = {
    if (!file.exists() || !file.isFile) {
      return Failure(new java.io.FileNotFoundException(s"Файл не найден или не является файлом: ${file.getAbsolutePath}"))
    }
    val parentDir = file.getParentFile
    val scanRootDirForExclusion = Option(parentDir).getOrElse(file)
    val customPatterns = if (parentDir != null) loadIgnorePatterns(parentDir) else Set.empty[String]


    if (isExcluded(file, customPatterns, scanRootDirForExclusion)) {
      logger.debug("Чтение файла {} пропущено (исключен).", file.getName)
      return Success(s"[Содержимое файла ${file.getName} не отображается (исключен)]")
    }

    Try {
      Using.resource(Source.fromFile(file)("UTF-8")) { source =>
        val contentChars = new Array[Char](MAX_FILE_SIZE_BYTES)
        val readChars = source.reader().read(contentChars)

        if (file.length() > MAX_FILE_SIZE_BYTES) {
          logger.warn(
            "Содержимое файла {} было больше {} байт и было обрезано. Прочитано {} символов.",
            file.getName,
            MAX_FILE_SIZE_BYTES,
            readChars
          )
          new String(contentChars, 0, readChars) +
            s"\n\n... (файл ${file.getName} обрезан, полный размер: ${file.length()} байт)"
        } else {
          new String(contentChars, 0, readChars)
        }
      }
    }.recoverWith {
      case NonFatal(e) =>
        logger.error(s"Ошибка чтения файла ${file.getName}: ${e.getMessage}", e)
        Failure(new IOException(s"Ошибка чтения файла ${file.getName}: ${e.getMessage}", e))
    }
  }

  def attachTextFile(): Unit = {
    logger.debug("FileManager.attachTextFile: Вызван диалог выбора файла.")
    val fileChooser = new FileChooser(); fileChooser.setTitle("Выберите текстовый файл")
    fileChooser.getExtensionFilters.addAll(
      new FileChooser.ExtensionFilter("Text Files", "*.txt", "*.md", "*.scala", "*.java", "*.py", "*.js", "*.ts",
        "*.html", "*.css", "*.xml", "*.json", "*.yaml", "*.yml",
        "*.log", "*.csv", "*.conf", "*.properties", "*.sh", "*.bat"),
      new FileChooser.ExtensionFilter("All Files", "*.*")
    )
    Option(fileChooser.showOpenDialog(ownerStage.delegate)).foreach(readFileAndAppendToFooter)
  }

  def attachFolderContext(): Unit = {
    logger.debug("FileManager.attachFolderContext: Вызван диалог выбора каталога.")
    val directoryChooser = new DirectoryChooser(); directoryChooser.setTitle("Выберите папку для контекста")
    Option(directoryChooser.showDialog(ownerStage.delegate)).foreach { dir =>
      val customPatterns = loadIgnorePatterns(dir)
      if (!isExcluded(dir, customPatterns, dir)) {
        listDirectoryAndAppendToFooter(dir, customPatterns)
      } else {
        val errorMsg = s"Каталог ${dir.getName} исключен из обработки согласно $GEMINI_IGNORE_FILENAME или стандартным правилам."
        logger.warn(errorMsg)
        Platform.runLater(DialogUtils.showError(errorMsg, Some(ownerStage)))
      }
    }
  }

  def readFileAndAppendToFooter(file: File): Unit = {
    val parentDir = file.getParentFile
    val scanRootDirForExclusion = Option(parentDir).getOrElse(file)
    val customPatterns = if (parentDir != null) loadIgnorePatterns(parentDir) else Set.empty[String]

    if (!isExcluded(file, customPatterns, scanRootDirForExclusion)) {
      logger.info("Чтение файла для Footer: {}", file.getAbsolutePath)
      readFileContent(file) match {
        case Success(content) =>
          val textToInsert = formatFileContentForInputFooter(file.getName, content)
          appendTextToFooter(textToInsert)
        case Failure(e) =>
          val errorMsg = s"Не удалось прочитать файл ${file.getName}: ${e.getMessage}"
          logger.error(s"Ошибка чтения ${file.getAbsolutePath}", e)
          Platform.runLater(DialogUtils.showError(errorMsg, Some(ownerStage)))
      }
    } else {
      val errorMsg = s"Файл ${file.getName} исключен из обработки."
      logger.warn(errorMsg)
      Platform.runLater(DialogUtils.showError(errorMsg, Some(ownerStage)))
    }
  }

  private def listDirectoryAndAppendToFooter(dir: File, customPatterns: Set[String]): Unit = {
    logger.info("Составление списка файлов для Footer из: {}", dir.getAbsolutePath)
    // Вызываем buildDirectoryTextListing, который теперь возвращает String, а не Try[String]
    // и обрабатывает ошибки внутри себя или пробрасывает их.
    // Обернем вызов в Try для безопасности на этом уровне.
    Try(buildDirectoryTextListing(dir, 0, customPatterns, dir)) match {
      case Success(fileListString) =>
        val textToInsert = formatDirectoryContentForInputFooter(dir.getName, fileListString)
        appendTextToFooter(textToInsert)
      case Failure(e) =>
        val errorMsg = s"Не удалось получить список файлов в ${dir.getName}: ${e.getMessage}"
        logger.error(s"Ошибка составления списка ${dir.getAbsolutePath}", e)
        Platform.runLater(DialogUtils.showError(errorMsg, Some(ownerStage)))
    }
  }

  /**
   * Рекурсивно строит текстовое представление структуры каталога.
   * Теперь возвращает String, а не Try[String]. Ошибки при рекурсивных вызовах
   * логируются, и проблемные ветки могут быть пропущены или заменены сообщением об ошибке.
   */
  private def buildDirectoryTextListing(
                                         currentDir: File,
                                         depth: Int,
                                         customPatterns: Set[String],
                                         scanRootDir: File
                                       ): String = { // Возвращаемый тип изменен на String
    if (depth > MAX_SCAN_DEPTH) {
      return "... (достигнута максимальная глубина отображения)\n" // Простой возврат строки
    }
    val indent = "  " * depth

    val filesAndDirsOrError: Try[List[File]] = Try { // Оборачиваем listFiles в Try
      Option(currentDir.listFiles())
        .map(_.toList).getOrElse(List.empty)
        .filterNot(f => isExcluded(f, customPatterns, scanRootDir))
        .sortBy(f => (!f.isDirectory, f.getName.toLowerCase()))
    }

    filesAndDirsOrError match {
      case Success(filesAndDirs) =>
        val contentBuilder = new StringBuilder
        if (depth == 0 && filesAndDirs.isEmpty && !isExcluded(currentDir, customPatterns, scanRootDir)) {
          contentBuilder.append(s"${indent}Папка ${currentDir.getName} не содержит видимых элементов или все исключены.\n")
        } else {
          filesAndDirs.foreach { file =>
            if (file.isFile) {
              contentBuilder.append(s"$indent- ${file.getName} (${formatFileSize(file.length())})\n")
            } else if (file.isDirectory) {
              contentBuilder.append(s"$indent+ ${file.getName}/\n")
              // Рекурсивный вызов теперь напрямую возвращает String
              // Если он выбросит исключение, оно не будет обработано здесь, если не обернуть в Try
              Try(buildDirectoryTextListing(file, depth + 1, customPatterns, scanRootDir)) match {
                case Success(subdirContent) => contentBuilder.append(subdirContent)
                case Failure(e) =>
                  logger.error(s"Ошибка при рекурсивном сканировании ${file.getAbsolutePath} для текстового списка: ${e.getMessage}", e)
                  contentBuilder.append(s"$indent  [ошибка сканирования каталога: ${file.getName}]\n")
              }
            }
          }
        }
        contentBuilder.toString()

      case Failure(e) =>
        logger.error(s"Не удалось получить список файлов для каталога ${currentDir.getAbsolutePath}: ${e.getMessage}", e)
        s"$indent[ошибка чтения каталога: ${currentDir.getName}]\n"
    }
  }

  private def formatFileSize(size: Long): String = {
    if (size < 1024) s"$size B"
    else if (size < 1024 * 1024) f"${size / 1024.0}%.1f KB"
    else f"${size / (1024.0 * 1024.0)}%.1f MB"
  }

  private def formatFileContentForInputFooter(fileName: String, content: String): String =
    s"\n\n--- Начало содержимого файла: $fileName ---\n```\n$content\n```\n--- Конец содержимого файла: $fileName ---\n"

  private def formatDirectoryContentForInputFooter(dirName: String, fileListString: String): String =
    s"\n\n--- Начало списка файлов из папки: $dirName ---\n$fileListString--- Конец списка файлов из папки: $dirName ---\n"

  private def appendTextToFooter(text: String): Unit = {
    footerRefOption.foreach { footer =>
      Platform.runLater(footer.appendText(text))
      logger.info("Текст добавлен в поле ввода Footer.")
    }
  }
}