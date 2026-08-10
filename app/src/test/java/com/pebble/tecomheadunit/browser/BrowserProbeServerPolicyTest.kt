package com.pebble.tecomheadunit.browser

import com.pebble.tecomheadunit.browser.audio.BrowserAudioTrack
import com.pebble.tecomheadunit.core.TouchPhase
import com.pebble.tecomheadunit.session.AndroidAutoConnectionState
import com.pebble.tecomheadunit.session.AndroidAutoConnectionStatus
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserProbeServerPolicyTest {
    @Test
    fun revokedCredentialCannotResurrectWhenPairingCommitsFirst() {
        val admissionLock = Any()
        val trustedCredential = AtomicReference("old")
        val activeOwner = AtomicReference<String?>(null)
        val initialAuthorizationPassed = CountDownLatch(1)
        val allowFinalCommit = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val staleOpen = executor.submit<BrowserCredentialAdmissionResult<String>> {
                assertEquals("old", trustedCredential.get())
                initialAuthorizationPassed.countDown()
                assertTrue(allowFinalCommit.await(2, TimeUnit.SECONDS))
                commitAuthorizedBrowserOperation(
                    lock = admissionLock,
                    credentialStillValid = { trustedCredential.get() == "old" },
                    operation = {
                        activeOwner.set("old")
                        "old"
                    },
                )
            }

            assertTrue(initialAuthorizationPassed.await(2, TimeUnit.SECONDS))
            synchronized(admissionLock) {
                trustedCredential.set("new")
                activeOwner.set(null)
            }
            allowFinalCommit.countDown()

            assertEquals(BrowserCredentialAdmissionResult.Unauthorized, staleOpen.get(2, TimeUnit.SECONDS))
            assertNull(activeOwner.get())
            assertEquals(
                BrowserCredentialAdmissionResult.Authorized("new"),
                commitAuthorizedBrowserOperation(
                    lock = admissionLock,
                    credentialStillValid = { trustedCredential.get() == "new" },
                    operation = {
                        activeOwner.set("new")
                        "new"
                    },
                ),
            )
            assertEquals("new", activeOwner.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun pairingAddsCredentialWithoutRevokingAnExistingOwner() {
        val trustedCredentials = linkedSetOf("old")
        val activeOwners = linkedSetOf("old")

        trustedCredentials += "new"

        assertEquals(setOf("old", "new"), trustedCredentials)
        assertEquals(setOf("old"), activeOwners)
        assertTrue("old" in trustedCredentials)
    }

    @Test
    fun openFirstThenAccessDowngradeOrDeleteCannotLeaveAStaleControlSession() {
        listOf("READ_ONLY", "DELETE").forEach { mutation ->
            val lock = Any()
            val credentialPresent = AtomicReference(true)
            val permission = AtomicReference("CONTROL")
            val activePermission = AtomicReference<String?>(null)
            val openEntered = CountDownLatch(1)
            val releaseOpen = CountDownLatch(1)
            val mutationStarted = CountDownLatch(1)
            val mutationFinished = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val open = executor.submit<BrowserCredentialAdmissionResult<String>> {
                    commitAuthenticatedBrowserOperation(
                        lock = lock,
                        authenticate = {
                            permission.get().takeIf { credentialPresent.get() }
                        },
                        operation = { freshPermission ->
                            openEntered.countDown()
                            assertTrue(releaseOpen.await(2, TimeUnit.SECONDS))
                            activePermission.set(freshPermission)
                            freshPermission
                        },
                    )
                }
                assertTrue(openEntered.await(2, TimeUnit.SECONDS))
                val mutate = executor.submit {
                    mutationStarted.countDown()
                    synchronized(lock) {
                        if (mutation == "DELETE") credentialPresent.set(false)
                        else permission.set("READ_ONLY")
                        activePermission.set(null)
                    }
                    mutationFinished.countDown()
                }
                assertTrue(mutationStarted.await(2, TimeUnit.SECONDS))
                assertFalse(mutationFinished.await(100, TimeUnit.MILLISECONDS))
                releaseOpen.countDown()

                assertEquals(
                    BrowserCredentialAdmissionResult.Authorized("CONTROL"),
                    open.get(2, TimeUnit.SECONDS),
                )
                mutate.get(2, TimeUnit.SECONDS)
                assertNull(activePermission.get())
            } finally {
                releaseOpen.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun accessDowngradeOrDeleteFirstControlsTheFreshOpenSnapshot() {
        listOf("READ_ONLY", "DELETE").forEach { mutation ->
            val lock = Any()
            val credentialPresent = AtomicReference(true)
            val permission = AtomicReference("CONTROL")
            val mutationEntered = CountDownLatch(1)
            val releaseMutation = CountDownLatch(1)
            val openFinished = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val mutate = executor.submit {
                    synchronized(lock) {
                        if (mutation == "DELETE") credentialPresent.set(false)
                        else permission.set("READ_ONLY")
                        mutationEntered.countDown()
                        assertTrue(releaseMutation.await(2, TimeUnit.SECONDS))
                    }
                }
                assertTrue(mutationEntered.await(2, TimeUnit.SECONDS))
                val open = executor.submit<BrowserCredentialAdmissionResult<String>> {
                    commitAuthenticatedBrowserOperation(
                        lock = lock,
                        authenticate = {
                            permission.get().takeIf { credentialPresent.get() }
                        },
                        operation = { it },
                    ).also { openFinished.countDown() }
                }
                assertFalse(openFinished.await(100, TimeUnit.MILLISECONDS))
                releaseMutation.countDown()
                mutate.get(2, TimeUnit.SECONDS)

                val expected = if (mutation == "DELETE") {
                    BrowserCredentialAdmissionResult.Unauthorized
                } else {
                    BrowserCredentialAdmissionResult.Authorized("READ_ONLY")
                }
                assertEquals(expected, open.get(2, TimeUnit.SECONDS))
            } finally {
                releaseMutation.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun downgradeOrDeleteCommittedFirstPreventsOneMoreInputSideEffect() {
        listOf("READ_ONLY", "DELETE").forEach { mutation ->
            val lock = Any()
            val credentialPresent = AtomicReference(true)
            val permission = AtomicReference("CONTROL")
            val mutationEntered = CountDownLatch(1)
            val releaseMutation = CountDownLatch(1)
            val sideEffects = AtomicReference(0)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val mutate = executor.submit {
                    synchronized(lock) {
                        if (mutation == "DELETE") credentialPresent.set(false)
                        else permission.set("READ_ONLY")
                        mutationEntered.countDown()
                        assertTrue(releaseMutation.await(2, TimeUnit.SECONDS))
                    }
                }
                assertTrue(mutationEntered.await(2, TimeUnit.SECONDS))
                val input = executor.submit<BrowserCredentialAdmissionResult<Boolean>> {
                    commitAuthenticatedBrowserOperation(
                        lock = lock,
                        authenticate = {
                            permission.get().takeIf { credentialPresent.get() }
                        },
                        operation = { freshPermission ->
                            (freshPermission == "CONTROL").also { allowed ->
                                if (allowed) sideEffects.set(sideEffects.get() + 1)
                            }
                        },
                    )
                }
                releaseMutation.countDown()
                mutate.get(2, TimeUnit.SECONDS)
                val result = input.get(2, TimeUnit.SECONDS)

                if (mutation == "DELETE") {
                    assertEquals(BrowserCredentialAdmissionResult.Unauthorized, result)
                } else {
                    assertEquals(BrowserCredentialAdmissionResult.Authorized(false), result)
                }
                assertEquals(0, sideEffects.get())
            } finally {
                releaseMutation.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun parsesOnlyNonNegativeDecimalFrameVersions() {
        assertEquals(0L, BrowserProbeServer.parseFrameVersion(null))
        assertEquals(0L, BrowserProbeServer.parseFrameVersion("0"))
        assertEquals(Long.MAX_VALUE, BrowserProbeServer.parseFrameVersion(Long.MAX_VALUE.toString()))

        assertNull(BrowserProbeServer.parseFrameVersion(""))
        assertNull(BrowserProbeServer.parseFrameVersion("-1"))
        assertNull(BrowserProbeServer.parseFrameVersion("+1"))
        assertNull(BrowserProbeServer.parseFrameVersion("9223372036854775808"))
        assertNull(BrowserProbeServer.parseFrameVersion("12345678901234567890"))
    }

    @Test
    fun projectionViewportDimensionsAreStrictPositiveBoundedDecimals() {
        assertEquals(1, BrowserProbeServer.parseViewportDimension("1"))
        assertEquals(16_384, BrowserProbeServer.parseViewportDimension("16384"))
        assertNull(BrowserProbeServer.parseViewportDimension(null))
        assertNull(BrowserProbeServer.parseViewportDimension(""))
        assertNull(BrowserProbeServer.parseViewportDimension("0"))
        assertNull(BrowserProbeServer.parseViewportDimension("-1"))
        assertNull(BrowserProbeServer.parseViewportDimension("1.5"))
        assertNull(BrowserProbeServer.parseViewportDimension("16385"))
    }

    @Test
    fun projectionViewportDevicePixelRatioIsFiniteAndBounded() {
        assertEquals(0.5, BrowserProbeServer.parseViewportDevicePixelRatio("0.5")!!, 0.0)
        assertEquals(1.25, BrowserProbeServer.parseViewportDevicePixelRatio("1.25")!!, 0.0)
        assertEquals(8.0, BrowserProbeServer.parseViewportDevicePixelRatio("8")!!, 0.0)

        assertNull(BrowserProbeServer.parseViewportDevicePixelRatio(null))
        assertNull(BrowserProbeServer.parseViewportDevicePixelRatio(""))
        assertNull(BrowserProbeServer.parseViewportDevicePixelRatio("0.49"))
        assertNull(BrowserProbeServer.parseViewportDevicePixelRatio("8.01"))
        assertNull(BrowserProbeServer.parseViewportDevicePixelRatio("NaN"))
        assertNull(BrowserProbeServer.parseViewportDevicePixelRatio("Infinity"))
        assertNull(BrowserProbeServer.parseViewportDevicePixelRatio("1.0000000000000000"))
    }

    @Test
    fun viewportClientIdsAreBoundedUrlSafeValues() {
        assertTrue(BrowserProbeServer.isValidViewportClientId("0123456789abcdef"))
        assertTrue(BrowserProbeServer.isValidViewportClientId("page_A-0123456789"))
        assertFalse(BrowserProbeServer.isValidViewportClientId("too-short"))
        assertFalse(BrowserProbeServer.isValidViewportClientId("0123456789abcde!"))
        assertFalse(BrowserProbeServer.isValidViewportClientId("a".repeat(65)))
    }

    @Test
    fun servesCurrentFrameOnlyWhenItIsNewerThanRequestedVersion() {
        assertTrue(BrowserProbeServer.shouldServeFrameImmediately(8L, 7L))
        assertFalse(BrowserProbeServer.shouldServeFrameImmediately(8L, 8L))
        assertFalse(BrowserProbeServer.shouldServeFrameImmediately(8L, 9L))
        assertFalse(BrowserProbeServer.shouldServeFrameImmediately(null, 0L))
    }

    @Test
    fun browserTouchRequiresNormalizedFiniteCoordinatesAndPointerZero() {
        assertTrue(
            BrowserProbeServer.isValidBrowserTouch(TouchPhase.DOWN, 0.0, 1.0, 0),
        )
        assertFalse(
            BrowserProbeServer.isValidBrowserTouch(TouchPhase.MOVE, -0.01, 0.5, 0),
        )
        assertFalse(
            BrowserProbeServer.isValidBrowserTouch(TouchPhase.MOVE, 0.5, 1.01, 0),
        )
        assertFalse(
            BrowserProbeServer.isValidBrowserTouch(TouchPhase.UP, Double.NaN, 0.5, 0),
        )
        assertFalse(
            BrowserProbeServer.isValidBrowserTouch(TouchPhase.UP, 0.5, 0.5, 1),
        )
        assertFalse(
            BrowserProbeServer.isValidBrowserTouch(TouchPhase.UP, 0.5, 0.5, null),
        )
    }

    @Test
    fun browserTouchCoordinateContractIsContentRelativeOnly() {
        assertTrue(BrowserProbeServer.isContentTouchCoordinateSpace(null))
        assertTrue(BrowserProbeServer.isContentTouchCoordinateSpace("content"))
        assertTrue(BrowserProbeServer.isContentTouchCoordinateSpace("CONTENT"))
        assertFalse(BrowserProbeServer.isContentTouchCoordinateSpace("encoded"))
        assertFalse(BrowserProbeServer.isContentTouchCoordinateSpace(""))
    }

    @Test
    fun browserTouchIsEnabledOnlyWhileAndroidAutoIsConnected() {
        assertTrue(
            BrowserProbeServer.isTouchAllowed(
                AndroidAutoConnectionStatus(state = AndroidAutoConnectionState.CONNECTED),
                touchInputReady = true,
            ),
        )
        assertFalse(
            BrowserProbeServer.isTouchAllowed(
                AndroidAutoConnectionStatus(state = AndroidAutoConnectionState.RECONNECTING),
            ),
        )
        assertFalse(BrowserProbeServer.isTouchAllowed(AndroidAutoConnectionStatus()))
        assertFalse(
            BrowserProbeServer.isTouchAllowed(
                AndroidAutoConnectionStatus(state = AndroidAutoConnectionState.CONNECTED),
                touchInputReady = false,
            ),
        )
    }

    @Test
    fun contentPolicyAllowsOnlySameOriginBlobFrames() {
        val policy = BrowserProbeServer.CONTENT_SECURITY_POLICY

        assertTrue(policy.contains("img-src 'self' blob:"))
        assertTrue(policy.contains("media-src 'self' blob:"))
        assertTrue(policy.contains("connect-src 'self'"))
        assertTrue(policy.contains("object-src 'none'"))
        assertTrue(policy.contains("frame-ancestors 'none'"))
        assertFalse(policy.contains("https:"))
        assertFalse(policy.contains("data:"))
    }

    @Test
    fun audioStreamingPathsAreClosedAndTrackSpecific() {
        assertEquals(BrowserAudioTrack.MEDIA, BrowserProbeServer.audioStreamTrack("/api/audio/media"))
        assertEquals(BrowserAudioTrack.SPEECH, BrowserProbeServer.audioStreamTrack("/api/audio/speech"))
        assertEquals(BrowserAudioTrack.SYSTEM, BrowserProbeServer.audioStreamTrack("/api/audio/system"))
        assertNull(BrowserProbeServer.audioStreamTrack("/api/audio/microphone"))
        assertNull(BrowserProbeServer.audioStreamTrack("/api/audio/media/extra"))
    }

    @Test
    fun microphoneUploadRequiresBoundedAlignedPcm16Base64() {
        val pcm = byteArrayOf(0x34, 0x12, 0x78, 0x56)
        val headers = mapOf(
            "content-type" to "text/plain; charset=UTF-8",
            "x-audio-codec" to "pcm-s16le",
            "x-audio-sample-rate" to "48000",
            "x-audio-channels" to "1",
        )
        val upload = BrowserProbeServer.decodeMicrophoneUpload(
            headers = headers,
            body = Base64.getEncoder().encode(pcm),
        )

        requireNotNull(upload)
        assertEquals(48_000, upload.format.sampleRateHz)
        assertEquals(1, upload.format.channelCount)
        assertArrayEquals(pcm, upload.pcm16LittleEndian)

        assertNull(
            BrowserProbeServer.decodeMicrophoneUpload(
                headers = headers + ("x-audio-codec" to "opus"),
                body = Base64.getEncoder().encode(pcm),
            ),
        )
        assertNull(
            BrowserProbeServer.decodeMicrophoneUpload(
                headers = headers + ("x-audio-sample-rate" to "7999"),
                body = Base64.getEncoder().encode(pcm),
            ),
        )
        assertNull(
            BrowserProbeServer.decodeMicrophoneUpload(
                headers = headers + ("x-audio-channels" to "2"),
                body = Base64.getEncoder().encode(byteArrayOf(1, 2)),
            ),
        )
        assertNull(BrowserProbeServer.decodeMicrophoneUpload(headers, "%%%".toByteArray()))
    }

    @Test
    fun plainHttpMicrophoneUploadIsRestrictedToTrustworthyLoopback() {
        assertTrue(
            BrowserProbeServer.isPlainHttpMicrophonePeerAllowed(
                InetAddress.getByName("127.0.0.1"),
            ),
        )
        assertFalse(
            BrowserProbeServer.isPlainHttpMicrophonePeerAllowed(
                InetAddress.getByName("192.168.50.20"),
            ),
        )
    }

    @Test
    fun idleAudioHeartbeatIsOneSilentAlignedFrame() {
        assertTrue(BrowserProbeServer.audioIdleHeartbeat(1).contentEquals(byteArrayOf(0, 0)))
        assertTrue(
            BrowserProbeServer.audioIdleHeartbeat(2).contentEquals(byteArrayOf(0, 0, 0, 0)),
        )
        assertThrows(IllegalArgumentException::class.java) {
            BrowserProbeServer.audioIdleHeartbeat(0)
        }
    }

    @Test
    fun activeClientRegistryClosesCurrentAndLateSockets() {
        val registry = ActiveClientSocketRegistry()
        val active = Socket()
        assertTrue(registry.register(active))
        assertEquals(1, registry.activeCount())

        registry.close()

        assertTrue(active.isClosed)
        assertTrue(registry.isClosed())
        assertEquals(0, registry.activeCount())
        val late = Socket()
        assertFalse(registry.register(late))
        assertTrue(late.isClosed)
    }

    @Test
    fun audioWriteWatchdogAllowsProgressButRejectsTenSecondStall() {
        assertFalse(BrowserProbeServer.isAudioWriteStalled(9_999L, 0L))
        assertTrue(BrowserProbeServer.isAudioWriteStalled(10_000L, 0L))
        assertFalse(BrowserProbeServer.isAudioWriteStalled(5_000L, 6_000L))
    }

    @Test
    fun bundledBrowserAssetsKeepExternalScriptAndBoundedPairingDom() {
        val index = readAsset("index.html")
        val script = readAsset("app.js")

        assertTrue(index.startsWith("<!doctype html>"))
        assertTrue(
            index.contains(
                "<input id=\"code\" inputmode=\"numeric\" maxlength=\"9\" autocomplete=\"one-time-code\"",
            ),
        )
        assertTrue(index.contains("<video id=\"webrtc-video\" autoplay muted playsinline>"))
        assertTrue(index.contains("<img id=\"frame\""))
        assertTrue(index.contains("draggable=\"false\""))
        assertTrue(index.contains("<script src=\"/app.js\"></script>"))
        assertFalse(index.contains("<script>"))
        assertFalse(index.contains("http://"))
        assertFalse(index.contains("https://"))
        assertTrue(script.isNotBlank())
        assertFalse(script.contains("C:\\\\"))
    }

    private fun readAsset(name: String): String {
        val candidates = listOf(
            File("app/src/main/assets/tesla/$name"),
            File("src/main/assets/tesla/$name"),
        )
        val asset = candidates.firstOrNull(File::isFile)
            ?: error("missing browser asset $name")
        return asset.readText(Charsets.UTF_8)
    }
}
