package net.yexe.anicome

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class BandaiTitleAccessibilityService : AccessibilityService() {
    private val titles: BandaiTitleRepository by inject()
    private val prime: PrimePlaybackRepository by inject()
    private val danime: DanimePlaybackRepository by inject()
    private val hulu: HuluPlaybackRepository by inject()
    private val viewports: PlayerViewportRepository by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sampling: Job? = null
    private var huluSampling: Job? = null
    private var huluReadMs = 0L
    private var viewportSampling: Job? = null

    override fun dump(fd: java.io.FileDescriptor, writer: java.io.PrintWriter, args: Array<out String>?) {
        writer.println("huluSampling=${huluSampling?.isActive} stage=${HuluAccessibilityReader.stage} lastReadMs=$huluReadMs")
        writer.println("viewport=${viewports.state.value}")
    }

    override fun onServiceConnected() {
        titles.setEnabled(true)
        sampling?.cancel()
        sampling = scope.launch {
            while (isActive) {
                readPlayback()
                delay(1000)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() in StreamingService.accessibilityServices.map { it.packageName }) readPlayback()
    }

    private fun readPlayback() {
        if (viewportSampling?.isActive != true) viewportSampling = scope.launch { readViewport() }
        if (aniComeApp.activeService.value == StreamingService.HULU) {
            if (huluSampling?.isActive != true) huluSampling = scope.launch { readHulu() }
        } else { huluSampling?.cancel(); hulu.clear() }
        if (aniComeApp.activeService.value == StreamingService.D_ANIME) readDanime() else danime.clear()
        if (aniComeApp.activeService.value == StreamingService.PRIME_VIDEO) readPrime() else {
            prime.clear()
            readTitle()
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun readViewport() {
        val service = aniComeApp.activeService.value
        val manager = getSystemService(android.view.WindowManager::class.java)
        val size = if (android.os.Build.VERSION.SDK_INT >= 30) manager.maximumWindowMetrics.bounds.let { it.width() to it.height() }
            else android.util.DisplayMetrics().also { manager.defaultDisplay.getRealMetrics(it) }.let { it.widthPixels to it.heightPixels }
        if (size.first <= size.second) { viewports.clear(); return }
        try {
            val result = withContext(Dispatchers.Default) {
                val roots = windows.mapNotNull { it.root }.toMutableList()
                if (roots.isEmpty()) rootInActiveWindow?.let(roots::add)
                try { roots.filter { it.packageName?.toString() == service.packageName && it.isVisibleToUser }
                    .firstNotNullOfOrNull { PlayerViewportReader.read(it, size.first, size.second) } }
                finally { roots.forEach { it.recycle() } }
            }
            if (aniComeApp.activeService.value == service) {
                if (result != null) viewports.observe(service, size.first, size.second, result.first, result.second) else viewports.clear()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { }
    }

    @Suppress("DEPRECATION")
    private suspend fun readHulu() {
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            val result = withContext(Dispatchers.Default) {
                val roots = windows.mapNotNull { it.root }.toMutableList()
                if (roots.isEmpty()) rootInActiveWindow?.let(roots::add)
                try { roots.firstNotNullOfOrNull { HuluAccessibilityReader.read(it) } }
                finally { roots.forEach { it.recycle() } }
            }
            if (aniComeApp.activeService.value == StreamingService.HULU) result?.let(hulu::observe) ?: hulu.absent()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (aniComeApp.activeService.value == StreamingService.HULU) hulu.absent() }
        finally { huluReadMs = android.os.SystemClock.elapsedRealtime() - started }
    }

    @Suppress("DEPRECATION")
    private fun readDanime() {
        runCatching {
            val roots = windows.mapNotNull { it.root }.toMutableList()
            if (roots.isEmpty()) rootInActiveWindow?.let(roots::add)
            try {
                val root = roots.firstOrNull { it.isVisibleToUser && it.packageName?.toString() == StreamingService.D_ANIME.packageName }
                val nodes = mutableListOf<DanimeScreenNode>()
                var visited = 0
                fun read(node: AccessibilityNodeInfo) {
                    if (++visited > 400 || node.packageName?.toString() != StreamingService.D_ANIME.packageName) return
                    nodes += DanimeScreenNode(node.viewIdResourceName.orEmpty(), node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty(), node.isVisibleToUser)
                    for (index in 0 until node.childCount) node.getChild(index)?.let { child -> try { read(child) } finally { child.recycle() } }
                }
                root?.let(::read)
                DanimeScreenParser.parse(nodes)?.let(danime::observe)
            } finally { roots.forEach { it.recycle() } }
        }
    }

    @Suppress("DEPRECATION")
    private fun readPrime() {
        runCatching {
            val roots = windows.mapNotNull { it.root }.toMutableList()
            if (roots.isEmpty()) rootInActiveWindow?.let(roots::add)
            try {
                val root = roots.firstOrNull { it.isVisibleToUser && it.packageName?.toString() == StreamingService.PRIME_VIDEO.packageName }
                val nodes = mutableListOf<PrimeScreenNode>()
                fun read(node: AccessibilityNodeInfo) {
                    if (nodes.size >= 400 || node.packageName?.toString() != StreamingService.PRIME_VIDEO.packageName) return
                    nodes += PrimeScreenNode(node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty(), node.viewIdResourceName.orEmpty(), node.isVisibleToUser)
                    for (index in 0 until node.childCount) node.getChild(index)?.let { child -> try { read(child) } finally { child.recycle() } }
                }
                root?.let(::read)
                PrimeScreenParser.parse(nodes)?.let(prime::observe) ?: prime.absent()
            } finally { roots.forEach { it.recycle() } }
        }.onFailure { prime.absent() }
    }

    private fun readTitle() {
        if (aniComeApp.activeService.value != StreamingService.BANDAI_CHANNEL || titles.identity.value.isBlank()) return
        runCatching {
            val root = rootInActiveWindow ?: return
            if (root.packageName?.toString() != StreamingService.BANDAI_CHANNEL.packageName) return
            val series = root.textFor("videoInfoTitle") ?: return
            val episode = root.textFor("videoInfoStry") ?: return
            titles.observeScreen(series, episode)
        }
    }

    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.textFor(id: String): String? {
        val nodes = findAccessibilityNodeInfosByViewId("${StreamingService.BANDAI_CHANNEL.packageName}:id/$id")
        return try { nodes.firstNotNullOfOrNull { it.text?.toString()?.trim()?.takeIf(String::isNotBlank) } }
        finally { nodes.forEach { it.recycle() } }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        viewports.clear()
        titles.setEnabled(false)
        prime.clear()
        danime.clear()
        hulu.clear()
        scope.cancel()
        super.onDestroy()
    }
}
