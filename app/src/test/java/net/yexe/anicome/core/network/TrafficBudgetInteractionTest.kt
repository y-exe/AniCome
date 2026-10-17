package net.yexe.anicome

import androidx.compose.ui.semantics.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.*
import org.junit.Test

class TrafficBudgetInteractionTest : InteractionFixture() {
    @Test fun editorValidatesInputAndSavesTheChosenPeriod() {
        var saved: Pair<Long, String>? = null
        val view = fixture {
            var limited by remember { mutableStateOf(false) }; var limit by remember { mutableStateOf("") }; var period by remember { mutableStateOf("day") }
            Column {
                TrafficBudgetEditorContent(limited, limit, period, false, "", { limited = it }, { limit = it }, { period = it })
                TrafficBudgetSaveButton(limited, limit, period, false) { amount, periodValue -> saved = amount to periodValue }
            }
        }
        var finished = false
        fun saveEnabled() = nodes(view).first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "保存" } == true && it.config.getOrNull(SemanticsActions.OnClick) != null }.config.getOrNull(SemanticsProperties.Disabled) == null
        fun input(value: String) { nodes(view).first { it.config.getOrNull(SemanticsActions.SetText) != null }.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)) }
        view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(v: android.view.View) {}
            override fun onViewAttachedToWindow(v: android.view.View) {
                v.postDelayed({ click(view, "上限を設定") }, 200)
                v.postDelayed({ assertFalse(saveEnabled()); input("0") }, 500)
                v.postDelayed({ assertFalse(saveEnabled()); input("2048") }, 800)
                v.postDelayed({ assertTrue(saveEnabled()); click(view, "1か月ごと") }, 1100)
                v.postDelayed({ click(view, "保存"); assertEquals(2048L to "month", saved); finished = true }, 1400)
            }
        })
        try { paparazzi.gif(view, "budget_editor_validation", end = 1700, fps = 10); assertTrue(finished) }
        finally { view.disposeComposition() }
    }

    @Test fun blockedReservationOpensNetworkSettingsInsteadOfOfferingARetryLoop() {
        var page = ""
        val work = androidx.work.WorkInfo(java.util.UUID.randomUUID(), androidx.work.WorkInfo.State.ENQUEUED, setOf("series:作品", "episode:1"), progress = androidx.work.workDataOf("reservationTrafficBlocked" to true, "reservationManaged" to true, "reservationOrder" to 1))
        val view = fixture { ReservationRow(work, { fail("must wait for allowance") }, {}, settings = { page = it }, pause = {}) }
        try {
            paparazzi.snapshot(view, "budget_waiting_reservation")
            assertTrue(texts(view).contains("通信量の上限で待機中")); assertFalse(texts(view).contains("再試行"))
            click(view, "通信量の上限"); assertEquals("ネットワーク", page)
        } finally { view.disposeComposition() }
    }
}
