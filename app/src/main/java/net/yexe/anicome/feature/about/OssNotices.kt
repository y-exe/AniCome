package net.yexe.anicome

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LicenseRepository(private val context: Context) {
    suspend fun read(file: String, language: String): String = withContext(Dispatchers.IO) {
        require(file == "Assets.txt" || OssNotices.any { it.file == file })
        val name = if (file == "Assets.txt" && language in setOf("en", "ko", "fr", "zh", "es", "pt")) "Assets-$language.txt" else file
        context.assets.open("licenses/$name").bufferedReader().use { it.readText() }
    }
}
data class LicenseState(val text: String = "", val loading: Boolean = false, val error: Boolean = false)

data class OssNotice(val name: String, val license: String, val file: String)
val OssNotices = listOf(
    OssNotice("bangumi-data", "CC BY 4.0 · bangumi-data contributors", "bangumi-data.txt"),
    OssNotice("Chiron GoRound TC", "SIL Open Font License 1.1 · Chiron GoRound TC Project Authors", "Chiron-GoRound.txt"),
    OssNotice("Jua", "SIL Open Font License 1.1 · Jua Project Authors", "Jua.txt"),
    OssNotice("Kosugi Maru", "Apache License 2.0 · The Kosugi Maru Project Authors", "Kosugi-Maru.txt"),
    OssNotice("Material Shapes", "Apache License 2.0 · The Android Open Source Project", "Material-Shapes.txt"),
    OssNotice("Google Sans Flex", "SIL Open Font License 1.1 · Google", "Google-Sans-Flex.txt"),
    OssNotice("AniCome", "GNU GPL-3.0 · © 2026 yexe", "AniCome.txt"),
    OssNotice("Twemoji", "CC-BY-4.0 · Twitter and contributors", "Twemoji.txt"),
    OssNotice("NCOverlay", "MIT · © 2023 Midra", "NCOverlay.txt"),
    OssNotice("nco-utils", "MIT · © 2025 Midra", "nco-utils.txt"),
    OssNotice("niconicomments", "MIT · xpadev-net", "niconicomments.txt"),
    OssNotice("Jetpack Compose / Navigation 3 / Room / DataStore / WorkManager / Biometric", "Apache License 2.0 · The Android Open Source Project", "Apache-2.0.txt"),
    OssNotice("Retrofit / OkHttp", "Apache License 2.0 · Square", "Apache-2.0.txt"),
    OssNotice("Koin", "Apache License 2.0 · Koin Contributors", "Apache-2.0.txt"),
    OssNotice("Lottie", "Apache License 2.0 · Airbnb", "Apache-2.0.txt"),
    OssNotice("Coil", "Apache License 2.0 · Coil Contributors", "Apache-2.0.txt"),
    OssNotice("Kotlin / kotlinx.coroutines", "Apache License 2.0 · JetBrains", "Apache-2.0.txt")
)
