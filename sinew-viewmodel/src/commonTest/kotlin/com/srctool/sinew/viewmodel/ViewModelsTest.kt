@file:OptIn(ExperimentalCoroutinesApi::class)

package com.srctool.sinew.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class CounterViewModel : StateEffectViewModel<Int, String>(0) {
    fun increment() = setState { this + 1 }
    fun notifyUser() = emitEffect("hello")
}

private sealed interface Event {
    data object Tapped : Event
    data object Ignored : Event
    data object LongTask : Event
}

private class TapViewModel : EventActionViewModel<Int, Event, String, String>(0) {
    val mapped = mutableListOf<Event>()
    val ran = mutableListOf<String>()
    var longTaskCancelled = false

    override fun mapEventToAction(event: Event): String? {
        mapped += event
        return when (event) {
            Event.Tapped -> "count"
            Event.Ignored -> null
            Event.LongTask -> "long"
        }
    }

    override suspend fun onAction(action: String) {
        ran += action
        when (action) {
            "count" -> setState { this + 1 }
            "long" -> try {
                awaitCancellation()
            } finally {
                longTaskCancelled = true
            }
        }
    }

    // A subclass that overrides onCleared without calling super must not break clean-up.
    @Suppress("MissingSuperCall")
    override fun onCleared() {}
}

/** Creates [create]'s view model in a real ViewModelStore, so clearing goes through AndroidX's own path. */
private inline fun <reified VM : ViewModel> TestScope.inStore(noinline create: () -> VM): Pair<VM, ViewModelStore> {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))                 // viewModelScope runs on Main
    val store = ViewModelStore()
    val viewModel = ViewModelProvider.create(store, viewModelFactory { initializer { create() } })[VM::class]
    return viewModel to store
}

class ViewModelsTest {
    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun setStateUpdatesAndNotifies() = runTest {
        val (viewModel, _) = inStore { CounterViewModel() }
        viewModel.state.test {
            assertEquals(0, awaitItem())
            viewModel.increment()
            assertEquals(1, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun anEffectEmittedBeforeCollectionIsDeliveredOnce() = runTest {
        val (viewModel, _) = inStore { CounterViewModel() }
        viewModel.notifyUser()
        viewModel.effects.test {
            assertEquals("hello", awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun onEventRunsOnActionInViewModelScope() = runTest {
        val (viewModel, _) = inStore { TapViewModel() }
        viewModel.onEvent(Event.Tapped); runCurrent()
        assertEquals(listOf("count"), viewModel.ran)
        assertEquals(1, viewModel.state.value)
    }

    @Test
    fun aNullActionRunsNothing() = runTest {
        val (viewModel, _) = inStore { TapViewModel() }
        viewModel.onEvent(Event.Ignored); runCurrent()
        assertTrue(viewModel.ran.isEmpty())
    }

    @Test
    fun clearingCancelsARunningActionAndClosesTheEffects() = runTest {
        val (viewModel, store) = inStore { TapViewModel() }
        viewModel.onEvent(Event.LongTask); runCurrent()
        store.clear(); runCurrent()
        assertTrue(viewModel.longTaskCancelled)
        viewModel.effects.test { awaitComplete() }                              // closed even though onCleared skipped super
    }

    @Test
    fun eventsAfterClearingAreIgnored() = runTest {
        val (viewModel, store) = inStore { TapViewModel() }
        store.clear()
        viewModel.onEvent(Event.Tapped); runCurrent()
        assertTrue(viewModel.mapped.isEmpty(), "mapEventToAction ran after clearing")
        assertEquals(0, viewModel.state.value)
    }
}
