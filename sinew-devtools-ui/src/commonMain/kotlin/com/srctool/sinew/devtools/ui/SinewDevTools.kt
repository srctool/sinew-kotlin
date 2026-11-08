package com.srctool.sinew.devtools.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.srctool.sinew.devtools.DevTools
import com.srctool.sinew.devtools.ExceptionRecord
import com.srctool.sinew.devtools.ExceptionSource
import com.srctool.sinew.devtools.Redaction
import com.srctool.sinew.storage.InspectableStore
import com.srctool.sinew.storage.KeyValueStore
import com.srctool.sinew.storage.SecureStore
import io.ktor.http.Url
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The developer tools around the app: wraps [content] with the launcher, the dashboard and the ribbon. Call it from
 * each platform entry point only; shared code takes plain hooks and callbacks, never a devtools type. Production builds
 * depend on `sinew-devtools-noop`, where this just calls [content].
 *
 * ```kotlin
 * setContent {
 *     SinewDevTools(sections = listOf(productSection), ribbon = BuildRibbon("1.4.0 (212) · staging")) {
 *         App(appClients(DevTools.httpHooks))
 *     }
 * }
 * ```
 *
 * @param sections the app's own sections, after Sinew's.
 * @param ribbon a label in a corner of every screen; the active host and "faults on" are added to it.
 * @param launcher how the dashboard opens: [FloatingLauncher] (the default), [MenuLauncher] or [CustomLauncher].
 * @param redaction what the recorders hide. Extend `Redaction.Default`; the defaults can't be removed.
 * @param stores the stores to inspect: each `KeyValueStore` and `SecureStore` gets its own page (secure values masked),
 *   and any other `InspectableStore` is listed, read-only, under Local DB.
 * @param changelog text for the Changelog page, or `null` for none.
 * @param hosts the API hosts a tester can switch to, as base URLs. Empty: no API Host page.
 * @param showUncaughtErrors whether an uncaught error pops up a card over the app. Uncaught errors are always recorded.
 * @param preferences devtools' own key-value store, such as `KeyValueStore.create(context, "sinew.devtools")`: the
 *   floating button's position and the chosen API host are remembered there. `null`: nothing is remembered.
 */
@Composable
public fun SinewDevTools(
    sections: List<DevToolSection> = emptyList(),
    ribbon: BuildRibbon? = null,
    launcher: DevToolsLauncher = FloatingLauncher(),
    redaction: Redaction = Redaction.Default,
    stores: List<InspectableStore> = emptyList(),
    changelog: String? = null,
    hosts: List<String> = emptyList(),
    showUncaughtErrors: Boolean = false,
    preferences: KeyValueStore? = null,
    content: @Composable () -> Unit,
) {
    LaunchedEffect(redaction) {
        DevTools.network.redaction = redaction
        DevTools.exceptions.redaction = redaction
    }
    LaunchedEffect(Unit) { DevTools.captureUncaughtErrors() }
    LaunchedEffect(hosts, preferences) { DevTools.hosts.configure(hosts, preferences) }
    val open by DevTools.isOpen.collectAsState()
    val allSections = remember(sections, stores, changelog, hosts) { listOf(sinewSection(stores, changelog, hosts.isNotEmpty())) + sections }

    Box(Modifier.fillMaxSize()) {
        content()
        Ribbon(ribbon)
        if (launcher is FloatingLauncher && !open) FloatingButton(launcher.initialPosition, preferences)
        if (showUncaughtErrors && !open) UncaughtErrorCard()
        if (open) Dashboard(allSections, onClose = DevTools::close)
    }
}

/**
 * A ready menu row that opens the dashboard, for [MenuLauncher]: place it in a settings screen or a drawer. In the
 * no-op artifact it draws nothing.
 */
@Composable
public fun DevToolsMenuItem(modifier: Modifier = Modifier) {
    ListItem(
        headlineContent = { Text("Developer tools") },
        supportingContent = { Text("Network, exceptions, stores, faults") },
        leadingContent = { Monogram("D") },
        modifier = modifier.clickable { DevTools.open() }.testTag(MENU_ITEM_TAG),
    )
}

