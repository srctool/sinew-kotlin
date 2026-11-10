package com.srctool.sinew.docs

import com.srctool.sinew.exception.CrashReporter
import com.srctool.sinew.exception.ExceptionHandler
import com.srctool.sinew.exception.Localizer
import com.srctool.sinew.exception.ParseException
import com.srctool.sinew.exception.displayMessage
import com.srctool.sinew.exception.processCall
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.SinewException
import kotlinx.serialization.SerializationException

fun interface OrderApi {
    suspend fun order(id: Int): Order
}

// region guard
suspend fun loadOrder(api: OrderApi): Result<Order> = processCall(
    module = "ORD",
    function = "load",
    layer = ExceptionLayer.Repository,
    handlers = listOf(
        ExceptionHandler(
            matches = { it is SerializationException },
            transform = { ParseException("ORD", ExceptionLayer.Repository, "load", cause = it) },
        ),
    ),
) { api.order(7) }
// endregion

// region display
fun errorText(error: SinewException, localizer: Localizer?): String = error.displayMessage(localizer)
// endregion

// region crash-reporter
fun installReporter() {
    CrashReporter.install(CrashReporter { error -> println("reported ${error.code}") })
}
// endregion
