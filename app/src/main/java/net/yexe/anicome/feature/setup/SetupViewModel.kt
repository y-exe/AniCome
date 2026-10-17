package net.yexe.anicome

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SetupState(val loaded: Boolean = false, val step: Int = 0, val finished: Boolean = false, val needsLogin: Boolean = true, val help: Int? = null, val posters: List<String> = emptyList())

class SetupViewModel(private val settings: SettingsRepository, private val saved: SavedStateHandle, private val artwork: SetupArtworkRepository = SetupArtworkRepository()) : ViewModel() {
    private val mutable = MutableStateFlow(SetupState(step = saved["step"] ?: 0, help = saved["help"]))
    val state = mutable.asStateFlow()
    init { viewModelScope.launch {
        val prefs = settings.load()
        val completed = prefs.getBoolean("onboardingCompleted", prefs.getBoolean("setupCompleted", false) && !prefs.getBoolean("setupFlowStarted", false))
        if (!completed) settings.putBoolean("setupFlowStarted", true)
        mutable.value = mutable.value.copy(loaded = true, finished = completed, needsLogin = !prefs.getBoolean("setupCompleted", false), posters = artwork.urls)
    } }
    private fun step(value: Int) { saved["step"] = value; mutable.value = mutable.value.copy(step = value) }
    fun next(listener: Boolean, overlay: Boolean, linked: Boolean) {
        val current = mutable.value
        when (current.step) {
            0 -> step(1)
            1 -> if (listener) step(2)
            2 -> if (overlay) step(if (current.needsLogin && !linked) 3 else 4)
            3 -> if (linked) step(4)
            4 -> step(6)
            6 -> if (listener && overlay && (!current.needsLogin || linked)) {
                settings.putBoolean("onboardingCompleted", true)
                mutable.value = current.copy(finished = true)
            }
        }
    }
    fun back() {
        when (mutable.value.step) {
            6 -> step(4)
            4 -> step(if (mutable.value.needsLogin) 3 else 2)
            else -> if (mutable.value.step > 0) step(mutable.value.step - 1)
        }
    }
    fun requested(permission: Int) { saved["pending"] = permission; saved["away"] = false }
    fun background() { if (saved.get<Int>("pending") != null) saved["away"] = true }
    fun resumed(listener: Boolean, overlay: Boolean) {
        if (mutable.value.finished || mutable.value.step >= 3) {
            if (!listener) step(1) else if (!overlay) step(2)
        }
        val pending = saved.get<Int>("pending") ?: return
        if (saved.get<Boolean>("away") != true) return
        saved.remove<Int>("pending"); saved["away"] = false
        if ((pending == 1 && !listener) || (pending == 2 && !overlay)) help(pending)
    }
    fun help(permission: Int?) { saved["help"] = permission; mutable.value = mutable.value.copy(help = permission) }
}
