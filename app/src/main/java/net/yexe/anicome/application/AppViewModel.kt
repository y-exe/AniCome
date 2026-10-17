package net.yexe.anicome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*

data class RootUiState(val ready: Boolean = false, val theme: String = "dark", val language: String = "system", val linked: Boolean = false, val completed: Boolean = false, val error: String = "")
class AppViewModel(val controller: AppCoordinator) : ViewModel() {
    private val intro = MutableStateFlow(false)
    val introCompleted = intro.asStateFlow()
    fun finishIntro() { intro.value = true }
    val state = combine(controller.ready, controller.themeMode, controller.language, controller.niconicoLinked, controller.setupCompleted) { ready, theme, language, linked, completed -> RootUiState(ready, theme, language, linked, completed) }
        .combine(controller.startupError) { state, error -> state.copy(error = error) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RootUiState())
}
