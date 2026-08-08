/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit

import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node

class AndroidStringResourceParityTest {
    @Test
    fun `Korean resources cover every default string and plurals resource`() {
        val defaultResources = readTextResources(findResourceDirectory("values"))
        val koreanResources = readTextResources(findResourceDirectory("values-ko"))

        assertTrue(
            "Default resources contain duplicate string or plurals names: " +
                defaultResources.duplicates.sorted().joinToString(),
            defaultResources.duplicates.isEmpty(),
        )
        assertTrue(
            "Korean resources contain duplicate string or plurals names: " +
                koreanResources.duplicates.sorted().joinToString(),
            koreanResources.duplicates.isEmpty(),
        )

        val missingInKorean = defaultResources.entries.keys - koreanResources.entries.keys
        val extraInKorean = koreanResources.entries.keys - defaultResources.entries.keys
        assertTrue(
            "Korean resources are missing: ${missingInKorean.sorted().joinToString()}",
            missingInKorean.isEmpty(),
        )
        assertTrue(
            "Korean resources have no default counterpart: ${extraInKorean.sorted().joinToString()}",
            extraInKorean.isEmpty(),
        )

        defaultResources.entries.toSortedMap().forEach { (name, defaultEntry) ->
            val koreanEntry = koreanResources.entries.getValue(name)
            assertEquals(
                "Resource kind differs for $name",
                defaultEntry.javaClass,
                koreanEntry.javaClass,
            )
            when (defaultEntry) {
                is TextResource.StringValue -> assertEquals(
                    "printf placeholders differ for string/$name",
                    printfPlaceholders(defaultEntry.text),
                    printfPlaceholders((koreanEntry as TextResource.StringValue).text),
                )

                is TextResource.PluralsValue -> comparePluralPlaceholders(
                    name = name,
                    defaultValue = defaultEntry,
                    koreanValue = koreanEntry as TextResource.PluralsValue,
                )
            }
        }
    }

    @Test
    fun `hotspot onboarding explains generic viewing device and local video data use`() {
        val defaultResources = readTextResources(findResourceDirectory("values"))
        val koreanResources = readTextResources(findResourceDirectory("values-ko"))
        val names = listOf(
            "first_run_hotspot_title",
            "first_run_hotspot_body",
            "first_run_hotspot_warning",
        )
        val englishCopy = names.joinToString(" ") { name ->
            (defaultResources.entries.getValue(name) as TextResource.StringValue).text
        }
        val koreanCopy = names.joinToString(" ") { name ->
            (koreanResources.entries.getValue(name) as TextResource.StringValue).text
        }

        assertFalse(englishCopy.contains("Tesla", ignoreCase = true))
        assertFalse(koreanCopy.contains("Tesla", ignoreCase = true))
        assertTrue(koreanCopy.contains("화면을 볼 장치의 Wi-Fi 메뉴"))
        assertTrue(koreanCopy.contains("영상 전송 자체는 휴대전화의 모바일 데이터를 사용하지 않습니다"))
        assertTrue(englishCopy.contains("video stream itself does not use the phone’s mobile data"))
    }

    @Test
    fun `projection profile descriptions show bitrate without frame rates`() {
        listOf("values", "values-ko").forEach { qualifier ->
            val resources = readTextResources(findResourceDirectory(qualifier))
            val description = (
                resources.entries.getValue("projection_profile_detail") as TextResource.StringValue
                ).text

            assertFalse(description.contains("FPS", ignoreCase = true))
            assertFalse(description.contains("Android Auto", ignoreCase = true))
            assertFalse(description.contains("WebRTC", ignoreCase = true))
            assertEquals(mapOf("1:s" to 1, "2:s" to 1), printfPlaceholders(description))
        }
    }

    @Test
    fun `visible copy uses paragraph breaks instead of manual line wrapping`() {
        listOf("values", "values-ko").forEach { qualifier ->
            val resources = readTextResources(findResourceDirectory(qualifier))
            resources.entries.forEach { (name, entry) ->
                val values = when (entry) {
                    is TextResource.StringValue -> listOf(entry.text)
                    is TextResource.PluralsValue -> entry.quantities.values
                }
                values.forEach { text ->
                    val withoutParagraphBreaks = text
                        .replace("\\n\\n", "")
                        .replace("\r\n\r\n", "")
                        .replace("\n\n", "")
                    assertFalse(
                        "$qualifier/$name contains a single forced line break",
                        withoutParagraphBreaks.contains("\\n") ||
                            withoutParagraphBreaks.contains('\r') ||
                            withoutParagraphBreaks.contains('\n'),
                    )
                }
            }
        }
    }

