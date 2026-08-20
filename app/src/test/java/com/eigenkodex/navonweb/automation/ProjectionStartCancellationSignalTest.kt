/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionStartCancellationSignalTest {
    @Test
    fun `cancellation invalidates an older delayed start but permits a later request`() {
        val oldRequest = ProjectionStartCancellationSignal.snapshot()

        ProjectionStartCancellationSignal.cancelPendingStarts()
        val newRequest = ProjectionStartCancellationSignal.snapshot()

        assertFalse(ProjectionStartCancellationSignal.runIfCurrent(oldRequest) {})
        assertTrue(ProjectionStartCancellationSignal.runIfCurrent(newRequest) {})
    }
}
