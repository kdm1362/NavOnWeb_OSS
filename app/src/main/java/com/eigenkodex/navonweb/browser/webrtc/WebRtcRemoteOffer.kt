/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser.webrtc

/** Pure SDP routing helpers used before binding a native sender to an offered m-section. */
internal object WebRtcRemoteOffer {
    /**
     * Returns, in SDP order, non-rejected video MIDs on which the remote endpoint
     * permits us to send. A remote recvonly/sendrecv direction can therefore be
     * answered locally as sendonly.
     */
    fun receivingVideoMids(sdp: String): List<String> {
        val lines = sdp.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val firstMedia = lines.indexOfFirst { it.startsWith("m=") }.let { if (it < 0) lines.size else it }
        val sessionDirection = lines.subList(0, firstMedia)
            .firstOrNull { it in DIRECTION_LINES }
            ?: SEND_RECV
        val mids = mutableListOf<String>()

        var index = firstMedia
        while (index < lines.size) {
            val nextMediaOffset = lines.drop(index + 1).indexOfFirst { it.startsWith("m=") }
            val end = if (nextMediaOffset >= 0) index + 1 + nextMediaOffset else lines.size
            val section = lines.subList(index, end)
            val mediaFields = section.first().split(Regex("\\s+"))
            val isVideo = mediaFields.firstOrNull() == "m=video"
            val isRejected = mediaFields.getOrNull(1) == "0"
            if (isVideo && !isRejected) {
                val direction = section.firstOrNull { it in DIRECTION_LINES } ?: sessionDirection
                val mid = section.firstOrNull { it.startsWith(MID_PREFIX) }
                    ?.removePrefix(MID_PREFIX)
                    ?.takeIf(String::isNotBlank)
                if (direction in REMOTE_RECEIVING_DIRECTIONS && mid != null && mid !in mids) {
                    mids += mid
                }
            }
            index = end
        }
        return mids
    }

    private const val MID_PREFIX = "a=mid:"
    private const val SEND_RECV = "a=sendrecv"
    private val DIRECTION_LINES = setOf(
        SEND_RECV,
        "a=sendonly",
        "a=recvonly",
        "a=inactive",
    )
    private val REMOTE_RECEIVING_DIRECTIONS = setOf(SEND_RECV, "a=recvonly")
}
