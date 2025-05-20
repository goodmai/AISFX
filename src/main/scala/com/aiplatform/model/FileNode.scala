// src/main/scala/com/aiplatform/model/FileNode.scala
package com.aiplatform.model

import javafx.beans.property.{BooleanProperty, SimpleBooleanProperty}
import java.io.File // Стандартный java.io.File

/**
 * Представляет узел в файловом дереве.
 * Использует `NodeType` ADT для явного определения типа узла (Файл или Каталог).
 *
 * AISFX:
 * - Неизменяемость для основных атрибутов (`file`, `nodeType`, `name`, `path`).
 * - `children` также `val`, предполагая, что они либо передаются при создании, либо
 * управляются на уровне UI компонента (ленивая загрузка).
 * - `selectedProperty` - изменяемое JavaFX свойство для UI.
 * - Соответствует принципу использования ADT (`NodeType`).
 *
 * @param file Файл или каталог java.io.File, который представляет данный узел.
 * @param nodeType Тип узла (`NodeType.File` или `NodeType.Directory`).
 * @param children Список дочерних узлов (для каталогов).
 */
case class FileNode(
                     file: File,
                     nodeType: NodeType, // Замена для isDirectory: Boolean
                     children: List[FileNode] = List.empty
                   ) {
  /**
   * Имя файла или каталога.
   */
  val name: String = file.getName() // Вызов метода getName() из java.io.File

  /**
   * Абсолютный путь к файлу или каталогу.
   */
  val path: String = file.getAbsolutePath() // Вызов метода getAbsolutePath() из java.io.File

  /**
   * Свойство JavaFX, отслеживающее состояние выбора узла (например, в `CheckBoxTreeItem`).
   * Помечено `@transient` для исключения из возможной сериализации.
   */
  @transient val selectedProperty: BooleanProperty = new SimpleBooleanProperty(false)

  /**
   * Возвращает текущее состояние выбора узла.
   * @return `true`, если узел выбран, иначе `false`.
   */
  def isSelected: Boolean = selectedProperty.get()

  /**
   * Устанавливает состояние выбора узла.
   * @param value Новое состояние выбора.
   */
  def setSelected(value: Boolean): Unit = selectedProperty.set(value)

  /**
   * Удобный метод для проверки, является ли узел каталогом, на основе `nodeType`.
   * @return `true`, если узел является каталогом, иначе `false`.
   */
  def isDirectory: Boolean = nodeType == NodeType.Directory

  /**
   * Удобный метод для проверки, является ли узел файлом, на основе `nodeType`.
   * @return `true`, если узел является файлом, иначе `false`.
   */
  def isFile: Boolean = nodeType == NodeType.File


  /**
   * Строковое представление узла, используемое по умолчанию в `TreeView` для отображения.
   * @return Имя файла или каталога.
   */
  override def toString: String = name

  /**
   * Сравнивает этот `FileNode` с другим объектом.
   * Два `FileNode` считаются равными, если их абсолютные пути и типы узлов совпадают.
   */
  override def equals(obj: Any): Boolean = obj match {
    case that: FileNode => that.path == this.path && that.nodeType == this.nodeType
    case _              => false
  }

  /**
   * Возвращает хеш-код для этого `FileNode`.
   * Основан на хеш-коде абсолютного пути и типа узла.
   */
  override def hashCode(): Int = {
    val prime = 31
    var result = 1
    result = prime * result + path.hashCode
    result = prime * result + nodeType.hashCode()
    result
  }
}

/**
 * Объект-компаньон для `FileNode`.
 * Предоставляет вспомогательные конструкторы `apply`.
 */
object FileNode {
  /**
   * Вспомогательный конструктор для создания `FileNode` из `java.io.File`.
   * Автоматически определяет `nodeType` и инициализирует пустой список дочерних элементов.
   *
   * @param file Файл или каталог `java.io.File`.
   * @return Новый экземпляр `FileNode`.
   */
  def apply(file: File): FileNode = {
    new FileNode(file, NodeType.fromJavaFile(file))
  }

  /**
   * Вспомогательный конструктор для создания `FileNode` с заданными дочерними элементами.
   * Автоматически определяет `nodeType` из `java.io.File`.
   *
   * @param file Файл или каталог `java.io.File`.
   * @param children Список дочерних `FileNode`.
   * @return Новый экземпляр `FileNode`.
   */
  def apply(file: File, children: List[FileNode]): FileNode = {
    new FileNode(file, NodeType.fromJavaFile(file), children)
  }
}