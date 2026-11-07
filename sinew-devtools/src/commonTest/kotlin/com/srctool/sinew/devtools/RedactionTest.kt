package com.srctool.sinew.devtools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RedactionTest {
    private val redaction = Redaction.Default
    private val mask = Redaction.Default.mask

    private fun json(body: String, contentType: String? = "application/json"): JsonObject {
        val recorded = assertIs<RecordedBody.Text>(redaction.redactBody(body.encodeToByteArray(), contentType))
        return Json.parseToJsonElement(recorded.text).jsonObject
    }

    // Headers

    @Test
    fun theDefaultHeadersAreRedacted() {
        val headers = mapOf(
            "Authorization" to listOf("Bearer abc"),
            "Cookie" to listOf("a=1", "b=2"),
            "Set-Cookie" to listOf("s=1"),
            "Proxy-Authorization" to listOf("Basic xyz"),
            "Accept" to listOf("application/json"),
        )
        assertEquals(
            mapOf(
                "Authorization" to listOf(mask),
                "Cookie" to listOf(mask, mask),
                "Set-Cookie" to listOf(mask),
                "Proxy-Authorization" to listOf(mask),
                "Accept" to listOf("application/json"),
            ),
            redaction.redactHeaders(headers),
        )
    }

    @Test
    fun headerNamesMatchWhateverTheirCase() {
        assertEquals(mapOf("authorization" to listOf(mask)), redaction.redactHeaders(mapOf("authorization" to listOf("x"))))
        assertEquals(mapOf("SET_COOKIE" to listOf(mask)), redaction.redactHeaders(mapOf("SET_COOKIE" to listOf("x"))))
    }

    @Test
    fun aHeaderNamedLikeASecretFieldIsRedacted() {
        val redacted = redaction.redactHeaders(mapOf("X-Api-Key" to listOf("k"), "X-Refresh-Token" to listOf("t"), "X-Request-Id" to listOf("r")))
        assertEquals(listOf(mask), redacted["X-Api-Key"])
        assertEquals(listOf(mask), redacted["X-Refresh-Token"])
        assertEquals(listOf("r"), redacted["X-Request-Id"])
    }

    @Test
    fun everyDefaultHeadersNameIsRedacted() {
        val defaultHeaders = mapOf("X-Device-Id" to "d-1", "X-Client" to "app", "Tenant" to "acme")
        val forClient = redaction.withHeaders(defaultHeaders.keys)
        val redacted = forClient.redactHeaders(defaultHeaders.mapValues { listOf(it.value) } + ("Accept" to listOf("*/*")))
        assertEquals(listOf(mask), redacted["X-Device-Id"])
        assertEquals(listOf(mask), redacted["X-Client"])
        assertEquals(listOf(mask), redacted["Tenant"])
        assertEquals(listOf("*/*"), redacted["Accept"])
        assertEquals(listOf(mask), forClient.redactHeaders(mapOf("Authorization" to listOf("x")))["Authorization"])
    }

    // Field names

    @Test
    fun namesAreNormalizedBeforeMatching() {
        for (name in listOf("refreshToken", "refresh_token", "Refresh-Token", "newPassword", "idToken", "X-Api-Key", "otp.code", "PIN")) {
            assertTrue(redaction.isSensitive(name), name)
        }
        for (name in listOf("username", "email", "status", "codename", "id")) {
            assertFalse(redaction.isSensitive(name), name)
        }
    }

    @Test
    fun anExactFieldMatchesOnlyTheWholeName() {
        assertTrue(redaction.isSensitive("code"))
        assertTrue(redaction.isSensitive("Code"))
        assertFalse(redaction.isSensitive("zipCode"))
        assertFalse(redaction.isSensitive("countryCode"))
    }

    @Test
    fun topLevelFieldsAreRedacted() {
        val body = json("""{"username":"ana","password":"hunter2","refreshToken":"r","code":"123456","age":30}""")
        assertEquals("ana", body["username"]!!.jsonPrimitive.content)
        assertEquals(mask, body["password"]!!.jsonPrimitive.content)
        assertEquals(mask, body["refreshToken"]!!.jsonPrimitive.content)
        assertEquals(mask, body["code"]!!.jsonPrimitive.content)
        assertEquals("30", body["age"]!!.jsonPrimitive.content)
    }

    @Test
    fun fieldsNestedInObjectsAndArraysAreRedacted() {
        val body = json(
            """{"user":{"name":"ana","credentials":[{"password":"p"}],"devices":[{"id":1,"pushToken":"t"},{"id":2,"pushToken":"u"}]},
               "items":[[{"idToken":"i"}]]}""",
        )
        val user = body["user"]!!.jsonObject
        assertEquals("ana", user["name"]!!.jsonPrimitive.content)
        assertEquals(mask, user["credentials"]!!.jsonPrimitive.content)          // the whole value, whatever its shape
        val devices = user["devices"]!!.jsonArray
        assertEquals(listOf(mask, mask), devices.map { it.jsonObject["pushToken"]!!.jsonPrimitive.content })
        assertEquals(listOf("1", "2"), devices.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        val nested = body["items"]!!.jsonArray[0].jsonArray[0].jsonObject
        assertEquals(mask, nested["idToken"]!!.jsonPrimitive.content)
    }

    @Test
    fun aTopLevelArrayIsWalked() {
        val recorded = assertIs<RecordedBody.Text>(redaction.redactBody("""[{"secret":"s"},{"name":"n"}]""".encodeToByteArray(), "application/json"))
        val array = Json.parseToJsonElement(recorded.text) as JsonArray
        assertEquals(mask, array[0].jsonObject["secret"]!!.jsonPrimitive.content)
        assertEquals("n", array[1].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun aJsonBodyWithoutAJsonContentTypeIsStillRedacted() {
        assertEquals(mask, json("""{"password":"p"}""", contentType = "text/plain")["password"]!!.jsonPrimitive.content)
        assertEquals(mask, json("""  {"password":"p"}""", contentType = null)["password"]!!.jsonPrimitive.content)
    }

    @Test
    fun aJsonBodyThatDoesNotParseIsMaskedWhole() {
        val recorded = assertIs<RecordedBody.Text>(redaction.redactBody("""{"password":"p"""".encodeToByteArray(), "application/json"))
        assertEquals(mask, recorded.text)
        assertFalse(recorded.truncated)
    }

    @Test
    fun plainTextIsKept() {
        val recorded = assertIs<RecordedBody.Text>(redaction.redactBody("hello".encodeToByteArray(), "text/plain; charset=utf-8"))
        assertEquals(RecordedBody.Text("hello", size = 5, truncated = false), recorded)
    }

    @Test
    fun aBodyTellsWhetherAnythingWasMasked() {
        fun masked(body: String, type: String?) = assertIs<RecordedBody.Text>(redaction.redactBody(body.encodeToByteArray(), type)).masked
        assertTrue(masked("""{"user":{"password":"p"}}""", "application/json"))
        assertTrue(masked("""[{"name":"n"},{"otp":"1"}]""", "application/json"))
        assertTrue(masked("""{"password":""", "application/json"))                  // masked whole
        assertTrue(masked("user=ana&password=p", "application/x-www-form-urlencoded"))
        assertFalse(masked("""{ "user": { "name": "ana" }, "items": [1, 2] }""", "application/json"))
        assertFalse(masked("user=ana&page=2", "application/x-www-form-urlencoded"))
        assertFalse(masked("password=p", "text/plain"))                              // plain text is kept as it is
        val big = """{"password":"p","filler":"${"x".repeat(Redaction.DEFAULT_MAX_BODY_BYTES)}"}"""
        assertTrue(masked(big, "application/json"))
    }

    // Free text

    @Test
    fun freeTextHidesCredentialsPairsAndJsonValues() {
        assertEquals("Authorization: Bearer $mask, then basic $mask",redaction.redactText("Authorization: Bearer abc.def-1, then basic dXNlcjpw"))
        assertEquals(
            "GET https://api.test/me?token=$mask&page=2 failed; password=$mask",
            redaction.redactText("GET https://api.test/me?token=abc&page=2 failed; password=hunter2"),
        )
        assertEquals(
            """body {"name":"ana","otp": "$mask","pin":"$mask","refresh_token":"$mask"}""",
            redaction.redactText("""body {"name":"ana","otp": 445566,"pin":true,"refresh_token":"r\"1"}"""),
        )
        assertEquals("nothing to hide: status=500", redaction.redactText("nothing to hide: status=500"))
    }

    // Query parameters and form bodies

    @Test
    fun queryParametersAreRedacted() {
        assertEquals(
            "https://api.example.com/v1/login?user=ana&token=$mask&access_token=$mask&page=2#top",
            redaction.redactUrl("https://api.example.com/v1/login?user=ana&token=abc&access_token=def&page=2#top"),
        )
        assertEquals("https://api.example.com/v1/items", redaction.redactUrl("https://api.example.com/v1/items"))
        assertEquals("https://x.test/?flag&code=$mask", redaction.redactUrl("https://x.test/?flag&code=9"))
        assertEquals("https://x.test/?new%5Fpassword=$mask", redaction.redactUrl("https://x.test/?new%5Fpassword=p"))
        assertEquals("https://x.test/?pass%77ord=$mask", redaction.redactUrl("https://x.test/?pass%77ord=p"))   // only decoded is it "password"
        assertEquals("https://x.test/?a=1&token=$mask#top", redaction.redactUrl("https://x.test/?a=1&token=abc#top"))
    }

    @Test
    fun fragmentParametersAreRedacted() {
        assertEquals(
            "https://app.test/callback#access_token=$mask&state=s1",
            redaction.redactUrl("https://app.test/callback#access_token=abc&state=s1"),
        )
        assertEquals("https://x.test/a?b=1#c", redaction.redactUrl("https://x.test/a?b=1#c"))
    }

    @Test
    fun formBodiesAreRedacted() {
        val recorded = assertIs<RecordedBody.Text>(
            redaction.redactBody("grant_type=password&username=ana&password=p%26q&client_secret=s".encodeToByteArray(), "application/x-www-form-urlencoded"),
        )
        assertEquals("grant_type=password&username=ana&password=$mask&client_secret=$mask", recorded.text)
    }

    // Size and binary bodies

    @Test
    fun aBodyOverTheCapIsCutWithItsRealSize() {
        val big = "a".repeat(Redaction.DEFAULT_MAX_BODY_BYTES * 3)
        val recorded = assertIs<RecordedBody.Text>(redaction.redactBody(big.encodeToByteArray(), "text/plain"))
        assertTrue(recorded.truncated)
        assertEquals(Redaction.DEFAULT_MAX_BODY_BYTES, recorded.text.encodeToByteArray().size)
        assertEquals(big.length.toLong(), recorded.size)
    }

    @Test
    fun anOversizedJsonBodyIsRedactedBeforeItIsCut() {
        val filler = "x".repeat(Redaction.DEFAULT_MAX_BODY_BYTES)
        val recorded = assertIs<RecordedBody.Text>(
            redaction.redactBody("""{"password":"hunter2","filler":"$filler"}""".encodeToByteArray(), "application/json"),
        )
        assertTrue(recorded.truncated)
        assertFalse("hunter2" in recorded.text)
        assertTrue(recorded.text.startsWith("""{"password":"$mask""""))
    }

    @Test
    fun aCutNeverSplitsACharacter() {
        val text = "é".repeat(Redaction.DEFAULT_MAX_BODY_BYTES)              // 2 bytes each
        val cut = assertIs<RecordedBody.Text>((redaction + { maxBodyBytes = 5 }).redactBody(text.encodeToByteArray(), "text/plain"))
        assertEquals("éé", cut.text)
    }

    @Test
    fun aBinaryBodyKeepsOnlyItsTypeAndSize() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertEquals(RecordedBody.Binary("image/png", 8), redaction.redactBody(png, "image/png"))
        assertEquals(RecordedBody.Binary("application/octet-stream", 3), redaction.redactBody(byteArrayOf(1, 2, 3), "application/octet-stream"))
        assertEquals(
            RecordedBody.Binary("multipart/form-data", 4),
            redaction.redactBody("a=b\n".encodeToByteArray(), "multipart/form-data; boundary=x"),
        )
    }

    @Test
    fun bytesWithoutAContentTypeAreSniffed() {
        assertEquals(RecordedBody.Binary(null, 3), redaction.redactBody(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00), null))
        assertEquals(RecordedBody.Text("ok", 2, false), redaction.redactBody("ok".encodeToByteArray(), null))
        assertEquals(RecordedBody.Binary(null, 3), redaction.redactBody(byteArrayOf(0x61, 0x00, 0x62), null))   // valid UTF-8, but a NUL
    }

    @Test
    fun theCapMustBePositive() {
        assertFailsWith<IllegalArgumentException> { Redaction.Default + { maxBodyBytes = 0 } }
    }

    @Test
    fun anEmptyBodyIsNone() {
        assertEquals(RecordedBody.None, redaction.redactBody(null, "application/json"))
        assertEquals(RecordedBody.None, redaction.redactBody(ByteArray(0), "application/json"))
    }

    // Extending

    @Test
    fun extendingTheDefaultsKeepsThem() {
        val extended = Redaction.Default + {
            headers += "X-Tenant"
            fieldParts += listOf("cardNumber", "cvv")
            exactFields += "ssn"
        }
        assertEquals(listOf(mask), extended.redactHeaders(mapOf("Authorization" to listOf("x")))["Authorization"])
        assertEquals(listOf(mask), extended.redactHeaders(mapOf("x-tenant" to listOf("x")))["x-tenant"])
        assertTrue(extended.isSensitive("password"))
        assertTrue(extended.isSensitive("card_number"))
        assertTrue(extended.isSensitive("cvv2"))
        assertTrue(extended.isSensitive("SSN"))
        assertFalse(extended.isSensitive("ssnHint"))
        assertTrue(extended.headers.containsAll(Redaction.Default.headers))
        assertTrue(extended.fieldParts.containsAll(Redaction.Default.fieldParts))
        assertTrue(extended.exactFields.containsAll(Redaction.Default.exactFields))
    }

    @Test
    fun theDefaultsAreAsDesigned() {
        assertEquals(setOf("authorization", "cookie", "setcookie", "proxyauthorization"), Redaction.Default.headers)
        assertEquals(
            setOf("password", "passwd", "token", "secret", "otp", "pin", "apikey", "credential", "signature"),
            Redaction.Default.fieldParts,
        )
        assertEquals(setOf("code"), Redaction.Default.exactFields)
        assertEquals("•••", Redaction.Default.mask)
        assertEquals(64 * 1024, Redaction.Default.maxBodyBytes)
    }

    // Masking a stored secret for display

    @Test
    fun aLongSecretShowsItsLastFourCharactersAndLength() {
        val token = "a".repeat(408) + "9f2c"
        assertEquals("•••• 9f2c (412 chars)", Redaction.maskSecret(token))
        assertEquals("•••• wxyz (16 chars)", Redaction.maskSecret("abcdefghijklwxyz"))
    }

    @Test
    fun aShortSecretShowsOnlyItsLength() {
        assertEquals("•••• (6 chars)", Redaction.maskSecret("123456"))
        assertEquals("•••• (15 chars)", Redaction.maskSecret("a".repeat(15)))
        assertEquals("•••• (1 char)", Redaction.maskSecret("x"))
        assertEquals("•••• (0 chars)", Redaction.maskSecret(""))
    }
}
