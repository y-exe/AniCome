@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package net.yexe.anicome

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset

@Composable internal fun SetupScreen(listener: Boolean, overlay: Boolean, onListener: () -> Unit, onOverlay: () -> Unit, onHelp: () -> Unit,
    linked: Boolean = false, needsLogin: Boolean = true, onLogin: () -> Unit = {}, currentStep: Int = 0,
    notificationsAllowed: Boolean = true, onNotifications: () -> Unit = {}, onNext: () -> Unit = {}, onBack: () -> Unit = {}, helpStep: Int? = null, dismissHelp: () -> Unit = {}, onAppInfo: () -> Unit = {}, posters: List<String> = emptyList()) {
    val steps = listOf(0, 1, 2, 3, 4, 6)
    val scheme = MaterialTheme.motionScheme
    val reduced = LocalAdvancedSettings.current.reducedMotion
    val spatial = remember(scheme, reduced) { if (reduced) snap<IntOffset>() else scheme.defaultSpatialSpec<IntOffset>() }
    BackHandler(currentStep > 0, onBack)
    AnimatedContent(currentStep, Modifier.fillMaxSize(), transitionSpec = {
        val direction = if (steps.indexOf(targetState) >= steps.indexOf(initialState)) 1 else -1
        (slideInHorizontally(spatial) { direction * it / 4 } + fadeIn(tween(if (reduced) 0 else 200, delayMillis = if (reduced) 0 else 40))) togetherWith
            (slideOutHorizontally(spatial) { -direction * it / 4 } + fadeOut(tween(if (reduced) 0 else 140))) using SizeTransform(clip = false)
    }, label = "setupPage") { step ->
        SetupPage(listener, overlay, onListener, onOverlay, onHelp, linked, needsLogin, onLogin, step,
            notificationsAllowed, onNotifications, onNext, onBack, posters = posters)
    }
    if (helpStep != null) SetupPermissionHelp(helpStep, dismissHelp, onAppInfo)
}

@Composable private fun SetupPage(listener: Boolean, overlay: Boolean, onListener: () -> Unit, onOverlay: () -> Unit, onHelp: () -> Unit,
    linked: Boolean = false, needsLogin: Boolean = true, onLogin: () -> Unit = {}, currentStep: Int = 0,
    notificationsAllowed: Boolean = true, onNotifications: () -> Unit = {}, onNext: () -> Unit = {}, onBack: () -> Unit = {}, helpStep: Int? = null, dismissHelp: () -> Unit = {}, onAppInfo: () -> Unit = {}, posters: List<String> = emptyList()) {
    val steps = listOf(0, 1, 2, 3, 4, 6)
    var notificationOptIn by rememberSaveable { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (currentStep > 0) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { AppIcon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") }
            Row(Modifier.weight(1f).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(5) { index -> Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(if (index < steps.indexOf(currentStep)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)) }
            }
            AppText("${steps.indexOf(currentStep)} / 5", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium, localize = false)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val step = currentStep
            if (step == 0) SetupWelcome(posters) else Column(Modifier.fillMaxSize().pointerInput(step) {
                if (step in listOf(4, 6)) {
                    var distance = 0f
                    detectHorizontalDragGestures(onDragStart = { distance = 0f }, onDragCancel = { distance = 0f }, onDragEnd = {
                        if (distance < -64.dp.toPx() && step != 6) onNext()
                        else if (distance > 64.dp.toPx()) onBack()
                    }) { change, amount -> change.consume(); distance += amount }
                }
            }.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val title = when (step) {
                    1 -> "再生情報へのアクセス"; 2 -> "他のアプリの上に表示"; 3 -> "ニコニコ連携"
                    4 -> "視聴アプリを設定"; else -> "事前ダウンロード"
                }
                val description = when (step) {
                    1 -> "作品名・話数・再生位置の取得"
                    2 -> "動画アプリにコメントを重ねて表示"
                    else -> "ニコニコ公式ページでログイン"
                }
                AppText(title, style = MaterialTheme.typography.headlineMedium)
                if (step in 1..3) AppText(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.weight(1f).fillMaxWidth().then(if (step in listOf(4, 6)) Modifier.padding(top = 8.dp) else Modifier), contentAlignment = Alignment.Center) {
                    when (step) {
                        1, 2 -> PermissionScreenIllustration(step)
                        4 -> TutorialScreenIllustration(4)
                        6 -> TutorialScreenIllustration(8, posters.firstOrNull())
                        else -> NiconicoLoginWebContent(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)))
                    }
                }
                if (step in listOf(4, 6)) {
                    val instructions = if (step == 4) listOf(
                        "視聴アプリを選択",
                        "コメント表示をオン。タップで表示位置・文字サイズを調整",
                        "右側の開くマークで動画アプリを起動"
                    ) else listOf(
                        "ホーム右下 → 事前ダウンロード → ＋",
                        "作品名を検索 → 候補から選択",
                        "話数・取得元を選択 → 確認して保存"
                    )
                    instructions.forEachIndexed { index, text -> SetupInstruction(index + 1, text) }
                }
                when (step) {
                    1, 2 -> {
                        SetupInstruction(1, if (step == 1) "通知の管理 → AniCome" else "重ねて表示 → AniCome")
                        SetupInstruction(2, if (step == 1) "通知へのアクセスを許可" else "表示を許可")
                        if (if (step == 1) listener else overlay) AssistChip(onClick = {}, label = { AppText("設定済み") }, leadingIcon = { AppIcon(Icons.Filled.CheckCircle, null) })

                    }
                    3 -> {
                        if (linked) AssistChip({}, label = { AppText("連携済み") }, leadingIcon = { AppIcon(Icons.Filled.CheckCircle, null) })

                    }
                }
            }
        }
        if (currentStep == 3) Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onLogin, Modifier.weight(1f).heightIn(min = 48.dp)) {
                AppIcon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                AppText("全画面で表示")
            }
            if (linked || !needsLogin) Button(onNext, Modifier.weight(1f).heightIn(min = 48.dp)) { AppText("ログイン完了") }
        } else Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val pendingPermission = (currentStep == 1 && !listener) || (currentStep == 2 && !overlay)
            val pendingLogin = currentStep == 3 && !linked && needsLogin
            if (currentStep == 6 && !notificationsAllowed) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AppText("進捗を通知で確認", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(notificationOptIn, { notificationOptIn = it })
            }
            Button(onClick = { if (currentStep == 6 && notificationOptIn && !notificationsAllowed) onNotifications(); if (pendingPermission) { if (currentStep == 1) onListener() else onOverlay() } else if (pendingLogin) onLogin() else onNext() }, Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                AppText(when { pendingPermission -> "Android設定を開く"; pendingLogin -> "ログイン"; currentStep == 3 -> "ログイン完了"; currentStep == 0 -> "はじめる"; currentStep == 6 -> "AniComeをはじめる"; else -> "次へ" })
            }
            if (currentStep in 1..2) TextButton(onHelp, Modifier.fillMaxWidth()) { AppIcon(Icons.AutoMirrored.Filled.Help, null); Spacer(Modifier.width(8.dp)); AppText("アクセスを許可できない場合") }
        }
    }
}

