package com.srctool.sinew.devtools

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class UncaughtHookJvmTest {
    private val original = Thread.getDefaultUncaughtExceptionHandler()

    @AfterTest
    fun restore() = Thread.setDefaultUncaughtExceptionHandler(original)

    @Test
    fun theHookChainsToThePreviousHandler() {
        val seen = mutableListOf<String>()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> seen += "previous ${e.message}" }
        installUncaughtHook { seen += "devtools ${it.message}" }
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))
        assertEquals(listOf("devtools boom", "previous boom"), seen)
    }

    @Test
    fun aFailureWhileRecordingStillRunsThePreviousHandler() {
        val seen = mutableListOf<String>()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> seen += "previous ${e.message}" }
        installUncaughtHook { error("recording failed") }
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))
        assertEquals(listOf("previous boom"), seen)
    }

    @Test
    fun withoutAPreviousHandlerOnlyDevtoolsRuns() {
        Thread.setDefaultUncaughtExceptionHandler(null)
        val seen = mutableListOf<String>()
        installUncaughtHook { seen += "devtools ${it.message}" }
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))
        assertEquals(listOf("devtools boom"), seen)
    }

    @Test
    fun captureUncaughtErrorsRecordsIntoTheExceptionLog() {
        Thread.setDefaultUncaughtExceptionHandler(null)
        DevTools.exceptions.clear()
        DevTools.captureUncaughtErrors()
        DevTools.captureUncaughtErrors()                    // a second call doesn't stack another hook
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), IllegalStateException("crash"))
        val row = DevTools.exceptions.records.value.single()
        assertEquals(1, row.count)
        assertEquals(ExceptionSource.Uncaught, row.source)
        DevTools.exceptions.clear()
    }
}
