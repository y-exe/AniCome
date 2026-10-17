package net.yexe.anicome

class MissingSourceBackoff(private val clock: () -> Long = { System.nanoTime() / 1_000_000L }, private val intervalMs: Long = 15 * 60_000L) {
    private val missing = linkedMapOf<Pair<String, PlaybackSource>, Long>()
    @Synchronized fun blocked(key: String, source: PlaybackSource): Boolean {
        val at = missing[key to source] ?: return false
        if (clock() - at in 0 until intervalMs) return true
        missing.remove(key to source)
        return false
    }
    @Synchronized fun record(key: String, source: PlaybackSource) {
        missing[key to source] = clock()
        while (missing.size > 256) missing.remove(missing.keys.first())
    }
    @Synchronized fun clear() { missing.clear() }
}
