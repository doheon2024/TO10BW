package com.doheon.kostolany.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.doheon.kostolany.data.SnapshotRepository
import com.doheon.kostolany.data.UpdateResult
import com.doheon.kostolany.model.Risk
import com.doheon.kostolany.model.Snapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data class Home(val snapshot: Snapshot) : Screen
    data class Updating(val progress: List<String>) : Screen
    data class Result(val snapshot: Snapshot, val update: UpdateResult?) : Screen
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SnapshotRepository(app)

    var risk by mutableStateOf(Risk.BALANCED)
    var screen by mutableStateOf<Screen>(Screen.Home(repo.load()))
        private set

    /** 저장된 정보로 바로 보기 */
    fun showStored() {
        screen = Screen.Result(repo.load(), null)
    }

    /** 최신 정보로 업데이트한 뒤 보기 (사용자가 버튼을 누를 때만) */
    fun updateAndShow() {
        screen = Screen.Updating(emptyList())
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                repo.update(risk) { msg ->
                    viewModelScope.launch(Dispatchers.Main) {
                        (screen as? Screen.Updating)?.let { screen = Screen.Updating(it.progress + msg) }
                    }
                }
            }
            screen = Screen.Result(result.new, result)
        }
    }

    fun goHome() {
        screen = Screen.Home(repo.load())
    }
}
