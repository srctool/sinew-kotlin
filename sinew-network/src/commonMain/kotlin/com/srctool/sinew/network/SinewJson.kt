package com.srctool.sinew.network

import kotlinx.serialization.json.Json

/**
 * The JSON settings every Sinew client decodes with, also usable by an app's own code.
 *
 * - `ignoreUnknownKeys`: a field the backend adds doesn't break older apps.
 * - `explicitNulls = false`: a missing optional field reads as its default.
 * - `coerceInputValues = true`: an unknown enum value reads as the property's default, so a DTO enum property
 *   declares one (`val status: OrderStatusDto = OrderStatusDto.Unknown`) instead of failing the whole response.
 */
public val SinewJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}
