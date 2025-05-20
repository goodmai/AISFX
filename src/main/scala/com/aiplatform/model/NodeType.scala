// src/main/scala/com/aiplatform/model/NodeType.scala
package com.aiplatform.model

/**
 * Представляет тип узла в файловой системе.
 * Этот ADT (Algebraic Data Type) используется для явного определения,
 * является ли узел файлом или каталогом, что повышает типобезопасность
 * и читаемость по сравнению с использованием простого Boolean.
 *
 * Соответствует AISFX принципу использования ADT.
 */
sealed trait NodeType

/**
 * Объект-компаньон для NodeType, может содержать вспомогательные методы,
 * если потребуется в будущем (например, для десериализации).
 */
object NodeType {
  /**
   * Указывает, что узел является файлом.
   */
  case object File extends NodeType

  /**
   * Указывает, что узел является каталогом.
   */
  case object Directory extends NodeType

  /**
   * Вспомогательный метод для получения NodeType из java.io.File.
   * @param file Файл java.io.File.
   * @return NodeType.File если это файл, NodeType.Directory если это каталог.
   */
  def fromJavaFile(file: java.io.File): NodeType = {
    if (file.isDirectory) Directory else File
  }
}