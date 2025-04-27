package com.aiplatform.repository

import com.aiplatform.model.AppState
import com.aiplatform.util.JsonUtil
import java.nio.file.{Files, Paths}

object StateRepository {
  private val STATE_FILE = "app_state.json"

  def saveState(state: AppState): Unit = {
    val json = JsonUtil.serialize(state)
    Files.write(Paths.get(STATE_FILE), json.getBytes)
  }

  def loadState(): AppState = {
    if(Files.exists(Paths.get(STATE_FILE))) {
      JsonUtil.deserialize[AppState](Files.readString(Paths.get(STATE_FILE)))
    } else AppState.initialState
  }
}