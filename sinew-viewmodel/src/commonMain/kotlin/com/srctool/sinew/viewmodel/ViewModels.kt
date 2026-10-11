package com.srctool.sinew.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.srctool.sinew.presentation.EffectEmitter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * `StateEffectHandler` on an AndroidX [ViewModel], which survives rotation and owns [viewModelScope]: state and
 * effects with direct methods, for a page with one or two actions.
 *
 * Clearing the view model closes the effects; running work stops with [viewModelScope].
 */
public abstract class StateEffectViewModel<S, F>(initial: S) : ViewModel() {
    private val _state = MutableStateFlow(initial)
    private val emitter = EffectEmitter<F>()

    @Volatile
    private var cleared = false

    init {
        // Registered as a closeable rather than in onCleared(), so a subclass that overrides onCleared() and
        // forgets super can't leak the effect channel.
        addCloseable(
            AutoCloseable {
                cleared = true
                emitter.dispose()
            },
        )
    }

    /** The page's state, observable. */
    public val state: StateFlow<S> = _state.asStateFlow()

    /** The page's one-time effects, each delivered once. Collect them with [ObserveEffects]. */
    public val effects: Flow<F> = emitter.effects

    /** The current state, for the view model's own reads. */
    protected val currentState: S get() = _state.value

    /** Whether the view model was cleared. */
    protected val isCleared: Boolean get() = cleared

    /** Replaces the state with [reduce] applied to it, atomically. */
    protected fun setState(reduce: S.() -> S) {
        _state.update(reduce)
    }

    /** Emits a one-time [effect]. Does nothing after the view model is cleared. */
    protected fun emitEffect(effect: F) {
        emitter.emit(effect)
    }
}

/**
 * `EventActionHandler` on an AndroidX [ViewModel]: events in, state and effects out, with actions launched in
 * [viewModelScope].
 *
 * The page only sends events. [mapEventToAction] is pure: it reads the state and returns the work to run, or `null`
 * for none right now. [onAction] does the work; actions may run concurrently, and work that must not overlap is
 * guarded in [mapEventToAction] by reading state. A throw in [onAction] is a bug and propagates.
 */
public abstract class EventActionViewModel<S, E, A, F>(initial: S) : StateEffectViewModel<S, F>(initial) {
    /** Reports an event from the page. Ignored after the view model is cleared: no action runs and no state changes. */
    public fun onEvent(event: E) {
        if (isCleared) return
        val action = mapEventToAction(event) ?: return
        viewModelScope.launch { onAction(action) }
    }

    /**
     * The action [event] means right now, or `null` for none. Pure: it reads [currentState] and changes no state.
     * The one allowed exception is a private once-flag, such as `opened` for loading on open.
     */
    protected abstract fun mapEventToAction(event: E): A?

    /** Does the work for [action]. */
    protected abstract suspend fun onAction(action: A)
}