    private fun comparePluralPlaceholders(
        name: String,
        defaultValue: TextResource.PluralsValue,
        koreanValue: TextResource.PluralsValue,
    ) {
        assertTrue("Default plurals/$name must define other", "other" in defaultValue.quantities)
        assertTrue("Korean plurals/$name must define other", "other" in koreanValue.quantities)

        // Plural categories legitimately differ by locale. Compare a matching category when the
        // default has one, otherwise compare the translated category with the default `other`.
        koreanValue.quantities.toSortedMap().forEach { (quantity, translatedText) ->
            val defaultText = defaultValue.quantities[quantity]
                ?: defaultValue.quantities.getValue("other")
            assertEquals(
                "printf placeholders differ for plurals/$name[$quantity]",
                printfPlaceholders(defaultText),
                printfPlaceholders(translatedText),
            )
        }
    }

    private fun findResourceDirectory(qualifier: String): Path {
        val start = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        generateSequence(start) { it.parent }.forEach { directory ->
            listOf(
                directory.resolve("app/src/main/res/$qualifier"),
                directory.resolve("src/main/res/$qualifier"),
            ).firstOrNull { Files.isDirectory(it) }?.let { return it }
        }
        error("Could not locate Android resource directory $qualifier from $start")
    }

    private fun readTextResources(directory: Path): ParsedResources {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
            isExpandEntityReferences = false
            isXIncludeAware = false
        }
        val entries = linkedMapOf<String, TextResource>()
        val duplicates = linkedSetOf<String>()

        Files.list(directory).use { files ->
            files.filter { it.fileName.toString().endsWith(".xml") }
                .sorted()
                .forEach { file ->
                    val document = Files.newInputStream(file).use { input ->
                        factory.newDocumentBuilder().parse(input)
                    }
                    val children = document.documentElement.childNodes
                    for (index in 0 until children.length) {
                        val element = children.item(index) as? Element ?: continue
                        val entry = when (element.tagName) {
                            "string" -> TextResource.StringValue(element.textContent)
                            "plurals" -> TextResource.PluralsValue(readPluralItems(element, file))
                            else -> continue
                        }
                        val name = element.getAttribute("name").trim()
                        assertTrue("Unnamed ${element.tagName} in $file", name.isNotEmpty())
                        if (entries.put(name, entry) != null) duplicates += name
                    }
                }
        }
        return ParsedResources(entries = entries, duplicates = duplicates)
    }

    private fun readPluralItems(element: Element, file: Path): Map<String, String> {
        val quantities = linkedMapOf<String, String>()
        val children = element.childNodes
        for (index in 0 until children.length) {
            val item = children.item(index)
            if (item.nodeType != Node.ELEMENT_NODE || item.nodeName != "item") continue
            val itemElement = item as Element
            val quantity = itemElement.getAttribute("quantity").trim()
            assertTrue("Unnamed plurals item in $file", quantity.isNotEmpty())
            assertTrue(
                "Duplicate plurals quantity $quantity in $file",
                quantities.put(quantity, itemElement.textContent) == null,
            )
        }
        return quantities
    }

    private fun printfPlaceholders(text: String): Map<String, Int> {
        var nextOrdinaryArgument = 1
        var previousArgument: Int? = null
        val placeholders = linkedMapOf<String, Int>()

        printfPattern.findAll(text).forEach { match ->
            val conversion = match.groupValues[6]
            if (conversion == "%" || conversion == "n") return@forEach

            val flags = match.groupValues[2]
            val explicitArgument = match.groupValues[1].toIntOrNull()
            val argument = when {
                explicitArgument != null -> explicitArgument
                '<' in flags -> requireNotNull(previousArgument) {
                    "Relative printf placeholder has no previous argument in: $text"
                }
                else -> nextOrdinaryArgument++
            }
            previousArgument = argument
            val dateTimePrefix = match.groupValues[5]
            val token = "$argument:$dateTimePrefix$conversion"
            placeholders[token] = placeholders.getOrDefault(token, 0) + 1
        }
        return placeholders.toSortedMap()
    }

    private data class ParsedResources(
        val entries: Map<String, TextResource>,
        val duplicates: Set<String>,
    )

    private sealed interface TextResource {
        data class StringValue(val text: String) : TextResource

        data class PluralsValue(val quantities: Map<String, String>) : TextResource
    }

    private companion object {
        val printfPattern = Regex(
            "%(?:(\\d+)\\${'$'})?([-#+ 0,(<]*)(\\d+)?(?:\\.(\\d+))?([tT])?([a-zA-Z%])",
        )
    }
}
