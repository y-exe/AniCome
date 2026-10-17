package net.yexe.anicome

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable internal fun AboutSettings(openLink: (String) -> Unit, openLicenses: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(Modifier.size(112.dp), shape = MaterialTheme.shapes.large, color = androidx.compose.ui.graphics.Color.White, shadowElevation = 2.dp) {
            Box(Modifier.padding(16.dp), contentAlignment = Alignment.Center) { BrandLogo(Modifier.fillMaxSize(), cropped = true) }
        }
        Spacer(Modifier.height(16.dp))
        AppText("AniCome", localize = false, style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        StudioBadge("バージョン ${BuildConfig.VERSION_NAME}")

    }
    ReferenceGroup {
        ReferenceCategory("GitHub", "ソースコード", Icons.Filled.Code, 0, 3) { openLink("https://github.com/y-exe/AniCome") }
        ReferenceCategory("不具合報告・要望", "GitHub Issues", Icons.Filled.BugReport, 1, 3) { openLink("https://github.com/y-exe/AniCome/issues") }
        ReferenceCategory("開発者", "github.com/y-exe", Icons.Filled.Language, 2, 3) { openLink("https://github.com/y-exe") }
    }
    Spacer(Modifier.height(8.dp))
    ReferenceGroup {
        ReferenceCategory("OSSライセンス", "使用しているオープンソース", Icons.Filled.Description, 0, 1, tone = 3, click = openLicenses)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AppText("© 2026 yexe", localize = false, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AppText("GNU GPL-3.0", localize = false, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
