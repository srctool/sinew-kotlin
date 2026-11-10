package com.srctool.sinew.models

import android.content.Context

/**
 * The Android implementation, wrapping a [Context].
 *
 * @property context The wrapped context.
 */
public actual class PlatformContext(public val context: Context)
