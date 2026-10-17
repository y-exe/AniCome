package net.yexe.anicome

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class StorageState(val comments: Long = 0, val images: Long = 0, val free: Long = 0, val episodes: Int = 0, val busy: Boolean = false, val error: String = "")
class StorageRepository(private val context: Context, private val app: AppCoordinator) {
    suspend fun snapshot(): StorageState = withContext(Dispatchers.IO) {
        val items = app.library.value
        val cache = coil3.SingletonImageLoader.get(context).diskCache?.size ?: 0L
        StorageState(items.sumOf { it.bytes }, cache, context.filesDir.usableSpace, items.size)
    }
    suspend fun clearImages() = withContext(Dispatchers.IO) {
        val loader = coil3.SingletonImageLoader.get(context)
        loader.diskCache?.clear(); loader.memoryCache?.clear()
    }
}
class StorageViewModel(private val repository: StorageRepository) : ViewModel() {
    private val mutable = MutableStateFlow(StorageState())
    val state = mutable.asStateFlow()
    fun refresh() { viewModelScope.launch { mutable.value = repository.snapshot() } }
    fun clearImages() {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, error = "") }
        viewModelScope.launch {
            try { repository.clearImages(); mutable.value = repository.snapshot() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(error = "画像キャッシュを削除できませんでした") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
}
