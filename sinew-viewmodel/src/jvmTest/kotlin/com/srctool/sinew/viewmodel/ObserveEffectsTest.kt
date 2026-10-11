package com.srctool.sinew.viewmodel

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.srctool.sinew.presentation.EffectEmitter
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals

private class TestLifecycleOwner : LifecycleOwner {
    val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.CREATED }
    override val lifecycle: Lifecycle get() = registry
}

/**
 * Waits for Compose and for the main (Swing) thread, where ObserveEffects collects: waitForIdle alone doesn't wait
 * for work dispatched to Dispatchers.Main, so a check could run before an effect lands, or pass before it would.
 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.settle() {
    repeat(3) {
        waitForIdle()
        SwingUtilities.invokeAndWait {}
    }
}

@OptIn(ExperimentalTestApi::class)
class ObserveEffectsTest {
    @Test
    fun anEffectEmittedWhileStoppedIsHandledOnceAfterStart() = runComposeUiTest {
        val owner = TestLifecycleOwner()
        val emitter = EffectEmitter<String>()
        val handled = mutableListOf<String>()
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                ObserveEffects(emitter.effects) { handled += it }
            }
        }

        emitter.emit("saved")                                                   // the screen is only CREATED
        settle()
        assertEquals(emptyList(), handled)

        runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        settle()
        assertEquals(listOf("saved"), handled)

        runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }    // stop, then start again
        settle()
        runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        settle()
        assertEquals(listOf("saved"), handled, "the effect was replayed")

        emitter.emit("next")
        settle()
        assertEquals(listOf("saved", "next"), handled)
    }
}
