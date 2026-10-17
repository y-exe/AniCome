package net.yexe.anicome

import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
val LocalReferenceBar = staticCompositionLocalOf<TopAppBarScrollBehavior?> { null }

data class SettingsCategory(val title: String, val summary: String, val icon: ImageVector, val page: String)
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
@Composable fun referenceFontFamily(): FontFamily {
    return FontFamily(*listOf(400, 500, 600, 700).map { weight ->
    Font(R.font.google_sans_flex, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("ROND", 100f)))
}.toTypedArray())
}
@Composable fun ReferenceGroup(content: @Composable ColumnScope.() -> Unit) = CompositionLocalProvider(LocalGroupedRows provides true) { Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)), verticalArrangement = Arrangement.spacedBy(2.dp), content = content) }
@Composable fun ReferenceCategory(title: String, summary: String, icon: ImageVector, index: Int, count: Int, tone: Int = index, trailing: (@Composable () -> Unit)? = null, click: () -> Unit) {
    val shape = RoundedCornerShape(topStart = if (index == 0) 24.dp else 4.dp, topEnd = if (index == 0) 24.dp else 4.dp, bottomStart = if (index == count - 1) 24.dp else 4.dp, bottomEnd = if (index == count - 1) 24.dp else 4.dp)
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val backgrounds = if (dark) listOf(0xFF3E4C63, 0xFF7D5260, 0xFF004D61, 0xFF6E4E13, 0xFF2E4B5F, 0xFF3B4869, 0xFF7D5260) else listOf(0xFFD7E3FF, 0xFFFFD8E4, 0xFFACEFEE, 0xFFFFDEAC, 0xFFB8E6FF, 0xFFD9E2FF, 0xFFFFD8E4)
    val foregrounds = if (dark) listOf(0xFFD7E3FF, 0xFFFFD8E4, 0xFFACEFEE, 0xFFFFDEAC, 0xFFB8E6FF, 0xFFD9E2FF, 0xFFFFD8E4) else listOf(0xFF253347, 0xFF631835, 0xFF002022, 0xFF281900, 0xFF0E3346, 0xFF27304E, 0xFF631835)
    val tint = Color(foregrounds[tone % 7])
    val background = Color(backgrounds[tone % 7])
    val interactions = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Surface(onClick = click, interactionSource = interactions, shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp).pressMotion(interactions)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(56.dp).clip(ReferenceClover).background(background), contentAlignment = Alignment.Center) { AppIcon(icon, null, Modifier.size(24.dp), tint = tint) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AppText(title, style = MaterialTheme.typography.titleMedium)
                AppText(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (trailing != null) trailing() else AppIcon(androidx.compose.material.icons.Icons.Filled.ChevronRight, null)
        }
    }
}