/** Sinew's own section, always first. */
internal fun sinewSection(stores: List<InspectableStore>, changelog: String?, hasHosts: Boolean): DevToolSection {
    val keyValue = stores.filterIsInstance<KeyValueStore>()
    val secure = stores.filterIsInstance<SecureStore>()
    val other = stores.filterNot { it is KeyValueStore || it is SecureStore }
    return DevToolSection(
        "Sinew",
        buildList {
            add(DevToolEntry.Page("Network Monitor", "Requests, redacted when recorded", appBarActions = { NetworkMonitorActions() }) { NetworkMonitorPage() })
            add(DevToolEntry.Page("Exception Log", "Reported and uncaught errors", appBarActions = { ExceptionLogActions() }) { ExceptionLogPage() })
            keyValue.forEach { store -> add(DevToolEntry.Page("Key-Value Store", store.name) { KeyValueStorePage(store) }) }
            secure.forEach { store -> add(DevToolEntry.Page("Secure Store", store.name) { SecureStorePage(store) }) }
            if (other.isNotEmpty()) {
                add(DevToolEntry.Group("Local DB", "Read-only", children = other.map { store -> DevToolEntry.Page(store.name) { InspectableStorePage(store) } }))
            }
            add(
                DevToolEntry.Action(
                    "Expire Access Token",
                    "The next request sends an invalid token",
                    confirm = "The next request that carries a token sends an invalid one, so the real refresh runs.",
                ) { DevTools.expireAccessToken() },
            )
            add(DevToolEntry.Page("Fault Injection", "Force errors, delays and dropped connections") { FaultInjectionPage() })
            if (hasHosts) add(DevToolEntry.Page("API Host", "Send every client to another backend") { ApiHostPage() })
            if (changelog != null) add(DevToolEntry.Page("Changelog") { ChangelogPage(changelog) })
        },
    )
}

/** The build label, the active host and "faults on", in the top-end corner of every screen. */
@Composable
private fun Ribbon(ribbon: BuildRibbon?) {
    val host by DevTools.hosts.current.collectAsState()
    val faults by DevTools.faults.active.collectAsState()
    val parts = listOfNotNull(
        ribbon?.label,
        host?.let { runCatching { Url(it).host }.getOrDefault(it) },
        "faults on".takeIf { faults.isNotEmpty() },
    )
    if (parts.isEmpty()) return
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopEnd) {
        Text(
            parts.joinToString(" · "),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .padding(4.dp)
                .background(if (faults.isNotEmpty()) Color(0xCCB3261E) else Color(0xAA000000), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .testTag(RIBBON_TAG),
        )
    }
}

/** Draggable; a tap opens the dashboard and a drag never does. Its position is remembered in [preferences]. */
@Composable
private fun FloatingButton(initialPosition: Alignment, preferences: KeyValueStore?) {
    val scope = rememberCoroutineScope()
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(preferences) {
        val x = preferences?.getDouble(BUTTON_X) ?: return@LaunchedEffect
        val y = preferences.getDouble(BUTTON_Y) ?: return@LaunchedEffect
        offset = Offset(x.toFloat(), y.toFloat())
    }
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), contentAlignment = initialPosition) {
        Surface(
            shape = CircleShape,
            color = Color(0xFF3D3D3D),
            shadowElevation = 6.dp,
            modifier = Modifier
                .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                .size(48.dp)
                .pointerInput(preferences) {
                    detectDragGestures(
                        onDragEnd = {
                            val saved = offset
                            scope.launch {
                                preferences?.setDouble(BUTTON_X, saved.x.toDouble())
                                preferences?.setDouble(BUTTON_Y, saved.y.toDouble())
                            }
                        },
                    ) { change, drag ->
                        change.consume()
                        offset += drag
                    }
                }
                .clickable { DevTools.open() }
                .testTag(FLOATING_TAG),
        ) {
            Box(contentAlignment = Alignment.Center) { Text("DEV", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

/** A card over the app when an uncaught error is recorded, until it's dismissed. */
@Composable
private fun UncaughtErrorCard() {
    val rows by DevTools.exceptions.records.collectAsState()
    var dismissed by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    val latest: ExceptionRecord = rows.firstOrNull { it.source == ExceptionSource.Uncaught } ?: return
    if (dismissed == latest.id to latest.count) return
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), contentAlignment = Alignment.BottomCenter) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth().testTag(UNCAUGHT_TAG),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("Uncaught ${latest.type}" + if (latest.count > 1) " ×${latest.count}" else "", fontWeight = FontWeight.Bold)
                if (latest.message.isNotEmpty()) Text(latest.message, maxLines = 3)
                Row {
                    TextButton(onClick = { dismissed = latest.id to latest.count; DevTools.open() }) { Text("Open log") }
                    TextButton(onClick = { dismissed = latest.id to latest.count }, modifier = Modifier.testTag("uncaught-dismiss")) { Text("Dismiss") }
                }
            }
        }
    }
}

@Composable
private fun Monogram(letter: String) {
    Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
        Text(letter, fontWeight = FontWeight.Bold)
    }
}

/** devtools' own keys, in its own namespace. */
internal const val BUTTON_X = "sinew.devtools.button.x"
internal const val BUTTON_Y = "sinew.devtools.button.y"

internal const val FLOATING_TAG = "sinew-devtools-button"
internal const val MENU_ITEM_TAG = "sinew-devtools-menu-item"
internal const val RIBBON_TAG = "sinew-devtools-ribbon"
internal const val UNCAUGHT_TAG = "sinew-devtools-uncaught"
