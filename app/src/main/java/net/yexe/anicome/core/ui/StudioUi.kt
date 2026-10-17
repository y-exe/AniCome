@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package net.yexe.anicome

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.*
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable fun StudioButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, quiet: Boolean = false, contentPadding: PaddingValues = ButtonDefaults.ContentPadding, shape: androidx.compose.ui.graphics.Shape = ButtonDefaults.shape, content: @Composable RowScope.() -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    if (quiet) FilledTonalButton(onClick, modifier.pressMotion(interactions), enabled, shape = shape, contentPadding = contentPadding, interactionSource = interactions, content = content)
    else Button(onClick, modifier.pressMotion(interactions), enabled, shape = shape, contentPadding = contentPadding, interactionSource = interactions, content = content)
}
val LocalDialogActions = staticCompositionLocalOf { false }
@Composable fun StudioTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding, content: @Composable RowScope.() -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    if (LocalDialogActions.current) TextButton(onClick, modifier.pressMotion(interactions), enabled, contentPadding = contentPadding, interactionSource = interactions, content = content)
    else OutlinedButton(onClick, modifier.heightIn(min = 48.dp).pressMotion(interactions), enabled, contentPadding = contentPadding, interactionSource = interactions, content = content)
}
@Composable fun StudioTonalButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(), content: @Composable RowScope.() -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    FilledTonalButton(onClick, modifier.pressMotion(interactions), enabled, colors = colors, interactionSource = interactions, content = content)
}
@Composable fun StudioIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    IconButton(onClick, modifier.pressMotion(interactions), enabled, interactionSource = interactions, content = content)
}
val LocalGroupedRows = staticCompositionLocalOf { false }
@Composable fun StudioRow(headlineContent: @Composable () -> Unit, modifier: Modifier = Modifier, supportingContent: (@Composable () -> Unit)? = null, leadingContent: (@Composable () -> Unit)? = null, trailingContent: (@Composable () -> Unit)? = null) {
    ListItem(headlineContent = headlineContent, modifier = modifier.fillMaxWidth().clip(if (LocalGroupedRows.current) androidx.compose.ui.graphics.RectangleShape else androidx.compose.foundation.shape.RoundedCornerShape(24.dp)), supportingContent = supportingContent?.let { body -> { ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)) { body() } } }, leadingContent = leadingContent, trailingContent = trailingContent, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer))
}
val LocalHeaderCompact = staticCompositionLocalOf { false }
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun StudioHeader(title: @Composable () -> Unit, navigationIcon: @Composable () -> Unit = {}, actions: @Composable RowScope.() -> Unit = {}, expandedHeight: Dp = 170.dp, titleBottomPadding: Dp = 24.dp) {
    val bar = LocalReferenceBar.current
    if (expandedHeight <= 64.dp) {
        SideEffect { bar?.state?.heightOffsetLimit = 0f; bar?.state?.heightOffset = 0f }
        TopAppBar(title = title, navigationIcon = navigationIcon, actions = actions, windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        return
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val extraHeight = (expandedHeight - 64.dp).coerceAtLeast(with(density) { MaterialTheme.typography.headlineLarge.lineHeight.toDp() } + titleBottomPadding)
    val extraPixels = with(density) { extraHeight.roundToPx() }
    SideEffect { bar?.state?.heightOffsetLimit = -extraPixels.toFloat() }
    val background = androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surfaceContainer, bar?.state?.collapsedFraction ?: 0f)
    Surface(color = background) {
        Column {
            TopAppBar(
                title = {
                    Box(Modifier.graphicsLayer { alpha = bar?.state?.collapsedFraction ?: 0f }.semantics { if ((bar?.state?.collapsedFraction ?: 0f) < .5f) hideFromAccessibility() }) {
                        CompositionLocalProvider(LocalTitleMaxLines provides 1, LocalHeaderCompact provides true) { title() }
                    }
                },
                navigationIcon = navigationIcon, actions = actions,
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            )
            Box(Modifier.fillMaxWidth().clipToBounds().layout { measurable, constraints ->
                val child = measurable.measure(constraints.copy(minHeight = extraPixels, maxHeight = extraPixels))
                val visibleHeight = (extraPixels + (bar?.state?.heightOffset ?: 0f)).roundToInt().coerceIn(0, extraPixels)
                layout(child.width, visibleHeight) { child.place(0, visibleHeight - child.height) }
            }.graphicsLayer { alpha = 1f - (bar?.state?.collapsedFraction ?: 0f) }.semantics { if ((bar?.state?.collapsedFraction ?: 0f) >= .5f) hideFromAccessibility() }, contentAlignment = androidx.compose.ui.Alignment.BottomStart) {
                CompositionLocalProvider(LocalTitleMaxLines provides 1) {
                    ProvideTextStyle(MaterialTheme.typography.headlineLarge) {
                        Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = titleBottomPadding)) { title() }
                    }
                }
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CollapsingPage(modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val bar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState(initialHeightOffsetLimit = 0f), canScroll = { enabled }, snapAnimationSpec = MaterialTheme.motionScheme.fastSpatialSpec())
    CompositionLocalProvider(LocalReferenceBar provides bar) {
        Column(modifier.then(if (enabled) Modifier.nestedScroll(bar.nestedScrollConnection) else Modifier), content = content)
    }
}
@Composable fun PreferenceSection(title: String) = AppText(title, Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
@Composable fun PreferenceDivider() = HorizontalDivider(Modifier.padding(vertical = 8.dp))
@Composable fun PreferenceAction(title: String, summary: String, icon: ImageVector, onClick: () -> Unit) = StudioRow(headlineContent = { AppText(title, style = MaterialTheme.typography.titleMedium) }, modifier = Modifier.motionClickable(onClick = onClick), supportingContent = { if (summary.isNotBlank()) AppText(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }, leadingContent = { AppIcon(icon, null) }, trailingContent = { AppIcon(Icons.Filled.ChevronRight, null) })
@Composable fun StudioSearchField(value: String, change: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "設定を検索", actions: @Composable () -> Unit = {}) = OutlinedTextField(value, change, modifier.fillMaxWidth(), placeholder = { AppText(placeholder, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }, singleLine = true, visualTransformation = rememberRoundedInput(), shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent), leadingIcon = { AppIcon(Icons.Filled.Search, null) }, trailingIcon = { Row { if (value.isNotEmpty()) StudioIconButton({ change("") }) { AppIcon(Icons.Filled.Close, "検索をクリア") }; actions() } })
@Composable fun StudioField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, label: (@Composable () -> Unit)? = null, supportingText: (@Composable () -> Unit)? = null, singleLine: Boolean = false, minLines: Int = 1, readOnly: Boolean = false, keyboardOptions: KeyboardOptions = KeyboardOptions.Default, shape: androidx.compose.ui.graphics.Shape = OutlinedTextFieldDefaults.shape, enabled: Boolean = true, isError: Boolean = false) = OutlinedTextField(value, onValueChange, modifier, visualTransformation = rememberRoundedInput(), enabled = enabled, isError = isError, readOnly = readOnly, label = label, supportingText = supportingText, singleLine = singleLine, minLines = minLines, keyboardOptions = keyboardOptions, shape = shape)
@Composable fun StudioSwitch(checked: Boolean, change: (Boolean) -> Unit, label: String) { val title = uiText(label); Switch(checked, change, Modifier.semantics { contentDescription = title }) }
@Composable fun StudioBadge(text: String) = Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) { AppText(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge) }
fun snapSliderValue(value: Float, range: ClosedFloatingPointRange<Float>, steps: Int): Float {
    val bounded = value.coerceIn(range)
    if (steps <= 0) return bounded
    val increment = (range.endInclusive - range.start) / (steps + 1)
    return (range.start + ((bounded - range.start) / increment).roundToInt() * increment).coerceIn(range)
}
@Composable fun StudioSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier, valueRange: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0, enabled: Boolean = true, animate: Boolean = true, onValueChangeFinished: (() -> Unit)? = null) {
    val interactions = remember { MutableInteractionSource() }
    val dragging by interactions.collectIsDraggedAsState()
    val position = if (animate) {
        val animated by animateFloatAsState(value.coerceIn(valueRange), animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(), label = "sliderPosition")
        animated
    } else value.coerceIn(valueRange)
    val change: (Float) -> Unit = { onValueChange(snapSliderValue(it, valueRange, steps)) }
    Slider(if (dragging || !animate) value.coerceIn(valueRange) else position, change, modifier.semantics {
        progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(valueRange), valueRange, steps)
        if (enabled) setProgress { change(it); onValueChangeFinished?.invoke(); true } else disabled()
    }, valueRange = valueRange, steps = 0, enabled = enabled, interactionSource = interactions, onValueChangeFinished = onValueChangeFinished)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun StudioSheet(onDismissRequest: () -> Unit, secure: Boolean = false, dismissEnabled: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val mayDismiss by rememberUpdatedState(dismissEnabled)
    ModalBottomSheet(onDismissRequest = { if (dismissEnabled) onDismissRequest() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { mayDismiss || it != SheetValue.Hidden }),
        properties = ModalBottomSheetProperties(securePolicy = if (secure) androidx.compose.ui.window.SecureFlagPolicy.SecureOn else androidx.compose.ui.window.SecureFlagPolicy.Inherit),
        dragHandle = {
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                BottomSheetDefaults.DragHandle(Modifier.align(androidx.compose.ui.Alignment.Center))
                IconButton(onDismissRequest, Modifier.align(androidx.compose.ui.Alignment.CenterEnd).padding(end = 12.dp), enabled = dismissEnabled) { AppIcon(Icons.Filled.Close, "閉じる") }
            }
        }, content = content)
}
@Composable fun StudioProgress(progress: () -> Float, modifier: Modifier = Modifier) = LinearProgressIndicator(progress, modifier.fillMaxWidth())
@Composable fun StudioProgress(modifier: Modifier = Modifier) = LinearProgressIndicator(modifier.fillMaxWidth())
@Composable fun StudioDialog(onDismissRequest: () -> Unit, title: @Composable () -> Unit, text: (@Composable () -> Unit)? = null, confirmButton: @Composable () -> Unit, dismissButton: (@Composable () -> Unit)? = null) = AlertDialog(onDismissRequest, { CompositionLocalProvider(LocalDialogActions provides true) { confirmButton() } }, modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(.94f), properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false), dismissButton = dismissButton?.let { action -> { CompositionLocalProvider(LocalDialogActions provides true) { action() } } }, title = title, text = text?.let { body -> { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { body() } } })

