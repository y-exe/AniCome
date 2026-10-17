package net.yexe.anicome

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.map

class ReservationVisibility(private val store: DataStore<Preferences>) {
    private val key = stringSetPreferencesKey("dismissed-download-reservations")
    val hidden = store.data.map { it[key].orEmpty() }
    suspend fun dismiss(ids: Set<String>) { store.edit { it[key] = it[key].orEmpty() + ids } }
}
