package com.srctool.sinew.models.interop

import com.srctool.sinew.models.Result
import com.srctool.sinew.models.getOrThrow
import kotlin.test.Test
import kotlin.test.assertEquals

// Outside Sinew's package, an explicit import of Sinew's Result wins over Kotlin's default `kotlin.Result` import.
class ResultImportTest {
    @Test
    fun explicitImportResolvesToSinewsResult() {
        val sinew: Result<Int> = Result.Success(1, "ok")
        val standard: kotlin.Result<Int> = kotlin.Result.success(2)
        assertEquals(1, sinew.getOrThrow())
        assertEquals(2, standard.getOrThrow())
    }
}
