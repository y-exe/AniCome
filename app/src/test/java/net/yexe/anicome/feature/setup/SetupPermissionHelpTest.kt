package net.yexe.anicome

import androidx.compose.runtime.*
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SetupPermissionHelpTest : InteractionFixture() {
    @Test fun helpButtonOpensAndDismissesTheActualDialogForBothPermissions() {
        for (permission in listOf(1, 2)) {
            val help = mutableStateOf<Int?>(null)
            val view = fixture {
                SetupScreen(false, false, {}, {}, { help.value = permission }, currentStep = permission,
                    helpStep = help.value, dismissHelp = { help.value = null })
            }
            try {
                paparazzi.snapshot(view, "permission_before_help_$permission")
                click(view, "アクセスを許可できない場合")
                paparazzi.snapshot(view, "permission_help_manual_$permission")
                assertEquals(permission, help.value)
                help.value = null
                paparazzi.snapshot(view, "permission_help_dismissed_$permission")
                assertTrue(texts(view).contains("Android設定を開く"))
            } finally { view.disposeComposition() }
        }
    }

    @Test fun returningWithoutPermissionRendersTheActualHelpDialog() {
        for (permission in listOf(1, 2)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val model = SetupViewModel(SettingsRepository(MemoryPreferences(), scope), SavedStateHandle(mapOf("step" to permission)))
            val view = fixture {
                val state by model.state.collectAsState()
                SetupScreen(false, false, {}, {}, { model.help(permission) }, currentStep = state.step,
                    helpStep = state.help, dismissHelp = { model.help(null) })
            }
            try {
                paparazzi.snapshot(view, "permission_before_return_$permission")
                model.requested(permission)
                model.background()
                model.resumed(false, false)
                paparazzi.snapshot(view, "permission_help_return_$permission")
                assertEquals(permission, model.state.value.help)
                model.help(null)
                paparazzi.snapshot(view, "permission_return_dismissed_$permission")
                assertNull(model.state.value.help)
            } finally { view.disposeComposition(); model.viewModelScope.cancel(); scope.cancel() }
        }
    }
}
