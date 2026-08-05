/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinKoreanHardcodingGuardTest {
    @Test
    fun `production Kotlin does not add Korean hardcoded strings`() {
        val sourceRoot = findMainKotlinSourceRoot()
        val actualCounts = linkedMapOf<String, Int>()

        Files.walk(sourceRoot).use { files ->
            files.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .sorted()
                .forEach { file ->
                    val source = Files.newBufferedReader(file, StandardCharsets.UTF_8).use {
                        it.readText()
                    }
                    val count = kotlinStringLiterals(source).count(::containsHangul)
                    if (count > 0) {
                        actualCounts[sourceRoot.relativize(file).invariantSeparators()] = count
                    }
                }
        }

        val unexpectedFiles = actualCounts.keys - legacyKoreanLiteralUpperBounds.keys
        assertTrue(
            "Move Korean user-facing copy to values/strings.xml and values-ko/strings.xml. " +
                "Unexpected files: ${unexpectedFiles.sorted().joinToString()}",
            unexpectedFiles.isEmpty(),
        )

        legacyKoreanLiteralUpperBounds.forEach { (file, upperBound) ->
            val actual = actualCounts[file] ?: 0
            assertTrue(
                "$file added Korean string literals ($actual > legacy ceiling $upperBound). " +
                    "Use an English default resource and a Korean translation instead.",
                actual <= upperBound,
            )
        }
    }

    private fun findMainKotlinSourceRoot(): Path {
        val start = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        generateSequence(start) { it.parent }.forEach { directory ->
            listOf(
                directory.resolve("app/src/main/java"),
                directory.resolve("src/main/java"),
            ).firstOrNull { Files.isDirectory(it) }?.let { return it }
        }
        error("Could not locate app/src/main/java from $start")
    }

    /**
     * Extracts ordinary and triple-quoted Kotlin strings while ignoring comments and character
     * literals. It is intentionally a small source guard, not a replacement for the Kotlin parser.
     */
    private fun kotlinStringLiterals(source: String): List<String> {
        val literals = mutableListOf<String>()
        var index = 0
        while (index < source.length) {
            when {
                source.startsWith("//", index) -> {
                    index = source.indexOf('\n', index + 2).takeIf { it >= 0 } ?: source.length
                }

                source.startsWith("/*", index) -> {
                    index = skipNestedBlockComment(source, index + 2)
                }

                source.startsWith("\"\"\"", index) -> {
                    val contentStart = index + 3
                    val end = source.indexOf("\"\"\"", contentStart)
                    if (end < 0) {
                        literals += source.substring(contentStart)
                        index = source.length
                    } else {
                        literals += source.substring(contentStart, end)
                        index = end + 3
                    }
                }

                source[index] == '"' -> {
                    val extracted = readOrdinaryString(source, index + 1)
                    literals += extracted.text
                    index = extracted.nextIndex
                }

                source[index] == '\'' -> index = skipCharacterLiteral(source, index + 1)
                else -> index += 1
            }
        }
        return literals
    }

    private fun skipNestedBlockComment(source: String, contentStart: Int): Int {
        var depth = 1
        var index = contentStart
        while (index < source.length && depth > 0) {
            when {
                source.startsWith("/*", index) -> {
                    depth += 1
                    index += 2
                }
                source.startsWith("*/", index) -> {
                    depth -= 1
                    index += 2
                }
                else -> index += 1
            }
        }
        return index
    }

    private fun readOrdinaryString(source: String, contentStart: Int): ExtractedString {
        val value = StringBuilder()
        var index = contentStart
        while (index < source.length) {
            when (val character = source[index]) {
                '\\' -> {
                    value.append(character)
                    if (index + 1 < source.length) {
                        value.append(source[index + 1])
                        index += 2
                    } else {
                        index += 1
                    }
                }
                '"' -> return ExtractedString(value.toString(), index + 1)
                else -> {
                    value.append(character)
                    index += 1
                }
            }
        }
        return ExtractedString(value.toString(), source.length)
    }

    private fun skipCharacterLiteral(source: String, contentStart: Int): Int {
        var index = contentStart
        while (index < source.length) {
            when (source[index]) {
                '\\' -> index = (index + 2).coerceAtMost(source.length)
                '\'' -> return index + 1
                else -> index += 1
            }
        }
        return source.length
    }

    private fun containsHangul(value: String): Boolean = value.any {
        it in '\uAC00'..'\uD7A3' || it in '\u1100'..'\u11FF' || it in '\u3130'..'\u318F'
    }

    private fun Path.invariantSeparators(): String = toString().replace('\\', '/')

    private data class ExtractedString(val text: String, val nextIndex: Int)

    private companion object {
        /**
         * Existing localization debt is allowed only to shrink. New files and increases fail.
         * Remove entries as their status/error values move behind resource-backed UI boundaries.
         */
        val legacyKoreanLiteralUpperBounds = mapOf(
            "com/pebble/tecomheadunit/core/OpenAutoCoordinator.kt" to 1,
            "com/pebble/tecomheadunit/core/SafetyGate.kt" to 6,
            "com/pebble/tecomheadunit/diagnostics/AppDiagnostics.kt" to 1,
            "com/pebble/tecomheadunit/diagnostics/upload/DiagnosticUploadConsentGate.kt" to 1,
            "com/pebble/tecomheadunit/openauto/BenchOpenAutoEngine.kt" to 3,
            "com/pebble/tecomheadunit/openauto/credential/HeadUnitCredential.kt" to 20,
            "com/pebble/tecomheadunit/session/BrowserTrustStore.kt" to 1,
        )
    }
}
