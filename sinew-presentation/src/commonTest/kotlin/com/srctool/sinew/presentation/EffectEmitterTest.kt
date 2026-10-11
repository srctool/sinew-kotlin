package com.srctool.sinew.presentation

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EffectEmitterTest {
    @Test
    fun anEffectEmittedBeforeAnyCollectorReachesTheFirstCollector() = runTest {
        val emitter = EffectEmitter<String>()
        emitter.emit("saved")                                                  // e.g. during a rotation
        emitter.effects.test {
            assertEquals("saved", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aSecondCollectorDoesNotGetItAgain() = runTest {
        val emitter = EffectEmitter<String>()
        emitter.emit("saved")
        emitter.effects.test {
            assertEquals("saved", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        emitter.emit("next")
        emitter.effects.test {
            assertEquals("next", awaitItem())                                  // only the new one
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun eachEffectIsDeliveredOnceInOrder() = runTest {
        val emitter = EffectEmitter<Int>()
        repeat(3) { emitter.emit(it) }
        emitter.effects.test {
            assertEquals(listOf(0, 1, 2), List(3) { awaitItem() })
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun emitAfterDisposeDoesNothingAndTheStreamCompletes() = runTest {
        val emitter = EffectEmitter<String>()
        emitter.dispose()
        emitter.emit("late")
        emitter.effects.test { awaitComplete() }
    }
}
