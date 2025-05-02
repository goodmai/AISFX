// src/main/scala/com/aiplatform/controller/manager/PresetManager.scala
package com.aiplatform.controller.manager

import com.aiplatform.model.{AppState, PromptPreset}
import com.aiplatform.view.Header // Для доступа к списку категорий
import org.slf4j.LoggerFactory
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

/**
 * Управляет логикой пресетов (стандартных и пользовательских) и их назначений кнопкам категорий.
 * Использует StateManager для чтения и обновления состояния AppState.
 *
 * @param stateManager Менеджер состояния приложения.
 */
class PresetManager(stateManager: StateManager) {
  private val logger = LoggerFactory.getLogger(getClass)

  // --- Методы чтения состояния (не изменяют его) ---

  /**
   * Находит пресет (сначала среди пользовательских, потом стандартных) по имени.
   * Сравнение имен регистронезависимое.
   *
   * @param name Имя пресета для поиска.
   * @return Option[PromptPreset] с найденным пресетом или None.
   */
  def findPresetByName(name: String): Option[PromptPreset] = {
    val state = stateManager.getState
    // Ищем сначала среди пользовательских, потом стандартных
    state.customPresets.find(_.name.equalsIgnoreCase(name))
      .orElse(state.defaultPresets.find(_.name.equalsIgnoreCase(name)))
  }

  /**
   * Находит активный пресет для указанной кнопки (категории).
   * Использует маппинг кнопок, если он есть и пресет существует.
   * Иначе возвращает первый стандартный или первый пользовательский пресет.
   * Если пресетов нет вообще, возвращает жестко закодированный fallback.
   *
   * @param buttonName Имя кнопки категории (например, "Research", "Code").
   * @return Активный PromptPreset для этой кнопки.
   */
  def findActivePresetForButton(buttonName: String): PromptPreset = {
    val state = stateManager.getState
    val fallbackPreset: PromptPreset = state.defaultPresets.headOption // Сначала ищем первый стандартный
      .orElse(state.customPresets.headOption) // Потом первый пользовательский
      .getOrElse {
        // Критическая ситуация - нет вообще никаких пресетов
        logger.error("CRITICAL: No presets (default or custom) defined. Using hardcoded fallback.")
        PromptPreset("Fallback", "{{INPUT}}", isDefault = true) // Возвращаем аварийный пресет
      }

    // Ищем назначенный пресет
    state.buttonMappings.get(buttonName)
      .flatMap(findPresetByName) // Пытаемся найти пресет по имени из маппинга
      .getOrElse {
        // Если маппинга нет или пресет не найден, используем fallback
        if (state.buttonMappings.contains(buttonName)) {
          // Маппинг был, но пресет не найден (ошибка конфигурации?)
          logger.warn(s"Preset '${state.buttonMappings(buttonName)}' mapped to button '$buttonName' not found. Falling back to default preset logic.")
        } else {
          // Маппинга для кнопки нет
          logger.trace(s"No preset explicitly mapped for category button '$buttonName'. Falling back to default preset logic.")
        }
        fallbackPreset // Возвращаем fallback
      }
  }

  def getDefaultPresets: List[PromptPreset] = stateManager.getState.defaultPresets
  def getCustomPresets: List[PromptPreset] = stateManager.getState.customPresets
  def getButtonMappings: Map[String, String] = stateManager.getState.buttonMappings

  // --- Методы изменения состояния ---

  /**
   * Сохраняет (добавляет или обновляет) пользовательский пресет.
   * Проверяет уникальность имени среди ВСЕХ пресетов (кроме самого себя при обновлении).
   * Гарантирует, что у пользовательского пресета isDefault = false.
   *
   * @param preset Пользовательский пресет для сохранения.
   * @return Success(()) если сохранение успешно, иначе Failure(exception).
   */
  def saveCustomPreset(preset: PromptPreset): Try[Unit] = {
    stateManager.updateState { currentState =>
      // Проверяем, занято ли имя другим пресетом (стандартным или *другим* пользовательским)
      val isNameTaken = (currentState.defaultPresets ++ currentState.customPresets.filterNot(_.name.equalsIgnoreCase(preset.name)))
        .exists(_.name.equalsIgnoreCase(preset.name))

      if (isNameTaken) {
        // Имя уже используется другим пресетом
        throw new IllegalArgumentException(s"Имя пресета '${preset.name}' уже используется другим пресетом.")
      }

      // Ищем существующий пользовательский пресет для обновления
      val index = currentState.customPresets.indexWhere(_.name.equalsIgnoreCase(preset.name))
      val updatedList = if (index >= 0) {
        // Обновляем существующий
        logger.info("Updating existing custom preset '{}'", preset.name)
        // Обновляем, явно устанавливая isDefault = false
        currentState.customPresets.updated(index, preset.copy(isDefault = false))
      } else {
        // Добавляем новый
        logger.info("Adding new custom preset '{}'", preset.name)
        // Добавляем, явно устанавливая isDefault = false
        currentState.customPresets :+ preset.copy(isDefault = false)
      }
      // Обновляем список и сортируем по имени (регистронезависимо)
      currentState.copy(customPresets = updatedList.sortBy(_.name.toLowerCase))
    }.recoverWith { case NonFatal(e) =>
      logger.error(s"Failed to save custom preset '${preset.name}'.", e)
      Failure(e) // Пробрасываем ошибку
    }
  }

