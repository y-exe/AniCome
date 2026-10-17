package net.yexe.anicome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class LibraryViewModel(val controller: AppCoordinator, private val downloads: DownloadRepository) : ViewModel() {
    val library: StateFlow<List<SavedEpisode>> = controller.library.asStateFlow()
    val works = downloads.work
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun cancel(id: java.util.UUID) { controller.convenience?.let { it.defer("保存予約の取り消しを予約しました") { downloads.cancel(id) }; return }; viewModelScope.launch { try { downloads.cancel(id) } catch (e: CancellationException) { throw e } catch (_: Exception) { Session.message.value = "削除できませんでした" } } }
    fun cancelAll() { controller.convenience?.let { repo -> val ids = works.value.filter { it.state !in setOf(androidx.work.WorkInfo.State.SUCCEEDED, androidx.work.WorkInfo.State.CANCELLED) }.map { it.id }.toSet(); repo.defer("保存予約の取り消しを予約しました") { downloads.cancelMany(ids) }; return }; viewModelScope.launch { try { downloads.cancelAll() } catch (e: CancellationException) { throw e } catch (_: Exception) { Session.message.value = "削除できませんでした" } } }
    fun enqueue(episode: Episode) { downloads.enqueue(episode) }
    fun retry(work: androidx.work.WorkInfo) { downloads.retry(work) }
    private fun queueAction(action: suspend () -> Unit) { controller.scope.launch { try { action() } catch (e: CancellationException) { throw e } catch (_: Exception) { Session.message.value = "保存予約を更新できませんでした。再試行してください。" } } }
    fun pause(ids: Set<java.util.UUID>) = queueAction { downloads.pause(ids) }
    fun resume(ids: Set<java.util.UUID>) = queueAction { downloads.resume(ids) }
    fun reorder(id: java.util.UUID, direction: String) = queueAction { downloads.reorder(id, direction) }
}
