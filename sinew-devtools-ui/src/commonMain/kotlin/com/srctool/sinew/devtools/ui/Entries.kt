package com.srctool.sinew.devtools.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * A titled group of entries on the dashboard's first screen. Sinew's built-in inspectors are the first section; the
 * app's own sections follow, in the order given.
 */
public class DevToolSection(public val title: String, public val entries: List<DevToolEntry>)

/** One row on the dashboard. */
public sealed interface DevToolEntry {
    /** The row's title. */
    public val name: String

    /** A line under the title, or `null`. */
    public val description: String?

    /** The row's icon, or `null` for a monogram of [name]. */
    public val icon: ImageVector?

    /**
     * Opens a page with [content], under a top bar with [name] and [appBarActions]. The page runs in the dashboard's
     * own theme and back stack, never the app's.
     */
    public class Page(
        override val name: String,
        override val description: String? = null,
        override val icon: ImageVector? = null,
        public val color: Color? = null,
        public val appBarActions: @Composable RowScope.() -> Unit = {},
        public val content: @Composable () -> Unit,
    ) : DevToolEntry

    /** Opens a sub-list of [children]. */
    public class Group(
        override val name: String,
        override val description: String? = null,
        override val icon: ImageVector? = null,
        public val color: Color? = null,
        public val children: List<DevToolEntry>,
    ) : DevToolEntry

    /** A switch shown inline: [value] is read when the row draws, [onChanged] called when it's flipped. */
    public class Toggle(
        override val name: String,
        override val description: String? = null,
        override val icon: ImageVector? = null,
        public val value: () -> Boolean,
        public val onChanged: (Boolean) -> Unit,
    ) : DevToolEntry

    /** Runs [run] on tap. With [confirm], a dialog with that text asks first. */
    public class Action(
        override val name: String,
        override val description: String? = null,
        override val icon: ImageVector? = null,
        public val confirm: String? = null,
        public val run: suspend () -> Unit,
    ) : DevToolEntry
}

/** A label in a corner of every screen, such as `1.4.0 (212) · staging`. The active host and faults are added to it. */
public class BuildRibbon(public val label: String)

/**
 * How the dashboard opens. Whichever is chosen, the dashboard, its theme and its back stack are the same, and
 * recording, fault rules and the ribbon work the same.
 */
public sealed interface DevToolsLauncher

/**
 * A floating button, draggable, that opens the dashboard on tap; a drag never opens it. It starts at
 * [initialPosition], and where it's dragged to is remembered in `SinewDevTools`' `preferences`.
 */
public class FloatingLauncher(public val initialPosition: Alignment = Alignment.BottomEnd) : DevToolsLauncher

/** No floating button: the app places `DevToolsMenuItem` where it wants, in a settings screen or a drawer. */
public data object MenuLauncher : DevToolsLauncher

/** No UI from devtools: the app calls `DevTools.open()` and `DevTools.close()` from its own trigger. */
public data object CustomLauncher : DevToolsLauncher
