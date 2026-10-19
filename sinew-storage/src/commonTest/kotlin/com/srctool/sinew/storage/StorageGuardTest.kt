package com.srctool.sinew.storage

import com.srctool.sinew.exception.CrashReporter
import com.srctool.sinew.exception.LocalStorageCorruptionException
import com.srctool.sinew.exception.ParseException
import com.srctool.sinew.exception.StorageFullException
import com.srctool.sinew.exception.StorageReadException
import com.srctool.sinew.exception.StorageWriteException
import com.srctool.sinew.exception.UnexpectedException
import com.srctool.sinew.models.EnvelopeDataMissing
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.SinewException
import com.srctool.sinew.security.DecryptionFailed
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** One test per handler row, with each platform's own errors from [diskFullErrors] and [otherStorageErrors]. */
class StorageGuardTest {
    private val reported = mutableListOf<SinewException>()

    @BeforeTest
    fun record() = CrashReporter.install { reported += it }

    @AfterTest
    fun reset() = CrashReporter.install(CrashReporter.None)

    private suspend fun fail(error: Throwable, access: StorageAccess = StorageAccess.Read): SinewException {
        val result = processStorageCall<String>("PRF", "GTH", access) { throw error }
        return assertIs<Result.Failure>(result, "$error").error
    }

    @Test
    fun aValueComesBack() = runTest {
        assertEquals(Result.Success("dark"), processStorageCall("PRF", "GTH", StorageAccess.Read) { "dark" })
    }

    @Test
    fun corruptionIsLocalStorageCorruption() = runTest {
        for (error in listOf(DecryptionFailed(), SerializationException("bad"), RuntimeException("wrapped", DecryptionFailed())) + corruptionErrors()) {
            val failure = assertIs<LocalStorageCorruptionException>(fail(error), "$error")
            assertEquals("PRF-R-GTH-LSC", failure.code)
        }
    }

    @Test
    fun aFullDeviceIsStorageFullOnReadsAndWrites() = runTest {
        assertTrue(diskFullErrors().isNotEmpty())
        for (error in diskFullErrors() + RuntimeException("wrapped", diskFullErrors().first())) {
            for (access in StorageAccess.entries) {
                val failure = assertIs<StorageFullException>(fail(error, access), "$error on $access")
                assertEquals("PRF-R-GTH-SF", failure.code)
            }
        }
    }

    @Test
    fun anotherStorageErrorIsAReadOrWriteFailureByAccess() = runTest {
        assertTrue(otherStorageErrors().isNotEmpty())
        for (error in otherStorageErrors()) {
            assertIs<StorageReadException>(fail(error, StorageAccess.Read), "$error")
            assertIs<StorageWriteException>(fail(error, StorageAccess.Write), "$error")
        }
        assertTrue(reported.isEmpty(), "storage failures aren't reported")
    }

    @Test
    fun aMissingEnvelopePayloadIsAParseFailureAndReported() = runTest {
        assertIs<ParseException>(fail(EnvelopeDataMissing()))
        assertEquals(1, reported.size)
    }

    @Test
    fun aBugIsUnexpectedAndReported() = runTest {
        assertIs<UnexpectedException>(fail(IllegalStateException("boom")))
        assertEquals(1, reported.size)
    }

    @Test
    fun corruptionWinsOverTheErrorItWraps() = runTest {
        assertIs<LocalStorageCorruptionException>(fail(DecryptionFailed(diskFullErrors().first())))
    }

    @Test
    fun aSinewExceptionPassesThrough() = runTest {
        val error = StorageFullException("OTH", ExceptionLayer.Utility, "XYZ")
        assertSame(error, fail(error))
    }
}

/** The platform's disk-full errors, as its storage libraries throw them. */
expect fun diskFullErrors(): List<Throwable>

/** The platform's other storage errors. */
expect fun otherStorageErrors(): List<Throwable>

/** The storage library's own corruption errors, beyond the common ones. */
expect fun corruptionErrors(): List<Throwable>