  /**
   * Обновляет существующий стандартный пресет.
   * Проверяет, что имя не занято пользовательским пресетом.
   * Не позволяет добавлять новые стандартные пресеты.
   * Гарантирует, что у стандартного пресета isDefault = true.
   *
   * @param preset Стандартный пресет для обновления. Должен существовать.
   * @return Success(()) если обновление успешно, иначе Failure(exception).
   */
  def saveDefaultPreset(preset: PromptPreset): Try[Unit] = {
    stateManager.updateState { currentState =>
      // Проверка, что имя не занято пользовательским пресетом
      val isNameTakenByCustom = currentState.customPresets.exists(_.name.equalsIgnoreCase(preset.name))
      if (isNameTakenByCustom) {
        throw new IllegalArgumentException(s"Имя '${preset.name}' уже используется пользовательским пресетом.")
      }

      // Ищем существующий стандартный пресет для обновления
      val index = currentState.defaultPresets.indexWhere(_.name.equalsIgnoreCase(preset.name))
      if (index < 0) {
        // Не позволяем создавать новые стандартные пресеты этим методом
        throw new NoSuchElementException(s"Стандартный пресет '${preset.name}' не найден для обновления.")
      }

      logger.info("Updating existing default preset '{}'", preset.name)
      // Обновляем существующий пресет, гарантируя isDefault = true
      val updatedList = currentState.defaultPresets.updated(index, preset.copy(isDefault = true))
      // Обновляем список и сортируем по имени (регистронезависимо)
      currentState.copy(defaultPresets = updatedList.sortBy(_.name.toLowerCase))
    }.recoverWith { case NonFatal(e) =>
      logger.error(s"Failed to save default preset '${preset.name}'.", e)
      Failure(e) // Пробрасываем ошибку
    }
  }

  /**
   * Удаляет пользовательский пресет по имени.
   * Также удаляет все назначения этого пресета кнопкам категорий.
   *
   * @param presetName Имя пользовательского пресета для удаления.
   * @return Success(()) если удаление успешно, иначе Failure(exception).
   */
  def deleteCustomPreset(presetName: String): Try[Unit] = {
    stateManager.updateState { currentState =>
      // Проверяем, существует ли такой пользовательский пресет
      currentState.customPresets.find(_.name.equalsIgnoreCase(presetName)) match {
        case Some(_) =>
          logger.info("Deleting custom preset '{}' and its mappings.", presetName)
          // Фильтруем список пользовательских пресетов
          val updatedList = currentState.customPresets.filterNot(_.name.equalsIgnoreCase(presetName))
          // Удаляем назначения, связанные с этим пресетом
          val updatedMappings = currentState.buttonMappings.filterNot { case (_, mappedPresetName) =>
            mappedPresetName.equalsIgnoreCase(presetName)
          }
          currentState.copy(customPresets = updatedList, buttonMappings = updatedMappings)
        case None =>
          // Пресет не найден, выбрасываем исключение
          val errorMsg = s"Пользовательский пресет '$presetName' не найден для удаления."
          logger.warn(s"Delete custom preset failed: $errorMsg")
          throw new NoSuchElementException(errorMsg)
      }
    }.recoverWith { case NonFatal(e) =>
      logger.error(s"Failed to delete custom preset '$presetName'.", e)
      Failure(e) // Пробрасываем ошибку
    }
  }

  /**
   * Обновляет карту назначений пресетов кнопкам категорий.
   * Проверяет, что все назначенные пресеты существуют (среди стандартных или пользовательских).
   *
   * @param newMappings Новая карта назначений [Имя кнопки -> Имя пресета].
   * @return Success(()) если обновление успешно, иначе Failure(exception).
   */
  def updateButtonMappings(newMappings: Map[String, String]): Try[Unit] = {
    stateManager.updateState { currentState =>
      // Собираем все доступные имена пресетов (стандартные + пользовательские) в нижнем регистре
      val allPresetNamesLower = (currentState.defaultPresets.map(_.name) ++ currentState.customPresets.map(_.name))
        .map(_.toLowerCase).toSet

      // Проверяем, все ли пресеты из newMappings существуют
      val invalidMappings = newMappings.filterNot { case (buttonName, presetName) =>
        // Проверяем, что имя кнопки валидно (известная категория) и пресет существует
        Header.categoryButtonNames.contains(buttonName) && allPresetNamesLower.contains(presetName.toLowerCase)
      }

      if (invalidMappings.isEmpty) {
        // Если все пресеты и кнопки валидны, обновляем карту
        logger.info("Updating button-preset mappings.")
        currentState.copy(buttonMappings = newMappings)
      } else {
        // Если есть невалидные пресеты или кнопки, формируем сообщение об ошибке и выбрасываем исключение
        val errors = invalidMappings.map {
          case (btn, preset) if !Header.categoryButtonNames.contains(btn) => s"Неизвестная кнопка '$btn'"
          case (btn, preset) => s"Пресет '$preset' (для кнопки '$btn') не найден"
        }.mkString("; ")
        logger.error(s"Update button mappings failed due to invalid entries: $errors")
        throw new IllegalArgumentException(s"Невозможно обновить назначения: $errors")
      }
    }.recoverWith { case NonFatal(e) =>
      logger.error("Failed to update button mappings.", e)
      Failure(e) // Пробрасываем ошибку
    }
  }
}
