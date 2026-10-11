@file:OptIn(ExperimentalCoroutinesApi::class)

package com.srctool.sinew.presentation

import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A scope the handler owns: a child of the test's background scope, so dispose() cancels only it. */
internal fun TestScope.handlerScope() = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))

private class CounterHandler(scope: CoroutineScope) : StateEffectHandler<Int, String>(0, scope) {
    fun increment() = setState { this + 1 }
    fun notifyUser() = emitEffect("hello")
    val running get() = scope
}

private sealed interface Event {
    data object Tapped : Event
    data object Ignored : Event
    data object LongTask : Event
}

private class TapHandler(scope: CoroutineScope) : EventActionHandler<Int, Event, String, Nothing>(0, scope) {
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
}

class HandlersTest {
    @Test
    fun setStateUpdatesAndNotifies() = runTest {
        val handler = CounterHandler(handlerScope())
        handler.state.test {
            assertEquals(0, awaitItem())
            handler.increment()
            assertEquals(1, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun emitEffectGoesThroughTheEmitter() = runTest {
        val handler = CounterHandler(handlerScope())
        handler.notifyUser()
        handler.effects.test {
            assertEquals("hello", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun disposeClosesTheEffectsAndCancelsTheScope() = runTest {
        val handler = CounterHandler(handlerScope())
        handler.dispose()
        handler.effects.test { awaitComplete() }
        assertFalse(handler.running.coroutineContext[Job]!!.isActive)
    }

    @Test
    fun onEventRunsTheMappedAction() = runTest {
        val handler = TapHandler(handlerScope())
        handler.onEvent(Event.Tapped); runCurrent()
        assertEquals(listOf("count"), handler.ran)
        assertEquals(1, handler.state.value)
    }

    @Test
    fun aNullActionRunsNothing() = runTest {
        val handler = TapHandler(handlerScope())
        handler.onEvent(Event.Ignored); runCurrent()
        assertEquals(listOf<Event>(Event.Ignored), handler.mapped)
        assertTrue(handler.ran.isEmpty())
    }

    @Test
    fun disposeCancelsARunningAction() = runTest {
        val handler = TapHandler(handlerScope())
        handler.onEvent(Event.LongTask); runCurrent()
        handler.dispose(); runCurrent()
        assertTrue(handler.longTaskCancelled)
    }

    @Test
    fun eventsAfterDisposeAreIgnored() = runTest {
        val handler = TapHandler(handlerScope())
        handler.dispose()
        handler.onEvent(Event.Tapped); runCurrent()
        assertTrue(handler.mapped.isEmpty(), "mapEventToAction ran after dispose")
        assertTrue(handler.ran.isEmpty())
        assertEquals(0, handler.state.value)
    }
}
