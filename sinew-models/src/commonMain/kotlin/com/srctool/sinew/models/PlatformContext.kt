package com.srctool.sinew.models

/**
 * What a Sinew factory needs from the platform: the Android `Context` on Android, nothing elsewhere.
 * Every factory takes one, so the same wiring line compiles in `commonMain`.
 */
public expect class PlatformContext
