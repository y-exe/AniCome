package net.yexe.anicome

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

@Composable private fun IllustrationViewport(height: Int, croppedPhone: Boolean = false, fillWidth: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = if (fillWidth) Alignment.TopCenter else Alignment.Center) {
            val width = if (!fillWidth && maxHeight.value.isFinite()) minOf(maxWidth, maxHeight * (360f / height)) else maxWidth
            val viewportHeight = if (maxHeight.value.isFinite()) minOf(maxHeight, width * (height / 360f)) else width * (height / 360f)
            val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = if (croppedPhone) 0.dp else 22.dp, bottomEnd = if (croppedPhone) 0.dp else 22.dp)
            val fade = if (croppedPhone) Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(0f to Color.Black, .84f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
            } else Modifier
            Surface(Modifier.width(width).height(viewportHeight).then(fade), shape = shape, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Layout(content = { Box(Modifier.clearAndSetSemantics {}, content = content) }) { children, constraints ->
                    val baseWidth = 360.dp.roundToPx()
                    val baseHeight = height.dp.roundToPx()
                    val scale = constraints.maxWidth.toFloat() / baseWidth
                    val childHeight = if (fillWidth) (constraints.maxHeight / scale).toInt() else baseHeight
                    val child = children.single().measure(Constraints.fixed(baseWidth, childHeight))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        child.placeWithLayer(0, 0) { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f) }
                    }
                }
            }
        }
    }
}

@Composable internal fun PermissionScreenIllustration(permission: Int) {
    val background = Color(0xFF1B1B1B)
    val card = Color(0xFF303030)
    val muted = Color(0xFFAAAAAA)
    val highlight = Color(0xFFA4E0CD)
    IllustrationViewport(if (permission == 1) 420 else 418, croppedPhone = true) {
        Box(Modifier.fillMaxSize().background(background)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.offset(18.dp, 16.dp).size(22.dp), tint = Color.White)
            SystemIllustrationText(if (permission == 1) "通知の許可、返信、管理" else "他のアプリの上に重ねて表示", 18, Modifier.offset(18.dp, 55.dp).width(324.dp), Color.White)
            Surface(Modifier.offset(158.dp, if (permission == 1) 86.dp else 106.dp).size(44.dp), shape = CircleShape, color = Color.White) { Box(contentAlignment = Alignment.Center) { BrandLogo(Modifier.size(32.dp), cropped = true) } }
            SystemIllustrationText("AniCome", 21, Modifier.offset(y = if (permission == 1) 142.dp else 162.dp).fillMaxWidth(), Color.White, centered = true)
            SystemIllustrationText(if (permission == 1) "AniCome" else BuildConfig.VERSION_NAME, 14, Modifier.offset(y = if (permission == 1) 172.dp else 192.dp).fillMaxWidth(), muted, centered = true)
            Surface(Modifier.offset(14.dp, if (permission == 1) 202.dp else 229.dp).width(332.dp).height(if (permission == 1) 70.dp else 90.dp), shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = if (permission == 1) 3.dp else 20.dp, bottomEnd = if (permission == 1) 3.dp else 20.dp), color = card) {
                Column(Modifier.padding(start = 18.dp, top = 14.dp, end = 76.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    SystemIllustrationText(if (permission == 1) "通知へのアクセスを許可" else "他のアプリの上に重ねて表示できるようにする", 16, color = Color(0xFFCCCCCC))
                    SystemIllustrationText(if (permission == 1) "制限付き設定によって管理されています" else "無効", 11, color = muted)
                }
            }
            Box(Modifier.offset(284.dp, if (permission == 1) 224.dp else 260.dp).size(48.dp, 29.dp).border(2.dp, Color(0xFF777777), CircleShape).padding(5.dp), contentAlignment = Alignment.CenterStart) { Box(Modifier.size(19.dp).background(Color(0xFFAAAAAA), CircleShape)) }
            Box(Modifier.offset(278.dp, if (permission == 1) 218.dp else 254.dp).size(60.dp, 41.dp).border(2.dp, highlight, RoundedCornerShape(24.dp)))
            if (permission == 1) {
                listOf("リアルタイム" to Icons.Filled.Sync, "会話" to Icons.Filled.Chat).forEachIndexed { index, (title, icon) ->
                    Surface(Modifier.offset(14.dp, (274 + index * 70).dp).width(332.dp).height(68.dp), shape = RoundedCornerShape(3.dp), color = card) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, null, Modifier.size(22.dp), tint = Color(0xFF858585)); Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                SystemIllustrationText(title, 17, color = Color(0xFF999999))
                                SystemIllustrationText(if (index == 0) "使用中のアプリ、ナビ、着信など" else "SMSやテキストメッセージなど", 11, color = Color(0xFF888888))
                            }
                            Icon(Icons.Filled.CheckBox, null, Modifier.size(20.dp), tint = Color(0xFF888888))
                        }
                    }
                }
            } else {
                Icon(Icons.Filled.Info, null, Modifier.offset(18.dp, 338.dp).size(20.dp), tint = Color(0xFFCCCCCC))
                SystemIllustrationText("使用中の他のアプリの上に、このアプリを重ねて表示できます。", 12, Modifier.offset(18.dp, 370.dp).width(320.dp), Color(0xFFCCCCCC))
            }
            Icon(Icons.Filled.TouchApp, null, Modifier.offset(300.dp, if (permission == 1) 246.dp else 282.dp).size(28.dp), tint = highlight)
        }
    }
}