@Composable fun PreferenceHint(text: String) = AppText(text, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
@Composable fun StudioFormLayout(title: String, actions: @Composable ColumnScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val maximum = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp - 96.dp).coerceAtLeast(160.dp)
    Column(Modifier.fillMaxWidth().heightIn(max = maximum).imePadding()) {
        AppText(title, Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.headlineSmall)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}
@Composable fun PreferenceChoice(label: String, selected: Boolean, choose: () -> Unit, detail: String = "", enabled: Boolean = true) {
    Surface(shape = if (LocalGroupedRows.current) androidx.compose.ui.graphics.RectangleShape else androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
        ListItem(headlineContent = { AppText(label, style = MaterialTheme.typography.bodyLarge) }, supportingContent = detail.takeIf { it.isNotBlank() }?.let { { AppText(it, style = MaterialTheme.typography.bodyMedium) } },
            trailingContent = { RadioButton(selected, null, enabled = enabled) }, modifier = Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = choose),
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
    }
}

@Composable fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
@Composable fun MenuAction(title: String, icon: ImageVector, enabled: Boolean = true, destructive: Boolean = false, action: () -> Unit) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    StudioRow(headlineContent = { AppText(title, style = MaterialTheme.typography.titleMedium, color = color) },
        leadingContent = { AppIcon(icon, null, tint = color) },
        modifier = Modifier.heightIn(min = 56.dp).semantics { role = androidx.compose.ui.semantics.Role.Button }.motionClickable(enabled = enabled, onClick = action))
}
