package net.yexe.anicome

fun storageLimitBytes(megabytes: Long): Long = if (megabytes <= 0) Long.MAX_VALUE else megabytes.coerceAtMost(Long.MAX_VALUE / 1048576) * 1048576
fun initialSetupReady(overlay: Boolean, listener: Boolean, completed: Boolean, linked: Boolean): Boolean = overlay && listener && (completed || linked)
