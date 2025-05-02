// src/main/scala/com/aiplatform/controller/manager/FileManager.scala
package com.aiplatform.controller.manager // Или com.aiplatform.controller

import com.aiplatform.view.Footer
import javafx.stage.{DirectoryChooser, FileChooser, Window}
import org.slf4j.LoggerFactory
import scalafx.application.Platform
import scalafx.stage.Stage

import java.io.File
import scala.io.Source
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try, Using}
import scala.util.control.NonFatal // Для NonFatal

/**
 * Управляет взаимодействием с файловой системой: выбор файлов и папок.
 *
 * @param ownerStage      Главное окно приложения (для диалогов).
 * @param footerRefOption Опциональная ссылка на Footer для добавления текста.
 */
class FileManager(ownerStage: Stage, footerRefOption: => Option[Footer]) { // Используем by-name параметр для footerRefOption
  private val logger = LoggerFactory.getLogger(getClass)
  private val MAX_FILE_SIZE_BYTES = 20000 // Увеличим лимит до 20KB
  private val ownerStageOpt: Option[Stage] = Option(ownerStage) // Сохраняем как Option

  /**
   * Открывает диалог выбора текстового файла и добавляет его содержимое в Footer.
   * Вызывается по клику на кнопку "Прикрепить файл".
   */
  def attachTextFile(): Unit = {
    logger.debug("Attach File action initiated (dialog).")
    val fileChooser = new FileChooser()
    fileChooser.setTitle("Выберите текстовый файл")
    fileChooser.getExtensionFilters.addAll(
      new FileChooser.ExtensionFilter("Text Files", "*.txt", "*.md", "*.scala", "*.java", "*.py", "*.js", "*.html", "*.css", "*.xml", "*.json", "*.log", "*.csv"),
      new FileChooser.ExtensionFilter("All Files", "*.*")
    )

    val selectedFileOpt = Option(fileChooser.showOpenDialog(ownerStage.delegate))

    selectedFileOpt.foreach(readFileAndAppend) // Используем новый общий метод
  }

  /**
   * Открывает диалог выбора папки и добавляет список файлов из нее в Footer.
   * Вызывается по клику на кнопку "Прикрепить папку".
   */
  def attachFolderContext(): Unit = {
    logger.debug("Attach Folder Context action initiated (dialog).")
    val directoryChooser = new DirectoryChooser()
    directoryChooser.setTitle("Выберите папку для добавления контекста")
    val selectedDirectoryOpt = Option(directoryChooser.showDialog(ownerStage.delegate))

    selectedDirectoryOpt.foreach(listDirectoryAndAppend) // Используем новый общий метод
  }

  /**
   * Читает содержимое указанного файла и добавляет его в Footer.
   * Вызывается как из диалога, так и при Drag & Drop файла.
   * @param file Файл для чтения.
   */
  def readFileAndAppend(file: File): Unit = {
    if (!file.exists() || !file.isFile) {
      val errorMsg = s"Выбранный элемент не является файлом или не существует: ${file.getAbsolutePath}"
      logger.error(errorMsg)
      showErrorDialog(errorMsg)
      return
    }
    logger.info(s"Reading file: ${file.getAbsolutePath}")
    readFileContent(file) match {
      case Success(content) =>
        val textToInsert = formatFileContentForInput(file.getName, content)
        appendTextToFooter(textToInsert)
      case Failure(e) =>
        val errorMsg = s"Не удалось прочитать файл ${file.getName}: ${e.getMessage}"
        logger.error(s"Failed to read file ${file.getAbsolutePath}", e)
        showErrorDialog(errorMsg) // Показываем ошибку пользователю
    }
  }

  /**
   * Получает список файлов в указанной папке и добавляет его в Footer.
   * Вызывается как из диалога, так и при Drag & Drop папки.
   * @param dir Папка для сканирования.
   */
  def listDirectoryAndAppend(dir: File): Unit = {
    if (!dir.exists() || !dir.isDirectory) {
      val errorMsg = s"Выбранный элемент не является папкой или не существует: ${dir.getAbsolutePath}"
      logger.error(errorMsg)
      showErrorDialog(errorMsg)
      return
    }
    logger.info(s"Listing directory: ${dir.getAbsolutePath}")
    listDirectoryContent(dir) match {
      case Success(fileListString) =>
        val textToInsert = formatDirectoryContentForInput(dir.getName, fileListString)
        appendTextToFooter(textToInsert)
      case Failure(e) =>
        val errorMsg = s"Не удалось получить список файлов в папке ${dir.getName}: ${e.getMessage}"
        logger.error(s"Failed to list directory ${dir.getAbsolutePath}", e)
        showErrorDialog(errorMsg) // Показываем ошибку
    }
  }


  /** Читает содержимое файла с ограничением по размеру. */
  private def readFileContent(file: File): Try[String] = Try {
    Using.resource(Source.fromFile(file)("UTF-8")) { source => // Явно указываем кодировку
      val content = source.take(MAX_FILE_SIZE_BYTES).mkString
      if (file.length() > MAX_FILE_SIZE_BYTES) {
        logger.warn(s"File content was larger than $MAX_FILE_SIZE_BYTES bytes, truncated.")
        content + "\n... (файл обрезан)"
      } else {
        content
      }
    }
  } // Добавляем import scala.util.Using в начало файла, если его нет

  /** Форматирует содержимое файла для вставки в поле ввода. */
  private def formatFileContentForInput(fileName: String, content: String): String = {
    s"\n\n--- Содержимое файла: $fileName ---\n```\n$content\n```\n--- Конец файла: $fileName ---\n"
  }

  /** Получает список видимых файлов в директории. */
  private def listDirectoryContent(dir: File): Try[String] = Try {
    val files = Option(dir.listFiles())
      .map(_.toList)
      .getOrElse(List.empty)
      .filter(f => f.isFile && !f.isHidden) // Только видимые файлы
      .sortBy(_.getName)

    if (files.isEmpty) "Папка не содержит видимых файлов."
    else files.map(f => s"- ${f.getName} (${f.length()} байт)").mkString("\n") // Добавим размер файла
  }

  /** Форматирует список файлов для вставки в поле ввода. */
  private def formatDirectoryContentForInput(dirName: String, fileListString: String): String = {
    s"\n\n--- Контекст папки: $dirName ---\n$fileListString\n--- Конец списка файлов ---\n"
  }

  /** Добавляет текст в Footer. */
  private def appendTextToFooter(text: String): Unit = {
    footerRefOption match {
      case Some(footer) =>
        Platform.runLater(footer.appendText(text)) // Выполняем в UI потоке
        logger.info("Text appended to footer input area.")
      case None =>
        logger.error("Cannot append text to footer: Footer reference is not available.")
    }
  }

  /** Показывает диалог ошибки (используя DialogUtils). */
  private def showErrorDialog(message: String): Unit = {
    com.aiplatform.view.DialogUtils.showError(message, ownerStageOpt)
  }

}

