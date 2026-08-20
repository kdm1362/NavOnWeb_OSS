/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class PreviewRendererBindingTest {
    @Test
    fun `same target and generation creates one renderer`() {
        val events = mutableListOf<String>()
        val binding = binding(events)

        assertEquals(
            PreviewRendererAttachResult.ATTACHED,
            binding.attach(Target("A"), 1L) { renderer("r1", events) },
        )
        assertEquals(
            PreviewRendererAttachResult.ALREADY_ATTACHED,
            binding.attach(Target("A"), 1L) { renderer("duplicate", events) },
        )

        assertEquals(listOf("create:r1"), events)
    }

    @Test
    fun `replacement releases old producer before creating new one`() {
        val events = mutableListOf<String>()
        val binding = binding(events)
        binding.attach(Target("A"), 1L) { renderer("r1", events) }

        binding.attach(Target("B"), 2L) { renderer("r2", events) }

        assertEquals(listOf("create:r1", "release:r1", "create:r2"), events)
    }

    @Test
    fun `detach preserves same-generation target tombstone`() {
        val events = mutableListOf<String>()
        val binding = binding(events)
        binding.attach(Target("A"), 1L) { renderer("r1", events) }
        binding.detach(1L)

        assertThrows(IllegalStateException::class.java) {
            binding.attach(Target("B"), 1L) { renderer("invalid", events) }
        }
        binding.attach(Target("A"), 1L) { renderer("r2", events) }

        assertEquals(listOf("create:r1", "release:r1", "create:r2"), events)
    }

    @Test
    fun `partial initialization failure releases candidate and fences stale generation`() {
        val events = mutableListOf<String>()
        val binding = binding(events)
        binding.attach(Target("A"), 1L) { renderer("r1", events) }

        assertThrows(IllegalArgumentException::class.java) {
            binding.attach(Target("B"), 2L) {
                createInitializedPreviewRenderer(
                    allocate = { renderer("partial", events) },
                    initialize = { throw IllegalArgumentException("EGL_BAD_ALLOC") },
                    release = { candidate -> events += "release:${candidate.id}" },
                )
            }
        }
        assertEquals(
            PreviewRendererAttachResult.STALE_IGNORED,
            binding.attach(Target("A"), 1L) { renderer("stale", events) },
        )
        assertThrows(IllegalStateException::class.java) {
            binding.attach(Target("C"), 2L) { renderer("substitute", events) }
        }
        binding.attach(Target("B"), 2L) { renderer("retry", events) }

        assertEquals(
            listOf(
                "create:r1",
                "release:r1",
                "create:partial",
                "release:partial",
                "create:retry",
            ),
            events,
        )
    }

    @Test
    fun `owner switch unconditionally releases inactive producer before attach`() {
        val events = mutableListOf<String>()
        val firstOwner = binding(events)
        val secondOwner = binding(events)
        firstOwner.attach(Target("surface"), 1L) { renderer("first", events) }

        firstOwner.detach()
        secondOwner.attach(Target("surface"), 2L) { renderer("second", events) }
        secondOwner.detach()
        firstOwner.attach(Target("surface"), 2L) { renderer("first-again", events) }

        assertEquals(
            listOf(
                "create:first",
                "release:first",
                "create:second",
                "release:second",
                "create:first-again",
            ),
            events,
        )
    }

    @Test
    fun `stale detach keeps current renderer dispatchable`() {
        val events = mutableListOf<String>()
        val binding = binding(events)
        binding.attach(Target("A"), 2L) { renderer("current", events) }

        assertFalse(binding.detach(1L))
        binding.dispatch { renderer -> events += "dispatch:${renderer.id}" }

        assertEquals(listOf("create:current", "dispatch:current"), events)
    }

    @Test
    fun `close is idempotent and attach after close allocates no candidate`() {
        val events = mutableListOf<String>()
        val binding = binding(events)
        binding.attach(Target("A"), 1L) { renderer("current", events) }

        binding.close()
        binding.close()
        assertThrows(IllegalStateException::class.java) {
            binding.attach(Target("B"), 2L) { renderer("leak", events) }
        }

        assertEquals(listOf("create:current", "release:current"), events)
    }

    private fun binding(events: MutableList<String>) =
        PreviewRendererBinding<Target, Renderer>(
            sameTarget = { first, second -> first.id == second.id },
            releaseRenderer = { renderer -> events += "release:${renderer.id}" },
        )

    private fun renderer(id: String, events: MutableList<String>): Renderer =
        Renderer(id).also { events += "create:$id" }

    private data class Target(val id: String)

    private data class Renderer(val id: String)
}