@Composable private fun SystemIllustrationText(text: String, size: Int, modifier: Modifier = Modifier, color: Color = Color.White, centered: Boolean = false) {
    AppText(text, modifier, color, style = MaterialTheme.typography.bodyMedium.copy(fontSize = size.sp, lineHeight = (size * 1.35f).sp),
        textAlign = if (centered) androidx.compose.ui.text.style.TextAlign.Center else null)
}

@Composable internal fun TutorialScreenIllustration(step: Int, posterUrl: String? = null) {
    IllustrationViewport(if (step == 8) 480 else 620, croppedPhone = true, fillWidth = true) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (step == 4) SetupHomeIllustration() else Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(22.dp)); Spacer(Modifier.width(10.dp))
                    AppText("コメント保存", style = MaterialTheme.typography.titleLarge)
                }
                when (step) {
                    8 -> {
                        DemoCard(highlight = true) {
                            AppText("作品名", style = MaterialTheme.typography.labelMedium)
                            Row(verticalAlignment = Alignment.CenterVertically) { coil3.compose.AsyncImage(posterUrl, null, Modifier.size(36.dp, 48.dp).clip(RoundedCornerShape(5.dp)), contentScale = ContentScale.Crop); Spacer(Modifier.width(10.dp)); AppText("ご注文はうさぎですか", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, localize = false, maxLines = 1, overflow = TextOverflow.Ellipsis); AppIcon(Icons.Filled.CheckCircle, null, Modifier.size(20.dp)) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("1話", "まとめて").forEach { label -> Surface(shape = CircleShape, color = if (label == "まとめて") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.weight(1f)) { AppText(label, Modifier.padding(10.dp), color = if (label == "まとめて") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, textAlign = androidx.compose.ui.text.style.TextAlign.Center) } } }
                        DemoCard { AppText("保存する取得元", style = MaterialTheme.typography.labelMedium); Row(verticalAlignment = Alignment.CenterVertically) { AppText("ニコニコ公式・dアニメ", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); AppIcon(Icons.Filled.ExpandMore, null) } }
                        Spacer(Modifier.weight(1f))
                        DemoAction("保存内容を確認", Icons.Filled.Download, highlight = true)
                        Spacer(Modifier.height(64.dp))
                    }

                }
            }
        }
    }
}

@Composable private fun DemoCard(highlight: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (highlight) BorderStroke(2.dp, Color(0xFF7FBFAE)) else null) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
}
@Composable private fun DemoAction(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, highlight: Boolean = false) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, border = if (highlight) BorderStroke(3.dp, Color(0xFF7FBFAE)) else null) { Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { AppIcon(icon, null, tint = MaterialTheme.colorScheme.onPrimary); AppText(title, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge) } }
}

@Composable private fun SetupHomeIllustration() {
    CompositionLocalProvider(LocalReferenceBar provides null) {
        Box(Modifier.fillMaxSize().clipToBounds()) {
            Column(Modifier.fillMaxWidth()) {
                StudioHeader(title = { BrandWordmark(LocalHeaderCompact.current) }, actions = {
                    StudioIconButton({}) { AppIcon(Icons.Filled.LightMode, null) }
                    StudioIconButton({}) { AppIcon(Icons.Filled.Translate, null) }
                }, expandedHeight = 144.dp, titleBottomPadding = 12.dp)
                Column(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp)) {
                    Box(Modifier.border(2.dp, Color(0xFF7FBFAE), RoundedCornerShape(24.dp))) {
                        ReferenceGroup { HomeServiceRow(StreamingService.NETFLIX, {}, {}) }
                    }
                    Spacer(Modifier.height(16.dp))
                    StudioSearchField("", {}, Modifier.padding(bottom = 20.dp))
                    SettingsHomeList(true, 0, "", true, {}, {})
                }
            }
            ExtendedFloatingActionButton(onClick = {}, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 24.dp),
                icon = { AppIcon(Icons.Filled.Download, null) }, text = { AppText("事前ダウンロード") })
        }
    }
}