@Composable private fun SetupWelcome(posters: List<String>) {
    val background = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize()) {
        WelcomePosterWall(Modifier.fillMaxSize(), posters)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to background.copy(alpha = .78f), .18f to background.copy(alpha = .18f), .48f to background.copy(alpha = .12f), .78f to background.copy(alpha = .94f), 1f to background)))
        Box(Modifier.align(Alignment.TopCenter).padding(top = 24.dp)) { WelcomeWordmark() }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            AppText("いつものアニメに、\nみんなのコメントを。", style = MaterialTheme.typography.headlineLarge)
            AppText("動画はそのまま。コメントでもっと楽しく。", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun WelcomeWordmark() {
    val light = MaterialTheme.colorScheme.background.luminance() > .5f
    val haloColor = if (light) Color.White else Color.Black
    val wordColor = if (light) Color.Black else Color.White
    val outline = with(LocalDensity.current) { 5.dp.toPx() }
    val halo = with(LocalDensity.current) { 8.dp.toPx() }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(40.dp)) {
            Image(painterResource(R.drawable.app_logo_inline), null,
                Modifier.matchParentSize().graphicsLayer { scaleX = 1.32f; scaleY = 1.32f }.blur(5.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded), colorFilter = ColorFilter.tint(haloColor))
            BrandLogo(Modifier.matchParentSize(), cropped = true)
        }
        Box {
            AppText("AniCome", Modifier.matchParentSize().clearAndSetSemantics {}, color = haloColor,
                style = MaterialTheme.typography.displaySmall.copy(drawStyle = Stroke(width = outline, join = StrokeJoin.Round), shadow = Shadow(haloColor, blurRadius = halo)))
            AppText("AniCome", color = wordColor, style = MaterialTheme.typography.displaySmall)
        }
    }
}

@Composable internal fun WelcomePosterWall(modifier: Modifier = Modifier, covers: List<String> = emptyList()) {
    val transition = rememberInfiniteTransition(label = "welcomePosters")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(52000, easing = LinearEasing)), label = "posterDrift")
    val still = LocalInspectionMode.current || LocalAdvancedSettings.current.reducedMotion
    if (covers.isEmpty()) return
    Box(modifier.clipToBounds().clearAndSetSemantics {}) {
        Row(Modifier.fillMaxSize().graphicsLayer { rotationZ = -10f; scaleX = 1.28f; scaleY = 1.28f }, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(3) { column ->
                Column(Modifier.weight(1f).wrapContentHeight(Alignment.Top, unbounded = true).graphicsLayer {
                    val progress = if (still) .1f else if (column % 2 == 0) phase else 1f - phase
                    translationY = -progress * size.height / 2f
                }) {
                    repeat(8) { index ->
                        Box(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                            coil3.compose.AsyncImage(covers[(index + column) % covers.size], null, Modifier.fillMaxWidth().aspectRatio(.7f).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun SetupInstruction(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = ReferenceClover, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(36.dp)) { Box(contentAlignment = Alignment.Center) { AppText(number.toString(), localize = false, style = MaterialTheme.typography.labelLarge) } }
        AppText(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable internal fun SetupPermissionHelp(permission: Int, dismiss: () -> Unit, appInfo: () -> Unit) {
    StudioDialog(onDismissRequest = dismiss, title = { AppText("アクセスを許可できない場合") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AppText(if (permission == 1) "APKインストール時のアクセス制限" else "端末のアクセス制限を確認")
            SetupInstruction(1, "アプリ情報を開く")
            SetupInstruction(2, "右上メニュー → 制限付き設定を許可")
            SetupInstruction(3, "戻ってアクセスを許可")
            AppText("項目名・場所は端末により異なります", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(appInfo) { AppText("アプリ情報を開く") } }, dismissButton = { TextButton(dismiss) { AppText("閉じる") } })
}
