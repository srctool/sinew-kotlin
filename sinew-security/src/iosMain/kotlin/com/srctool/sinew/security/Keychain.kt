package com.srctool.sinew.security

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/** A CoreFoundation dictionary being filled: Kotlin values are bridged, and released with the dictionary. */
@OptIn(ExperimentalForeignApi::class)
internal class CFQuery(val dictionary: CFMutableDictionaryRef?) {
    val bridged = mutableListOf<CFTypeRef?>()

    fun put(key: CFStringRef?, value: CFTypeRef?) = CFDictionaryAddValue(dictionary, key, value)

    /** Bridges a Kotlin or Objective-C [value] (a `String`, `NSData`, an `LAContext`) and adds it. */
    fun putBridged(key: CFStringRef?, value: Any) {
        val ref = CFBridgingRetain(value)
        bridged += ref
        CFDictionaryAddValue(dictionary, key, ref)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun <T> withDictionary(fill: (CFQuery) -> Unit, block: (CFDictionaryRef?) -> T): T {
    val query = CFQuery(CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr))
    try {
        fill(query)
        return block(query.dictionary)
    } finally {
        query.bridged.forEach { CFRelease(it) }
        CFRelease(query.dictionary)
    }
}

/** A Keychain call failed with [status], an `OSStatus`. */
internal class KeychainException(operation: String, val status: Int) : IllegalStateException("Keychain $operation failed: $status")

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray = ByteArray(length.toInt()).also { bytes ->
    if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), this.bytes, length) }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun ByteArray.toNSData(): NSData = usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
