package com.srctool.sinew.devtools.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.launch

/** One screen on the dashboard's back stack. */
internal class Screen(
    val title: String,
    val actions: @Composable RowScope.() -> Unit = {},
    val content: @Composable () -> Unit,
)

/** The dashboard's own back stack, never the app's navigation. */
internal class DevToolsNavigator(private val stack: SnapshotStateList<Screen>, private val close: () -> Unit) {
    val current: Screen? get() = stack.lastOrNull()

    fun push(screen: Screen) {
        stack += screen
    }

    /** Goes back one screen, or closes the dashboard from its first. */
    fun back() {
        if (stack.isEmpty()) close() else stack.removeAt(stack.lastIndex)
    }

    fun close() = close.invoke()
}

internal val LocalNavigator = staticCompositionLocalOf<DevToolsNavigator> { error("Outside the devtools dashboard") }

/** Where Copy goes. The platform clipboard by default; tests read it back. */
internal fun interface DevToolsClipboard {
    fun copy(text: String)
}

internal val LocalDevToolsClipboard = staticCompositionLocalOf<DevToolsClipboard?> { null }

@Suppress("DEPRECATION")                                            // LocalClipboard needs a platform ClipEntry per target
@Composable
internal fun rememberClipboard(): DevToolsClipboard {
    LocalDevToolsClipboard.current?.let { return it }
    val platform = LocalClipboardManager.current
    return remember(platform) { DevToolsClipboard { platform.setText(AnnotatedString(it)) } }
}

/** Forces light (`false`) or dark (`true`) instead of the system's setting: screenshots must not depend on the machine. */
internal val LocalDevToolsDark = staticCompositionLocalOf<Boolean?> { null }

/** The dashboard's own Material 3 theme, light or dark as the system is, so the app's theme never affects it. */
@Composable
internal fun DevToolsTheme(content: @Composable () -> Unit) {
    val dark = LocalDevToolsDark.current ?: isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme(), content = content)
}

/** The dashboard: the sections on its first screen, and the pages pushed on its own back stack. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Dashboard(sections: List<DevToolSection>, onClose: () -> Unit, initial: List<Screen> = emptyList()) {
    val stack = remember { mutableStateListOf<Screen>().apply { addAll(initial) } }
    val navigator = remember(stack) { DevToolsNavigator(stack, onClose) }
    // An app on an older androidx.activity provides no dispatcher: the top bar's Back still works there.
    if (LocalNavigationEventDispatcherOwner.current != null) {
        NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), onBackCompleted = navigator::back)
    }
    CompositionLocalProvider(LocalNavigator provides navigator) {
        DevToolsTheme {
            Surface(Modifier.fillMaxSize().testTag(DASHBOARD_TAG)) {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    val screen = navigator.current
                    TopAppBar(
                        title = { Text(screen?.title ?: "Developer tools", maxLines = 1) },
                        navigationIcon = {
                            TextButton(onClick = navigator::back, modifier = Modifier.testTag(BACK_TAG)) {
                                Text(if (screen == null) "Close" else "Back")
                            }
                        },
                        actions = { screen?.actions?.invoke(this) },
                    )
                    Box(Modifier.weight(1f)) {
                        if (screen == null) Home(sections) else screen.content()
                    }
                }
            }
        }
    }
}

@Composable
private fun Home(sections: List<DevToolSection>) {
    LazyColumn(Modifier.fillMaxSize().testTag(HOME_TAG)) {
        sections.forEach { section ->
            item(key = "section-${section.title}") {
                Text(
                    section.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp).testTag("section:${section.title}"),
                )
            }
            items(section.entries, key = { "${section.title}/${it.name}" }) { EntryRow(it) }
        }
    }
}

/** A list of entries, for a group. */
@Composable
internal fun EntryList(entries: List<DevToolEntry>) {
    LazyColumn(Modifier.fillMaxSize()) { items(entries, key = { it.name }) { EntryRow(it) } }
}

@Composable
internal fun EntryRow(entry: DevToolEntry) {
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }                // redraws a toggle after it changes
    val leading: @Composable () -> Unit = { EntryIcon(entry) }
    val supporting: (@Composable () -> Unit)? = entry.description?.let { { Text(it) } }
    when (entry) {
        is DevToolEntry.Page -> ListItem(
            headlineContent = { Text(entry.name) },
            supportingContent = supporting,
            leadingContent = leading,
            modifier = Modifier.clickable { navigator.push(Screen(entry.name, entry.appBarActions, entry.content)) }
                .testTag("entry:${entry.name}"),
        )
        is DevToolEntry.Group -> ListItem(
            headlineContent = { Text(entry.name) },
            supportingContent = supporting,
            leadingContent = leading,
            trailingContent = { Text("${entry.children.size}") },
            modifier = Modifier.clickable { navigator.push(Screen(entry.name) { EntryList(entry.children) }) }
                .testTag("entry:${entry.name}"),
        )
        is DevToolEntry.Toggle -> {
            @Suppress("UNUSED_EXPRESSION") version
            val on = entry.value()
            ListItem(
                headlineContent = { Text(entry.name) },
                supportingContent = supporting,
                leadingContent = leading,
                trailingContent = {
                    Switch(checked = on, onCheckedChange = { entry.onChanged(it); version++ }, modifier = Modifier.testTag("toggle:${entry.name}"))
                },
                modifier = Modifier.testTag("entry:${entry.name}"),
            )
        }
        is DevToolEntry.Action -> {
            ListItem(
                headlineContent = { Text(entry.name) },
                supportingContent = supporting,
                leadingContent = leading,
                modifier = Modifier.clickable { if (entry.confirm != null) asking = true else scope.launch { entry.run() } }
                    .testTag("entry:${entry.name}"),
            )
            if (asking) {
                ConfirmDialog(
                    title = entry.name,
                    text = entry.confirm.orEmpty(),
                    confirm = "Run",
                    onConfirm = { asking = false; scope.launch { entry.run() } },
                    onDismiss = { asking = false },
                )
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun EntryIcon(entry: DevToolEntry) {
    val icon: ImageVector? = entry.icon
    val color = (entry as? DevToolEntry.Page)?.color ?: (entry as? DevToolEntry.Group)?.color ?: MaterialTheme.colorScheme.secondaryContainer
    Box(Modifier.size(36.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        } else {
            Text(entry.name.take(1).uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

/** A confirmation dialog: nothing destructive happens without one. */
@Composable
internal fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag(CONFIRM_TAG)) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag(DISMISS_TAG)) { Text("Cancel") } },
    )
}

/** Monospaced text, for records and stack traces. */
@Composable
internal fun Mono(text: String, modifier: Modifier = Modifier) {
    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = modifier)
}

/** A one-line message for an empty list. */
@Composable
internal fun EmptyText(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A row of buttons under a record. */
@Composable
internal fun ButtonRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
internal fun Gap() = Spacer(Modifier.width(8.dp))

/** A small status chip, such as a replay marker. */
@Composable
internal fun Chip(text: String, color: Color = MaterialTheme.colorScheme.tertiaryContainer) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.background(color, CircleShape).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

internal const val DASHBOARD_TAG = "sinew-devtools-dashboard"
internal const val HOME_TAG = "sinew-devtools-home"
internal const val BACK_TAG = "sinew-devtools-back"
internal const val CONFIRM_TAG = "sinew-devtools-confirm"
internal const val DISMISS_TAG = "sinew-devtools-dismiss"
