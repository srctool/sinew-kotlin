package com.srctool.sinew.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ErrorBodyReaderTest {
    private fun read(body: String?) = ErrorBodyReader.Default.read(422, body)

    @Test
    fun readsMessageAndErrors() {
        val body = """{"message":"Check the form.","errors":{"email":["Taken.","Too long."],"name":"Required."}}"""
        assertEquals(
            ErrorBody("Check the form.", mapOf("email" to listOf("Taken.", "Too long."), "name" to listOf("Required."))),
            read(body),
        )
    }

    @Test
    fun readsAMessageAlone() = assertEquals(ErrorBody("Out of stock.", null), read("""{"message":"Out of stock."}"""))

    @Test
    fun readsErrorsAlone() =
        assertEquals(ErrorBody(null, mapOf("email" to listOf("Taken."))), read("""{"errors":{"email":["Taken."]}}"""))

    @Test
    fun ignoresUnknownKeys() =
        assertEquals(ErrorBody("No.", null), read("""{"code":"E42","message":"No.","trace":{"id":1}}"""))

    @Test
    fun aBlankMessageIsUnreadable() = assertNull(read("""{"message":"   "}"""))

    @Test
    fun blankAndNonStringFieldMessagesAreDropped() {
        assertEquals(
            ErrorBody("Bad.", mapOf("email" to listOf("Taken."))),
            read("""{"message":"Bad.","errors":{"email":["Taken.", "", 3, null],"name":[],"age":42}}"""),
        )
    }

    @Test
    fun aNonStringMessageIsIgnored() = assertNull(read("""{"message":{"text":"nested"}}"""))

    @Test
    fun returnsNullForAnHtmlPage() = assertNull(read("<html><body><h1>502 Bad Gateway</h1></body></html>"))

    @Test
    fun returnsNullForAnEmptyOrMissingBody() {
        assertNull(read(null))
        assertNull(read(""))
        assertNull(read("  \n"))
    }

    @Test
    fun returnsNullForNonJsonAndNonObjects() {
        assertNull(read("Service Unavailable"))
        assertNull(read("""{"message": "unterminated"""))
        assertNull(read("""["message"]"""))
        assertNull(read("\"just a string\""))
        assertNull(read("{}"))
    }

    @Test
    fun aCustomReaderReceivesTheStatus() {
        val reader = ErrorBodyReader { status, body -> ErrorBody("$status: $body", null) }
        assertEquals(ErrorBody("409: taken", null), reader.read(409, "taken"))
    }
}
