package com.srctool.sinew.models

/**
 * What a page renders. `Loading` and `Failed` can keep the previous data, so a reload doesn't blank the screen.
 * There's no idle state, and no success message: one-time reactions are effects, never state.
 */
public sealed interface ViewState<out T> {
    /**
     * Loading, with the previous data when there is some.
     *
     * @property data The previous data, or `null`.
     */
    public data class Loading<out T>(val data: T? = null) : ViewState<T>

    /**
     * Loaded.
     *
     * @property data The loaded data.
     */
    public data class Done<out T>(val data: T) : ViewState<T>

    /**
     * Keeps the [SinewException], never pre-rendered text, so a language switch re-renders it.
     *
     * @property data The previous data, or `null`.
     * @property error The failure.
     */
    public data class Failed<out T>(val data: T? = null, val error: SinewException) : ViewState<T>
}

/** The data to render, from any state. */
public val <T> ViewState<T>.dataOrNull: T?
    get() = when (this) {
        is ViewState.Loading -> data
        is ViewState.Done -> data
        is ViewState.Failed -> data
    }

/** Whether this is [ViewState.Loading]. */
public val ViewState<*>.isLoading: Boolean get() = this is ViewState.Loading
