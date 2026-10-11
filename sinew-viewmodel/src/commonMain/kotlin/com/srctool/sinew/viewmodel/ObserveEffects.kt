package com.srctool.sinew.viewmodel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Collects a page's one-time [effects] while the screen is at least started, handling each with [onEffect].
 *
 * An effect emitted while the screen is stopped waits in the buffer and is handled once when it starts again.
 * Collection runs on `Dispatchers.Main.immediate`, so an effect is handled in the frame it's received and isn't lost
 * between a lifecycle stop and the collection ending. Use one collector per effects flow: each effect goes to one.
 */
@Composable
public fun <F> ObserveEffects(effects: Flow<F>, onEffect: suspend (F) -> Unit) {
    val owner = LocalLifecycleOwner.current
    val handle by rememberUpdatedState(onEffect)
    LaunchedEffect(effects, owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            withContext(Dispatchers.Main.immediate) {
                effects.collect { handle(it) }
            }
        }
    }
}
