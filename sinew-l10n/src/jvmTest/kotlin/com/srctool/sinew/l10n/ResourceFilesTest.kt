package com.srctool.sinew.l10n

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

// Checks on the resource files themselves. JVM only: it reads the sources from the module directory.
class ResourceFilesTest {
    private val resources = File("src/commonMain/composeResources")

    private fun read(folder: String) = File(resources, "$folder/strings.xml").readText()

    /** Every resource name with the format placeholders its texts use, plural items merged. */
    private fun placeholders(xml: String): Map<String, Set<String>> {
        val entries = Regex("""<(string|plurals) name="([^"]+)">(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL).findAll(xml)
        return entries.associate { m -> m.groupValues[2] to Regex("""%\d+\$[a-z]""").findAll(m.groupValues[3]).map { it.value }.toSet() }
    }

    @Test
    fun indonesianShipsIdenticallyUnderInAndId() {
        assertEquals(read("values-in"), read("values-id"))
    }

    @Test
    fun bothLanguagesDefineEveryKeyWithTheSameArguments() {
        assertEquals(placeholders(read("values")), placeholders(read("values-in")))
    }
}
