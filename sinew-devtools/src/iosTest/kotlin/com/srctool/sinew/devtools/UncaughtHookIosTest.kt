package com.srctool.sinew.devtools

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalNativeApi::class)
class UncaughtHookIosTest {
    @Test
    fun theHookChainsToThePreviousHook() {
        val seen = mutableListOf<String>()
        val original = setUnhandledExceptionHook { seen += "previous ${it.message}" }
        try {
            installUncaughtHook { seen += "devtools ${it.message}" }
            val installed = setUnhandledExceptionHook(null)!!           // read the hook back without leaving it unset
            setUnhandledExceptionHook(installed)
            installed(IllegalStateException("boom"))
            assertEquals(listOf("devtools boom", "previous boom"), seen)
        } finally {
            setUnhandledExceptionHook(original)
        }
    }

    @Test
    fun aFailureWhileRecordingStillRunsThePreviousHook() {
        val seen = mutableListOf<String>()
        val original = setUnhandledExceptionHook { seen += "previous ${it.message}" }
        try {
            installUncaughtHook { error("recording failed") }
            val installed = setUnhandledExceptionHook(null)!!
            setUnhandledExceptionHook(installed)
            installed(IllegalStateException("boom"))
            assertEquals(listOf("previous boom"), seen)
        } finally {
            setUnhandledExceptionHook(original)
        }
    }
}
