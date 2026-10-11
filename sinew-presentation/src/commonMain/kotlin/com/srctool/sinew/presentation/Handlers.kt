package com.srctool.sinew.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * A page with one or two actions: state and effects, with direct methods (`submit()`, `resend()`) and no event or
 * action types. A page that grows a third intent becomes an [EventActionHandler], a change of superclass.
 *
 * State is one immutable value changed only through [setState]; one-time outcomes go through [emitEffect]. Never
 * trigger a side effect from a state change. [dispose] closes the effects and cancels [scope], so running work stops
 * when the owner goes away.
 */
public abstract class StateEffectHandler<S, F>(initial: S, protected val scope: CoroutineScope) {
    private val _state = MutableStateFlow(initial)
    private val emitter = EffectEmitter<F>()

    @Volatile
    private var disposed = false

    /** The page's state, observable. */
    public val state: StateFlow<S> = _state.asStateFlow()

    /** The page's one-time effects, each delivered once. */
    public val effects: Flow<F> = emitter.effects

    /** The current state, for the handler's own reads. */
    protected val currentState: S get() = _state.value

    /** Whether [dispose] was called. */
    protected val isDisposed: Boolean get() = disposed

    /** Replaces the state with [reduce] applied to it, atomically. */
    protected fun setState(reduce: S.() -> S) {
        _state.update(reduce)
    }

    /** Emits a one-time [effect]. Does nothing after [dispose]. */
    protected fun emitEffect(effect: F) {
        emitter.emit(effect)
    }

    /** Closes the effects and cancels [scope]. Later calls do nothing. */
    public open fun dispose() {
        disposed = true
        emitter.dispose()
        scope.cancel()
    }
}

/**
 * A page with three or more intents, or intents that mean the same work: events in, state and effects out.
 *
 * The page only sends events. [mapEventToAction] is pure: it reads the state and returns the work to run, or `null`
 * for none right now, so every "should this run" decision is in one testable function. [onAction] does the work,
 * launched in [scope]; actions may run concurrently, and work that must not overlap is guarded in
 * [mapEventToAction] by reading state. A throw in [onAction] is a bug and propagates.
 */
public abstract class EventActionHandler<S, E, A, F>(initial: S, scope: CoroutineScope) : StateEffectHandler<S, F>(initial, scope) {
    /** Reports an event from the page. Ignored after [dispose]: no action runs and no state changes. */
    public fun onEvent(event: E) {
        if (isDisposed) return
        val action = mapEventToAction(event) ?: return
        scope.launch { onAction(action) }
    }

    /**
     * The action [event] means right now, or `null` for none. Pure: it reads [currentState] and changes no state.
     * The one allowed exception is a private once-flag, such as `opened` for loading on open.
     */
    protected abstract fun mapEventToAction(event: E): A?

    /** Does the work for [action]. */
    protected abstract suspend fun onAction(action: A)
}
