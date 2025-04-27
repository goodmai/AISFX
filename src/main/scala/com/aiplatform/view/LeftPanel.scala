package com.aiplatform.view

import com.aiplatform.model.Section
import scalafx.scene.control.Button
import scalafx.scene.layout.VBox
import scala.compiletime.uninitialized // Добавляем импорт

class LeftPanel(
                 initialSection: Section,
                 onSectionChange: Section => Unit
               ) {
  private var currentSection: Section = initialSection
  private var buttons: List[Button] = uninitialized

  def create(): VBox = {
    buttons = Section.values.map { section =>
      createSectionButton(section)
    }.toList

    new VBox(10) {
      style = "-fx-background-color: #2b2b2b; -fx-padding: 15px;"
      children = buttons
    }
  }

  def refresh(newSection: Section): Unit = {
    currentSection = newSection
    buttons.foreach { btn =>
      btn.style = styleFor(Section.valueOf(btn.text.value))
    }
  }

  private def createSectionButton(section: Section): Button =
    new Button(section.toString) {
      style = styleFor(section)
      prefWidth = 120
      prefHeight = 40
      onAction = _ => {
        currentSection = section
        onSectionChange(section)
        refresh(section)
      }
    }

  private def styleFor(section: Section): String =
    s"-fx-base: ${if(section == currentSection) "#4b6eaf" else "#3c3f41"}"
}