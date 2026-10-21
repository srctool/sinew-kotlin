package com.srctool.sinew.testing

import java.net.SocketException

internal actual fun droppedConnection(): Throwable = SocketException("Connection reset")
