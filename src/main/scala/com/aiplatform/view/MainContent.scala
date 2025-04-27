package com.aiplatform.view

import com.aiplatform.controller.MainController
import scalafx.scene.control.SplitPane

class MainContent(controller: MainController) {
  def create(): SplitPane = new SplitPane {
    style = "-fx-background: #1e1e1e;"
    items.addAll(
      RequestArea.create(controller),
      ResponseArea.create()
    )
    dividerPositions = 0.5
  }
}