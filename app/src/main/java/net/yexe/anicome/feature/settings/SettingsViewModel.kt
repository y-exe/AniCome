package net.yexe.anicome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class SettingsViewModel(val controller: AppCoordinator, private val profileRepository: NiconicoProfileRepository? = null, private val licenses: LicenseRepository? = null, initialPage: String = "") : ViewModel() {
    private val mutablePage = MutableStateFlow(initialPage)
    val page = mutablePage.asStateFlow()
    private var pageInitialized = false
    fun initializePage(value: String) { if (!pageInitialized) { mutablePage.value = value; pageInitialized = true } }
    private val history = mutableListOf<String>()
    private val mutableBackNavigation = MutableStateFlow(false)
    val backNavigation = mutableBackNavigation.asStateFlow()
    fun openPage(value: String) {
        pageInitialized = true
        mutableBackNavigation.value = false
        if (value != mutablePage.value) { history += mutablePage.value; mutablePage.value = value }
    }
    fun backPage() { mutableBackNavigation.value = true; mutablePage.value = if (history.isNotEmpty()) history.removeAt(history.lastIndex) else settingsParent(mutablePage.value) }
    private val mutableLicense = MutableStateFlow(LicenseState())
    val license = mutableLicense.asStateFlow()
    private var licenseJob: Job? = null
    fun readLicense(file: String, language: String) {
        licenseJob?.cancel(); mutableLicense.value = LicenseState(loading = true)
        licenseJob = viewModelScope.launch {
            try { mutableLicense.value = LicenseState(text = checkNotNull(licenses).read(file, language)) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableLicense.value = LicenseState(text = "ライセンスを読み込めませんでした", error = true) }
        }
    }
    val profile: StateFlow<NiconicoProfile?> = profileRepository?.state ?: MutableStateFlow(null)
    fun refreshProfile() = profileRepository?.refresh()
    fun setCommentSize(value: Float) = controller.setCommentSize(value)
    fun setOpacity(value: Float) = controller.setOpacity(value)
    fun setSpeedScale(value: Float) = controller.setSpeedScale(value)
    fun setDisplayDensity(value: Float) = controller.setDisplayDensity(value)
    fun setFps(value: Int) = controller.setFps(value)
    fun setColorStyle(value: String) = controller.setColorStyle(value)
    fun setFontStyle(value: String) = controller.setFontStyle(value)
    fun setNgWords(value: String) = controller.setNgWords(value)
    fun setCommentAmount(value: Int) = controller.setCommentAmount(value)
    fun setWifiOnly(value: Boolean) = controller.setWifiOnly(value)
    fun setWatchDownloads(value: Boolean) = controller.setWatchDownloads(value)
    fun setBatchLimit(value: Int) = controller.setBatchLimit(value)
    fun setLanguage(value: String) = controller.setLanguage(value)
    fun setThemeMode(value: String) = controller.setThemeMode(value)
    fun setStorageLimitMb(value: Long) = controller.setStorageLimitMb(value)
    fun unlinkNiconico() = controller.unlinkNiconico()
}
