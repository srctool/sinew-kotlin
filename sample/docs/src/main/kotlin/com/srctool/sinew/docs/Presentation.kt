package com.srctool.sinew.docs

import com.srctool.sinew.viewmodel.StateEffectViewModel

// region counter
data class CounterState(val count: Int = 0)

/** A one-time reaction: never state, so a rebuild can't repeat it. */
data object ReachedTen

class CounterViewModel : StateEffectViewModel<CounterState, ReachedTen>(CounterState()) {
    fun increment() {
        setState { copy(count = count + 1) }
        if (state.value.count == 10) emitEffect(ReachedTen)
    }
}
// endregion
