package com.srctool.sinew.devtools.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.srctool.sinew.devtools.Redaction
import com.srctool.sinew.storage.InspectableStore
import com.srctool.sinew.storage.KeyValueStore

// The no-op stand-in for `sinew-devtools-ui`: the same public API, checked against the real one's ABI dump in CI. No
// overlay, no button, no dashboard: production builds show only the app.

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
    content()
}

/** Draws nothing. */
@Composable
public fun DevToolsMenuItem(modifier: Modifier = Modifier) {}
