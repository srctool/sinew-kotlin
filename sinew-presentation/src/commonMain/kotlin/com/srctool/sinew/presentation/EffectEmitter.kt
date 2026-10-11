package com.srctool.sinew.presentation

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * One-time outcomes for a page: a snackbar, a dialog, a navigation callback. Not state, because each must happen
 * once and must not replay on rebuild.
 *
 * An effect emitted while nobody collects waits in a buffer and goes to the next collector, exactly once, so a
 * snackbar emitted during a rotation is neither lost nor shown twice. Beyond 64 pending effects, new ones are
 * dropped: a page never legitimately queues that many.
 */
public class EffectEmitter<F> {
    private val channel = Channel<F>(Channel.BUFFERED)

    /** The effects, each delivered to one collector, once. Completes after [dispose] once the buffer is drained. */
    public val effects: Flow<F> = channel.receiveAsFlow()

    /** Emits [effect] without suspending. Does nothing after [dispose]. */
    public fun emit(effect: F) {
        channel.trySend(effect)
    }

    /** Closes the effect stream. */
    public fun dispose() {
        channel.close()
    }
}
