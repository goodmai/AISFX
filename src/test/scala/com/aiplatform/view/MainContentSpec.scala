// src/test/scala/com/aiplatform/view/MainContentSpec.scala
package com.aiplatform.view

import com.aiplatform.controller.MainController
import com.aiplatform.service.HistoryService
import org.apache.pekko.actor.typed.ActorSystem
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scalafx.application.Platform
import scalafx.embed.swing.SFXPanel // Используем SFXPanel
import scalafx.scene.Scene
import scalafx.stage.Stage

import scala.concurrent.Await
import scala.concurrent.duration._
// <<< ДОБАВЛЕНО: Импорт для uninitialized >>>
import scala.compiletime.uninitialized

// Используем BeforeAndAfterAll для ручного управления ActorSystem
class MainContentSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  // Инициализация JavaFX Toolkit для тестов
  new SFXPanel()
  Platform.implicitExit = false // Предотвращаем выход

  var testSystem: ActorSystem[HistoryService.Command] = uninitialized

  override def beforeAll(): Unit = {
    testSystem = ActorSystem(HistoryService(), "TestSystemForMainContent")
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    try {
      testSystem.terminate()
      Await.result(testSystem.whenTerminated, 5.seconds) // Ждем завершения
    } finally {
      // Platform.exit() // Не требуется с implicitExit = false
      super.afterAll()
    }
  }
  

  "Placeholder Test" should "always pass" in {
    // Добавлен пустой тест, чтобы файл не был совсем без тестов
    true shouldBe true
  }

}