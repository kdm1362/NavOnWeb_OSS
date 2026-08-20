package com.eigenkodex.navonweb.browser

import com.eigenkodex.navonweb.browser.audio.BrowserAudioTrack
import com.eigenkodex.navonweb.core.TouchPhase
import com.eigenkodex.navonweb.session.AndroidAutoConnectionState
import com.eigenkodex.navonweb.session.AndroidAutoConnectionStatus
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
import org.junit.Assume.assumeTrue
import org.junit.Test

class BrowserProbeServerPolicyTest {
    @Test
    fun authenticatedWebRtcOwnerIsWiredToAtomicSessionHandoff() {
        val server = readProjectFile(
            "app/src/main/java/com/eigenkodex/navonweb/browser/BrowserProbeServer.kt",
        )
        val controller = readProjectFile(
            "app/src/main/java/com/eigenkodex/navonweb/browser/webrtc/WebRtcProjectionController.kt",
        )

        assertTrue(
            server.contains(
                "ownerKey = fresh.ownerKey",
            ),
        )
        assertTrue(server.contains("commitAuthenticatedBrowserOperation("))
        assertTrue(server.contains("lock = pairingCodeOperationLock"))
        assertTrue(server.contains("authenticate = { browserTrustStore.authenticate(browserCredential) }"))

        val openStart = controller.indexOf("override fun openSession(offer: BrowserWebRtcOffer)")
        val openEnd = controller.indexOf("override fun session(sessionId: String, ownerKey: String)", openStart)
        assertTrue(openStart >= 0 && openEnd > openStart)
        val openSession = controller.substring(openStart, openEnd)
        val atomicAdmission = openSession.indexOf("var replacedRuntime: SessionRuntime? = null")
        val closePrior = openSession.indexOf("state = BrowserWebRtcSessionState.CLOSED", atomicAdmission)
        val installReplacement = openSession.indexOf("sessions[runtime.snapshot.sessionId] = runtime", closePrior)
        val disposePrior = openSession.indexOf("replaced?.let(::disposePeer)", installReplacement)
        val createReplacement = openSession.indexOf("createPeerConnection(runtime", disposePrior)
        assertTrue(atomicAdmission >= 0)
        assertTrue(closePrior > atomicAdmission)
        assertTrue(installReplacement > closePrior)
        assertTrue(disposePrior > installReplacement)
        assertTrue(createReplacement > disposePrior)
        assertTrue(openSession.contains("requestedOwnerKey = offer.ownerKey"))

        val closeStart = controller.indexOf("override fun closeSession(sessionId: String, ownerKey: String)")
        val closeEnd = controller.indexOf(
            "override fun closeSessionsForOwner(ownerKey: String)",
            closeStart,
        )
        assertTrue(closeStart >= 0 && closeEnd > closeStart)
        assertTrue(controller.substring(closeStart, closeEnd).contains("it.ownerKey == ownerKey"))
    }

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
    fun permissionSensitiveInputRoutesReauthenticateInsideTheSharedAdmissionLock() {
        val server = readProjectFile(
            "app/src/main/java/com/eigenkodex/navonweb/browser/BrowserProbeServer.kt",
        )
        listOf(
            "private fun serveProjectionViewportRequest(",
            "private fun serveMicrophone(",
            "private fun serveTouch(",
        ).forEach { signature ->
            val start = server.indexOf(signature)
            assertTrue("missing method $signature", start >= 0)
            val end = server.indexOf("\n    private fun ", start + signature.length)
                .takeIf { it > start }
                ?: server.length
            val method = server.substring(start, end)
            assertTrue(method.contains("= synchronized(pairingCodeOperationLock)"))
            assertTrue(method.contains("authenticatedDevice(request)"))
            assertTrue(method.contains("BrowserDevicePermission.READ_ONLY"))
        }
    }

    @Test
    fun permissionDowngradeCommitsBeforeAnotherInputSideEffect() {
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
    fun expiredTouchCancellationUsesLeaseMetadataAfterSourceDisconnects() {
        val server = readProjectFile(
            "app/src/main/java/com/eigenkodex/navonweb/browser/BrowserProbeServer.kt",
        )
        val start = server.indexOf("private fun serveTouch(")
        assertTrue("missing serveTouch", start >= 0)
        val end = server.indexOf("\n    private fun authenticatedDevice(", start)
            .takeIf { it > start }
            ?: server.length
        val method = server.substring(start, end)

        assertTrue(method.contains("sourceDeviceId = expiredSource.deviceId"))
        assertTrue(method.contains("colorSlot = expiredSource.colorSlot"))
        assertFalse(method.contains("activeSessionMetadata(expiredSource.ownerKey)"))
    }

    @Test
    fun serviceSharesOneAdmissionLockWithServerAndEveryWebRtcController() {
        val service = readProjectFile(
            "app/src/main/java/com/eigenkodex/navonweb/service/ProjectionService.kt",
        )

        assertTrue(service.contains("private val browserSessionAdmissionLock = Any()"))
        assertTrue(
            service.contains("pairingCodeOperationLock = browserSessionAdmissionLock"),
        )
        assertEquals(
            3,
            Regex("sessionAdmissionLock = browserSessionAdmissionLock")
                .findAll(service)
                .count(),
        )
    }

    @Test
    fun pairingUsesPersistentDeviceRegistryWithoutSessionRevocation() {
        val server = readProjectFile(
            "app/src/main/java/com/eigenkodex/navonweb/browser/BrowserProbeServer.kt",
        )
        val pairStart = server.indexOf("private fun servePair(request: HttpRequest, output: OutputStream)")
        val pairEnd = server.indexOf("private fun serveTouch(", pairStart)
        assertTrue(pairStart >= 0 && pairEnd > pairStart)
        val pairOperation = server.substring(pairStart, pairEnd)

        assertTrue(server.contains("browserTrustStore.pairNewDevice("))
        assertTrue(pairOperation.contains("issuePairedBrowserDeviceFromPairingHeaders("))
        assertFalse(pairOperation.contains("closeSessionsForOwner("))
        assertFalse(pairOperation.contains("closeSessionsForDevice("))
        assertFalse(pairOperation.contains("reconcileSessionLimit("))
        assertFalse(server.contains("issueBrowserCredentialAndRevokePriorSession("))
        assertFalse(server.contains("revokeActiveSessionForCredentialChange"))
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
                InetAddress.getByName("10.0.0.20"),
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
    fun developmentViewportFlagIsInjectedOnlyForDebugAndFailsClosed() {
        val script = readAsset("app.js")
        val disabled = "const NAVONWEB_DEVELOPMENT_VIEWPORT_ENABLED = false;"
        val enabled = "const NAVONWEB_DEVELOPMENT_VIEWPORT_ENABLED = true;"

        assertTrue(script.contains(disabled))
        assertFalse(script.contains(enabled))
        assertEquals(script, BrowserProbeServer.renderAppScript(script, isDebugBuild = false))

        val debugScript = BrowserProbeServer.renderAppScript(script, isDebugBuild = true)
        assertTrue(debugScript.contains(enabled))
        assertFalse(debugScript.contains(disabled))

        val missingMarker = script.replace(disabled, "const NAVONWEB_DEVELOPMENT_VIEWPORT_ENABLED = 0;")
        assertEquals(
            missingMarker,
            BrowserProbeServer.renderAppScript(missingMarker, isDebugBuild = true),
        )
        val duplicateMarker = "$script\n$disabled\n"
        assertEquals(
            duplicateMarker,
            BrowserProbeServer.renderAppScript(duplicateMarker, isDebugBuild = true),
        )
    }

    @Test
    fun browserPageContainsPairingProjectionPresentationAndFullscreenControls() {
        val index = readAsset("index.html")
        val script = readAsset("app.js")

        assertTrue(index.contains("id=\"pairing-form\""))
        assertTrue(index.contains("id=\"code\""))
        assertTrue(index.contains("id=\"pair\""))
        assertTrue(index.contains("id=\"viewer\""))
        assertTrue(index.contains("id=\"pad\""))
        assertTrue(index.contains("<div id=\"projection-content\">"))
        assertTrue(index.contains("<img id=\"frame\""))
        assertTrue(index.contains("<video id=\"webrtc-video\""))
        assertTrue(index.contains("id=\"viewer-controls\""))
        assertTrue(index.contains("id=\"fullscreen\""))
        assertTrue(index.contains("aria-label=\"View Android Auto in fullscreen\""))
        assertTrue(index.contains("data-i18n-aria-label=\"fullscreenEnterLabel\""))
        assertTrue(index.contains("aria-pressed=\"false\""))
        assertTrue(index.contains("id=\"media-permission-panel\""))
        assertTrue(index.contains("id=\"media-permission-allow\""))
        assertEquals(8, Regex("<button(?:\\s|>)").findAll(index).count())
        assertTrue(index.contains("id=\"presentation-orientation\""))
        assertTrue(index.contains("id=\"presentation-alignment-trigger\""))
        assertTrue(index.contains("aria-controls=\"presentation-alignment-picker\""))
        assertTrue(index.contains("id=\"presentation-alignment-picker\" role=\"radiogroup\""))
        assertEquals(
            9,
            Regex("name=\"presentation-alignment-choice\"").findAll(index).count(),
        )
        assertTrue(index.contains("id=\"presentation-alignment\""))
        assertTrue(index.contains("id=\"presentation-guide\""))

        val padStart = index.indexOf("<div id=\"pad\"")
        val controlsStart = index.indexOf("<div id=\"viewer-controls\"")
        val fullscreenButton = index.indexOf("<button id=\"fullscreen\"")
        assertTrue(padStart >= 0 && controlsStart > padStart)
        assertTrue(fullscreenButton > controlsStart)
        assertFalse(index.substring(padStart, controlsStart).contains("id=\"fullscreen\""))
        assertTrue(index.contains("#viewer:fullscreen"))
        assertTrue(index.contains("grid-template-rows: minmax(0, 1fr)"))
        assertTrue(index.contains("#viewer:fullscreen #viewer-controls,"))
        assertTrue(index.contains("body.theater-mode #viewer-controls { display: none !important; }"))
        assertTrue(index.contains("body.theater-mode #viewer"))

        val removedUiMarkers = listOf(
            "<header",
            "<audio",
            "<dl",
            "<pre",
            "id=\"projection-profile\"",
            "id=\"apply-profile\"",
            "id=\"profile-status\"",
            "id=\"codec\"",
            "id=\"webrtc\"",
            "id=\"stream-metrics\"",
            "id=\"aa-connection\"",
            "id=\"event\"",
        )
        removedUiMarkers.forEach { marker -> assertFalse("unexpected browser UI: $marker", index.contains(marker)) }

        assertTrue(script.contains("window.localStorage.getItem(STORAGE_KEY)"))
        assertTrue(script.contains("window.localStorage.setItem(STORAGE_KEY, value)"))
        assertTrue(script.contains("connectRemembered(rememberedCredential)"))
        assertTrue(script.contains("/api/frame.jpg?after="))
        assertTrue(script.contains("'X-Browser-Credential': credential"))
        assertTrue(script.contains("URL.createObjectURL(blob)"))
        assertTrue(script.contains("new RTCPeerConnection"))
        assertTrue(script.contains("addTransceiver('video', {direction: 'recvonly'})"))
        assertTrue(script.contains("/api/webrtc/capabilities"))
        assertTrue(script.contains("/api/webrtc/session?codec=auto"))
        assertTrue(script.contains("scheduleWebRtcRecovery()"))
        assertTrue(script.contains("new AudioContextClass({latencyHint: 'interactive'})"))
        assertTrue(script.contains("`/api/audio/${'$'}{track}`"))
        assertTrue(script.contains("response.body.getReader()"))
        assertTrue(script.contains("new Int16Array(pcmBytes.buffer, pcmBytes.byteOffset, sampleCount)"))
        assertTrue(script.contains("view.getInt16(index * 2, true)"))
        assertTrue(script.contains("unlockAudio()"))
        assertTrue(script.contains("stopAudioStreams(true)"))
        assertTrue(script.contains("pointerId: '0'"))
        assertTrue(script.contains("coordinateSpace: 'content'"))
        assertTrue(script.contains("const touchControlQueue = []"))
        assertTrue(script.contains("pendingMoveTouch = request"))
        assertTrue(script.contains("if (phase === 'up' || phase === 'cancel') pendingMoveTouch = null"))
        assertTrue(script.contains("point(event, false)"))
        assertTrue(script.contains("const STATUS_HEALTHY_MIN_INTERVAL_MILLIS = 1500"))
        assertTrue(script.contains("const STATUS_HEALTHY_MAX_INTERVAL_MILLIS = 2500"))
        assertTrue(script.contains("const STATUS_FAILURE_BASE_INTERVAL_MILLIS = 2000"))
        assertTrue(script.contains("const STATUS_FAILURE_MAX_INTERVAL_MILLIS = 5000"))
        assertTrue(script.contains("function statusPollDelayMillis(failureCount, randomSample = Math.random())"))
        assertTrue(script.contains("const STATUS_REQUEST_TIMEOUT_MILLIS = 5000"))
        assertTrue(script.contains("let statusGeneration = 0"))
        assertTrue(script.contains("let statusPollTask = null"))
        assertTrue(script.contains("statusPollTask.credential === credential"))
        assertTrue(script.contains("if (statusPollTask === task) statusPollTask = null"))
        assertTrue(script.contains("if (!statusPollTaskIsCurrent(task)) return false"))
        assertTrue(script.contains("const task = statusPollTask;\n    statusPollTask = null;"))
        assertTrue(script.contains("function statusPollingEligible()"))
        assertFalse(script.contains("statusPollPromise"))
        assertFalse(script.contains("statusAbortController"))
        assertTrue(script.contains("await pollStatus();"))
        assertTrue(script.contains("scheduleStatusPoll();"))
        assertFalse(script.contains("setInterval(() => pollStatus()"))
        assertTrue(script.contains("stopStatusPolling();\n      if (!microphoneCaptureIsTerminal())"))
        assertTrue(script.contains("if (statusPollingEligible()) startStatusPolling();"))

        assertTrue(script.contains("document.fullscreenElement === viewer"))
        assertTrue(script.contains("viewer.requestFullscreen()"))
        assertTrue(script.contains("document.exitFullscreen()"))
        assertTrue(script.contains("document.addEventListener('fullscreenchange'"))
        assertTrue(script.contains("document.addEventListener('fullscreenerror'"))
        assertTrue(script.contains("document.body.classList.toggle('theater-mode'"))
        assertTrue(script.contains("fullscreenButton.setAttribute('aria-pressed'"))
        assertFalse(script.contains("pad.requestFullscreen()"))
        assertTrue(script.contains("const PINCH_EXPAND_SCALE = 1.18"))
        assertTrue(script.contains("const PINCH_COLLAPSE_SCALE = 0.82"))
        assertTrue(script.contains("function beginPinchGesture()"))
        assertTrue(script.contains("cancelActivePointer();\n    suppressAndroidAutoTouch = true"))
        assertTrue(script.contains("setExpandedView(pinchGesture.intent === 'expand')"))
        assertTrue(script.contains("touchPointers.size === 0"))
        assertTrue(script.contains("const NAVONWEB_DEVELOPMENT_VIEWPORT_ENABLED = false;"))
        assertTrue(script.contains("const DEVELOPMENT_VIEWPORT_QUERY = 'navonweb-dev-viewport'"))
        assertTrue(script.contains("const DEVELOPMENT_TESLA_DRIVING_MODE = 'tesla-driving'"))
        assertTrue(script.contains("const DEVELOPMENT_TESLA_CYCLE_MODE = 'tesla-cycle'"))
        assertTrue(script.contains("window.__navOnWebDevelopmentViewport = Object.freeze({"))
        assertTrue(script.contains("snapshot: developmentViewportSnapshot"))
        assertTrue(script.contains("setTeslaDriving: setDevelopmentTeslaDriving"))
        assertTrue(script.contains("function scheduleViewportReport(value)"))
        assertTrue(script.contains("function setDevelopmentViewportDiagnostic(name, value)"))
        assertTrue(script.contains("data-navonweb-${'$'}{name}"))
        assertTrue(script.contains("navigator.onLine can be false in"))
        assertFalse(script.contains("typeof navigator.onLine"))
        assertTrue(script.contains("viewportReportKey(pendingViewportReport) === key"))
        assertTrue(script.contains("'X-Viewport-Client-Id': VIEWPORT_CLIENT_ID"))
        assertTrue(script.contains("'navonweb.browserCredential.v1'"))
        assertTrue(script.contains("'navonweb.browserCredential.v2'"))
        assertTrue(script.contains("const LEGACY_STORAGE_KEY_SUFFIX"))
        assertTrue(script.contains("key.endsWith(LEGACY_STORAGE_KEY_SUFFIX)"))
        assertTrue(script.contains("window.localStorage.setItem(STORAGE_KEY, legacyCredential)"))
        assertTrue(script.contains("window.localStorage.removeItem(legacyKey)"))
        assertTrue(script.contains("bootstrapPairUrl: `${'$'}{secureHttpOrigin}${'$'}{pathPrefix}/bootstrap/pair`"))
        assertTrue(script.contains("signalingWebSocketPathPrefix"))
        assertTrue(script.contains("credentials: 'include'"))
        assertTrue(script.contains("new WebSocket(this.config.webSocketUrl)"))
        assertTrue(script.contains("type: 'rpc_request'"))
        assertTrue(script.contains("bodyBase64: base64NoWrap(body)"))
        assertTrue(script.contains("if (CLOUD_RELAY_MODE) return;"))
        assertTrue(script.contains("const AUDIO_WEBRTC_CHANNEL_PREFIX = 'navonweb-audio-'"))
        assertTrue(script.contains("createOutputAudioWebRtcChannels(peer, generation, outputAudioTracks)"))
        assertTrue(script.contains("parseOutputAudioWebRtcFrame(event.data)"))
        val audioChannelStart = script.indexOf("function createOutputAudioWebRtcChannels")
        val audioChannelEnd = script.indexOf("async function consumeAudioStream", audioChannelStart)
        assertTrue(audioChannelStart >= 0 && audioChannelEnd > audioChannelStart)
        val audioChannelSetup = script.substring(audioChannelStart, audioChannelEnd)
        assertTrue(audioChannelSetup.contains("ordered: true"))
        assertFalse(audioChannelSetup.contains("maxRetransmits"))
        assertFalse(audioChannelSetup.contains("webRtcAudioOpenTimers.set"))
        assertTrue(script.contains("function armOutputAudioWebRtcOpenTimers(generation)"))
        assertTrue(script.contains("armOutputAudioWebRtcOpenTimers(generation);"))
        assertTrue(script.contains("const AUDIO_WEBRTC_RECOVERY_BASE_DELAY_MILLIS = 2000"))
        assertTrue(script.contains("const AUDIO_WEBRTC_RECOVERY_MAX_DELAY_MILLIS = 30000"))
        val audioRecoveryStart = script.indexOf("function failOutputAudioWebRtcChannel")
        val audioRecoveryEnd = script.indexOf("function parseOutputAudioWebRtcFrame")
        assertTrue(audioRecoveryStart >= 0 && audioRecoveryEnd > audioRecoveryStart)
        val audioRecovery = script.substring(audioRecoveryStart, audioRecoveryEnd)
        assertTrue(audioRecovery.contains("scheduleOutputAudioWebRtcRecovery()"))
        assertTrue(audioRecovery.contains("video and microphone remain connected"))
        assertFalse(audioRecovery.contains("alert("))
        assertFalse(audioRecovery.contains("confirm("))
        assertFalse(audioRecovery.contains("showModal("))
        assertTrue(script.contains("mediaPermissionAllow.addEventListener('click'"))
        assertTrue(script.contains("outputAudioDataChannelsV1"))
        assertTrue(script.contains("context.state !== 'running'"))
        assertTrue(script.contains("const response = await api('/api/pair'"))
        assertFalse(script.contains("fetch('/api/pair'"))
        assertTrue(script.contains("conflict.error === 'viewport_controller_busy'"))
        assertTrue(script.contains("VIEWPORT_CONTROLLER_BUSY_RETRY_MILLIS"))
        assertTrue(script.contains("api('/health', {cache: 'no-store'}, '')"))
        assertTrue(script.contains("const DEVELOPMENT_TESLA_CYCLE_INTERVAL_MILLIS = 12000"))
        assertTrue(script.contains("function expectedExpandedProjectionViewport()"))
        assertTrue(script.contains("return expectedExpandedProjectionViewport();"))
        assertTrue(script.contains("/api/projection/viewport?${'$'}{query}"))
        assertTrue(script.contains("function browserDevicePixelRatio()"))
        assertTrue(script.contains("devicePixelRatio: String(value.devicePixelRatio)"))
        // Profile-specific app settings use the trusted 72..320 range; browsers only consume
        // the resulting metadata and cannot submit a DPI value themselves.
        assertTrue(script.contains("value.densityDpi < 72 || value.densityDpi > 320"))
        assertTrue(script.contains("`@${'$'}{next.densityDpi}dpi`"))
        assertTrue(script.contains("{method: 'POST', signal: controller.signal}"))
        assertTrue(script.contains("normalizeProjectionViewport(data.projection.viewport.activeLayout)"))
        assertTrue(script.contains("syncProjectionMediaCrop()"))
        assertTrue(script.contains("WebRTC session while Android Auto renegotiates"))
        assertTrue(script.contains("new ResizeObserver(() => scheduleViewportLayoutSync())"))
        assertTrue(script.contains("window.visualViewport.addEventListener('resize'"))
        assertTrue(script.contains("window.visualViewport.addEventListener('scroll'"))
        assertTrue(script.contains("viewportLayoutFrame = window.requestAnimationFrame"))
        assertTrue(script.contains("const enabled = Boolean(projection && projection.activeProfile)"))
        assertFalse(script.contains("const enabled = Boolean(projection && projection.entitlement === 'premium')"))
        assertTrue(script.contains("projection.entitlement !== 'free'"))
        assertTrue(index.contains("body.navonweb-authenticated main {"))
        assertTrue(index.contains("align-content: start;"))
        assertTrue(
            index.contains(
                "padding-bottom: max(clamp(12px, 3vw, 32px), env(safe-area-inset-bottom));",
            ),
        )
        assertTrue(index.contains("body.navonweb-dynamic-aspect #pad"))
        assertTrue(index.contains("top: 0;"))
        assertTrue(index.contains("transform: translateX(-50%)"))
        assertTrue(script.contains("source = 'fullscreenPreview'"))
        assertTrue(script.contains("source = 'theaterPreview'"))
        assertTrue(script.contains("activeProjectionProfile.sourceAspectWidth"))
        assertTrue(script.contains("androidAutoTouchReady = androidAutoInteractive"))
        assertTrue(script.contains("status poll from turning that temporary mismatch into an endless reconnect loop"))
        assertFalse(script.contains("const projectionAspect = activeViewport.contentWidth"))
        assertTrue(script.contains("const TOUCH_REQUEST_TIMEOUT_MILLIS = 1500"))
        assertTrue(script.contains("signal: controller.signal"))
        assertTrue(script.contains("touchQueueGeneration += 1"))
        assertTrue(script.contains("touchRecoveryCancelPending = true"))
        assertTrue(script.contains("if (response.status === 202)"))
        assertTrue(script.contains("touchRecoveryCancelPending = false"))
        assertTrue(index.contains("body.navonweb-development-tesla-driving main"))
        assertFalse(script.contains("/api/projection/layout"))
        assertFalse(script.contains("browserCredential=${'$'}{"))
        assertFalse(index.contains('\uFFFD'))
        assertFalse(script.contains('\uFFFD'))
    }

    @Test
    fun browserFullscreenHidesDisplayToolbarAndShowsGuideOrPlatformHintOnlyOnEntry() {
        val index = readAsset("index.html")
        val script = readAsset("app.js")

        assertTrue(index.contains("id=\"fullscreen-hint\""))
        assertTrue(index.contains("id=\"fullscreen-hint\" role=\"status\" aria-live=\"polite\" hidden"))
        assertTrue(index.contains("#fullscreen-hint"))
        assertTrue(index.contains("pointer-events: none"))
        assertTrue(index.contains("transform: translateX(-50%)"))
        assertTrue(index.contains("grid-template-rows: minmax(0, 1fr);"))
        assertTrue(index.contains("gap: 0;"))

        assertTrue(script.contains("const FULLSCREEN_HINT_DURATION_MILLIS = 5000"))
        assertTrue(script.contains("let expandedViewWasActive = false"))
        assertTrue(script.contains("let fullscreenHintTimer = 0"))
        assertTrue(script.contains("let fullscreenEntryPendingGeneration = 0"))
        assertTrue(index.contains("body.theater-mode #viewer-controls { display: none !important; }"))
        assertTrue(script.contains("fullscreenButton.hidden = expanded"))
        assertTrue(script.contains("viewerControls.hidden = expanded"))
        assertTrue(
            script.contains(
                "if (expanded && !expandedViewWasActive && !maybeShowPresentationGuide()) " +
                    "showFullscreenHint();",
            ),
        )
        assertTrue(script.contains("if (!expanded) {"))
        assertTrue(script.contains("hidePresentationGuide(false)"))
        assertTrue(script.contains("expandedViewWasActive = expanded"))
        assertTrue(script.contains("if (fullscreenEntryPendingGeneration !== 0) return"))
        assertTrue(script.contains("fullscreenEntryPendingGeneration = generation"))
        assertTrue(script.contains("generation === expandedViewRequestGeneration"))
        assertTrue(script.contains("if (viewerOwnsFullscreen() && theaterMode)"))
        assertTrue(script.contains("document.body.classList.remove('theater-mode')"))
        assertTrue(script.contains("fullscreenButton.textContent = t(expanded ? 'fullscreenExit' : 'fullscreenEnter')"))

        assertTrue(script.contains("navigator.userAgentData.platform"))
        assertTrue(script.contains("navigator.platform"))
        assertTrue(script.contains("navigator.userAgent"))
        assertTrue(script.contains("/windows|win32|win64|wince|wow64/i"))
        assertTrue(
            script.contains(
                "t(WINDOWS_PLATFORM ? 'fullscreenHintWindows' : 'fullscreenHintTouch')",
            ),
        )
        assertTrue(script.contains("fullscreenHintWindows: 'Esc로 전체화면 종료'"))
        assertTrue(script.contains("fullscreenHintTouch: '핀치 줌아웃/인으로 전체화면을 전환할 수 있습니다'"))
        assertTrue(script.contains("fullscreenHintWindows: 'Press Esc to exit fullscreen.'"))
        assertTrue(script.contains("fullscreenHintTouch: 'Pinch out/in to toggle fullscreen.'"))
    }

    @Test
    fun browserLocalizesVisibleTextInKoreanAndEnglishFromSystemLanguage() {
        val index = readAsset("index.html")
        val script = readAsset("app.js")

        fun localeKeys(locale: String, nextMarker: String): Set<String> {
            val marker = "    $locale: Object.freeze({"
            val start = script.indexOf(marker)
            val end = script.indexOf(nextMarker, start + marker.length)
            assertTrue("missing locale block $locale", start >= 0 && end > start)
            return Regex("(?m)^      ([A-Za-z][A-Za-z0-9]*):")
                .findAll(script.substring(start, end))
                .map { it.groupValues[1] }
                .toSet()
        }

        val englishKeys = localeKeys("en", "    ko: Object.freeze({")
        val koreanKeys = localeKeys("ko", "  });\n  const PATH_LOCALE")
        assertEquals(englishKeys, koreanKeys)
        assertTrue(englishKeys.size >= 25)

        assertTrue(index.contains("<html lang=\"en\""))
        assertTrue(index.contains("data-i18n-pending"))
        assertTrue(index.contains("data-i18n=\"pairingCodeLabel\""))
        // Eight digits are rendered as "0000 0000", so the visible separator also needs a slot.
        assertTrue(index.contains("inputmode=\"numeric\" maxlength=\"9\""))
        assertTrue(index.contains("data-i18n=\"connect\""))
        assertTrue(index.contains("data-i18n=\"pairingRememberedHint\""))
        assertTrue(index.contains("data-i18n=\"androidAutoWaiting\""))
        assertTrue(index.contains("data-i18n-aria-label=\"browserPairing\""))
        assertTrue(index.contains("data-i18n-aria-label=\"projectionInputArea\""))
        assertTrue(index.contains("data-i18n-alt=\"projectionFrameAlt\""))

        assertTrue(script.contains("if (Array.isArray(navigator.languages))"))
        assertTrue(script.contains("if (typeof navigator.language === 'string')"))
        assertTrue(script.contains("if (base === 'ko' || base === 'en') return base"))
        assertTrue(script.contains("return 'en';"))
        assertTrue(script.contains("const PATH_LOCALE = resolvePathLocale(window.location.pathname)"))
        assertTrue(script.contains("let ACTIVE_LOCALE = PATH_LOCALE || resolveSystemLocale()"))
        assertTrue(script.contains("document.documentElement.lang = ACTIVE_LOCALE"))
        assertTrue(script.contains("document.querySelectorAll('[data-i18n]')"))
        assertTrue(script.contains("document.querySelectorAll('[data-i18n-aria-label]')"))
        assertTrue(script.contains("document.querySelectorAll('[data-i18n-alt]')"))
        assertTrue(script.contains("applyDocumentLocale();"))
        assertTrue(script.contains("Object.defineProperty(window, 'dbg'"))
        assertTrue(script.contains("changeLang: changeDebugLanguage"))
        assertTrue(script.contains("requested === 'ko' || requested === 'en'"))
        assertFalse(script.contains("requested === 'kr'"))
        assertTrue(script.contains("accepts only \"ko\" or \"en\""))
        assertTrue(script.contains("applyDocumentLocale(previousLocale)"))

        assertTrue(script.contains("setStreamState('androidAutoWaiting')"))
        assertTrue(script.contains("setStreamState('videoWaiting')"))
        assertTrue(script.contains("setStreamState('serverWaiting')"))
        assertTrue(script.contains("setPairStatus(t('eightDigitRequired'), true)"))
        assertTrue(script.contains("bootstrapRouteMissing ? t('codeNotRegistered') : t('unableToConnect')"))
        assertTrue(script.contains("invalidateCredential(t('connectionExpiredPhone'))"))
    }

    @Test
    fun browserLoadsBoundedLocalizedNoticesBelowProjectionAndHidesThemWhenExpanded() {
        val index = readAsset("index.html")
        val script = readAsset("app.js")

        assertTrue(index.contains("<details id=\"notice-panel\""))
        assertTrue(index.contains("<summary data-i18n=\"announcements\">Announcements</summary>"))
        assertTrue(index.contains("id=\"notice-status\" role=\"status\" aria-live=\"polite\""))
        assertTrue(index.contains("id=\"notice-list\""))
        assertTrue(index.contains("#viewer:fullscreen + #notice-panel { display: none; }"))
        assertTrue(index.contains("body.theater-mode #notice-panel { display: none; }"))
        assertTrue(index.contains("</section>\n\n  <details id=\"notice-panel\""))

        assertTrue(script.contains("const MAX_NOTICE_COUNT = 20"))
        assertTrue(script.contains("const MAX_NOTICE_RESPONSE_BYTES = 64 * 1024"))
        assertTrue(script.contains("const NOTICE_REFRESH_INTERVAL_MILLIS = 5 * 60 * 1000"))
        assertTrue(script.contains("const NOTICE_RETRY_INTERVAL_MILLIS = 30 * 1000"))
        assertTrue(script.contains("api('/api/notices', {signal: task.controller.signal}, credential)"))
        assertTrue(script.contains("response.headers.get('content-length')"))
        assertTrue(script.contains("fallback.byteLength > MAX_NOTICE_RESPONSE_BYTES"))
        assertTrue(script.contains("totalBytes > MAX_NOTICE_RESPONSE_BYTES"))
        assertTrue(script.contains("await reader.cancel().catch(() => null)"))
        assertTrue(script.contains("payload.notices.slice(0, MAX_NOTICE_COUNT)"))
        assertTrue(script.contains("let NOTICE_LOCALE_CANDIDATES = resolveNoticeLocaleCandidates("))
        assertTrue(
            script.contains(
                "PATH_LOCALE ? [PATH_LOCALE, ...browserLanguageCandidates] : browserLanguageCandidates",
            ),
        )
        assertTrue(script.contains(".map(language => normalizedNested.get(language))"))
        assertTrue(script.contains("const selectedSuffix = ACTIVE_LOCALE === 'ko' ? 'Ko' : 'En'"))
        assertTrue(script.contains("const fallbackSuffix = ACTIVE_LOCALE === 'ko' ? 'En' : 'Ko'"))
        assertTrue(script.contains("title.textContent = notice.title"))
        assertTrue(script.contains("body.textContent = notice.body"))
        assertTrue(script.contains("available: payload.available !== false"))
        assertTrue(script.contains("!normalized.available"))
        assertTrue(script.contains("noticeStatus.textContent = t('noticesUnavailable')"))
        assertTrue(script.contains(": empty ? t('noAnnouncements')"))
        assertTrue(script.contains("ensureNoticesLoaded();"))
        assertTrue(script.contains("now < noticeNextRequestEpochMillis"))
        assertTrue(script.contains("payload && payload.available !== false"))
        assertFalse(script.contains(".innerHTML"))
    }

    @Test
    fun freeConnectedBrowserShowsPremiumPromptOnceAndPersistsOnlyConfirmedOptOut() {
        val index = readAsset("index.html")
        val script = readAsset("app.js")

        assertTrue(index.contains("id=\"premium-prompt\" role=\"dialog\""))
        assertTrue(index.contains("id=\"premium-prompt-dismiss\" type=\"checkbox\""))
        assertTrue(index.contains("id=\"premium-prompt-confirm\" type=\"button\""))
        val controls = index.indexOf("id=\"viewer-controls\"")
        val prompt = index.indexOf("id=\"premium-prompt\"")
        val fullscreenState = index.indexOf("id=\"fullscreen-state\"")
        assertTrue(controls >= 0 && prompt > controls && fullscreenState > prompt)

        assertTrue(script.contains("const PREMIUM_PROMPT_DURATION_MILLIS = 10000"))
        assertTrue(script.contains("const PREMIUM_PROMPT_DISMISSED_KEY = 'navonweb.premiumPromptDismissed.v1'"))
        assertTrue(script.contains("let premiumPromptOfferedForSession = false"))
        assertTrue(script.contains("androidAutoInteractive = state === 'CONNECTED'"))
        assertTrue(script.contains("!projection || projection.entitlement !== 'free'"))
        assertTrue(script.contains("maybeShowPremiumPrompt(wasInteractive, projection)"))
        assertTrue(script.contains("premiumPromptOfferedForSession = true"))
        assertTrue(script.contains("premiumPromptTimer = window.setTimeout"))
        assertTrue(script.contains("}, PREMIUM_PROMPT_DURATION_MILLIS)"))
        assertTrue(script.contains("window.localStorage.getItem(PREMIUM_PROMPT_DISMISSED_KEY) === 'true'"))
        assertTrue(script.contains("window.localStorage.setItem(PREMIUM_PROMPT_DISMISSED_KEY, 'true')"))
        assertTrue(script.contains("const dismissPermanently = premiumPromptDismiss.checked"))
        assertTrue(script.contains("if (dismissPermanently) rememberPremiumPromptDismissal();"))
        assertTrue(script.contains("if (!projection || projection.entitlement !== 'free') hidePremiumPrompt();"))
        assertTrue(script.contains("resetPremiumPromptSession();\n    resetNoticeSession();"))
        assertTrue(script.contains("hidePremiumPrompt();\n    cancelNoticeRequestForRetry();"))

        val offerStart = script.indexOf("function maybeShowPremiumPrompt")
        val offerEnd = script.indexOf("function resetNoticeSession", offerStart)
        assertTrue(offerStart >= 0 && offerEnd > offerStart)
        assertFalse(script.substring(offerStart, offerEnd).contains("rememberPremiumPromptDismissal"))

        assertTrue(
            script.contains(
                "premiumUpgradeMessage: '고해상도(1080p), 스테레오 음질을 사용하려면 프리미엄 티어를 결제해 주세요'",
            ),
        )
        assertTrue(
            script.contains(
                "premiumUpgradeMessage: 'Please purchase the Premium tier to use high-resolution (1080p) video and stereo audio.'",
            ),
        )
        assertTrue(script.contains("premiumDoNotShowAgain: '다시 보지 않기'"))
        assertTrue(script.contains("premiumDoNotShowAgain: \"Don't show again\""))
        assertTrue(script.contains("confirm: '확인'"))
        assertTrue(script.contains("confirm: 'OK'"))
    }

    @Test
    fun browserReadsOnlyTheActivePhoneProfileAndAppliesItWithoutWebControls() {
        val index = readAsset("index.html")
        val script = readAsset("app.js")

        assertFalse(index.contains("projection-profile"))
        assertFalse(index.contains("apply-profile"))
        assertFalse(index.contains("profile-status"))
        // The endpoint may appear in the post-connect transport allowlist, but the browser must
        // still derive the active profile from /api/status rather than expose or call a web
        // profile control.
        assertFalse(script.contains("api('/api/projection/profile'"))
        assertFalse(script.contains("api(`/api/projection/profile"))
        assertFalse(script.contains("requestedProfile"))
        assertFalse(script.contains("availableProfiles"))

        assertTrue(script.contains("data.projection.activeProfile"))
        assertTrue(script.contains("normalizeProjectionProfile(data.projection.activeProfile)"))
        assertTrue(script.contains("applyProjectionGeometry(activeProfile)"))
        assertTrue(script.contains("pad.style.setProperty("))
        assertTrue(script.contains("'--projection-aspect-ratio'"))
        assertTrue(script.contains("function syncProjectionContentLayout()"))
        assertTrue(script.contains("projectionContent.getBoundingClientRect()"))
        assertTrue(script.contains("projectionProfileRevision += 1"))
        assertTrue(script.contains("webRtcCapabilitiesPromise = null"))
        assertTrue(script.contains("if (webRtcPeer || webRtcStarting) resetWebRtc(true)"))

        assertTrue(script.contains("width: value.width"))
        assertTrue(script.contains("height: value.height"))
        assertTrue(script.contains("webRtcFramesPerSecond: value.webRtcFramesPerSecond"))
        assertTrue(script.contains("sourceAspectWidth: value.sourceAspectWidth"))
        assertTrue(script.contains("sourceAspectHeight: value.sourceAspectHeight"))
        assertTrue(script.contains("projectionProfileRevision === requestedProjectionRevision"))
    }

    @Test
    fun browserStatusRecoveryFlagIsConsumedOnlyByTheSuccessfulPollPath() {
        val script = readAsset("app.js")
        val helperStart = script.indexOf("function consumeRecoveredStatusFailure()")
        val helperEnd = script.indexOf("function handleStatusPollFailure()", helperStart)
        assertTrue(helperStart >= 0 && helperEnd > helperStart)
        val helper = script.substring(helperStart, helperEnd)
        val declaration = helper.indexOf("const recovered = statusFailureCount > 0;")
        val reset = helper.indexOf("statusFailureCount = 0;", declaration)
        assertTrue(declaration >= 0)
        assertTrue(reset > declaration)

        val pollStart = script.indexOf("async function performStatusPoll(options, task)")
        val pollEnd = script.indexOf("function invalidateCredential(message)", pollStart)
        assertTrue(pollStart >= 0 && pollEnd > pollStart)

        val pollScript = script.substring(pollStart, pollEnd)
        val consume = pollScript.indexOf(
            "const recoveredFromStatusFailure = consumeRecoveredStatusFailure();",
        )
        val use = pollScript.indexOf(
            "if (recoveredFromStatusFailure) requestViewportControlReclaim();",
            consume,
        )
        assertTrue(consume >= 0)
        assertTrue(use > consume)

        val pollingStart = script.indexOf("function startStatusPolling()")
        val pollingEnd = script.indexOf("function scheduleStatusPoll()", pollingStart)
        assertTrue(pollingStart >= 0 && pollingEnd > pollingStart)
        assertFalse(
            script.substring(pollingStart, pollingEnd)
                .contains("recoveredFromStatusFailure"),
        )
    }

    @Test
    fun browserWebRtcKeepsNativeCodecNegotiationAndRtxDependencies() {
        val script = readAsset("app.js")

        assertTrue(script.contains("const CODEC_NAMES = ['h264', 'vp8', 'vp9', 'av1'];"))
        assertTrue(script.contains("addTransceiver('video', {direction: 'recvonly'})"))
        assertFalse(script.contains("setCodecPreferences"))
        assertFalse(script.contains("preferredPayloadType"))
    }

    @Test
    fun browserWebRtcRecoveryUsesOneBackoffOwnerAndPublishesAddressFreeIceCounters() {
        val script = readAsset("app.js")

        assertFalse(script.contains("queueMicrotask(() => startWebRtc())"))
        assertTrue(script.contains("await awaitWebRtcOfferAdmission(requestedCredential)"))
        assertTrue(
            script.contains(
                "const statusCurrent = await pollStatus({automatic: true, webRtcPreflight: true});",
            ),
        )
        assertTrue(script.contains("await publishIceFailure(peer, generation, startupStage, failureReason)"))
        assertTrue(script.contains("NAVONWEB_ICE_FAILED stage=${'$'}{stage} reason=${'$'}{reason}"))
        assertTrue(script.contains("pad.dataset.navonwebIceRequestsSent = String(requestsSent)"))
        assertTrue(script.contains("pad.dataset.navonwebIceResponsesReceived = String(responsesReceived)"))
        assertTrue(script.contains("pad.dataset.navonwebIceRequestsReceived = String(requestsReceived)"))
        assertTrue(script.contains("pad.dataset.navonwebIceResponsesSent = String(responsesSent)"))

        val failureStart = script.indexOf("async function publishIceFailure")
        val failureEnd = script.indexOf("function waitForIceGatheringComplete", failureStart)
        assertTrue(failureStart >= 0 && failureEnd > failureStart)
        val failureScript = script.substring(failureStart, failureEnd)
        assertFalse(failureScript.contains(".address"))
        assertFalse(failureScript.contains(".ip"))
        assertFalse(failureScript.contains(".port"))
    }

    @Test
    fun cloudWebRtcTreatsLocalNetworkPermissionAsAdvisory() {
        val index = readAsset("index.html")
        val script = readAsset("app.js")

        assertTrue(index.contains("id=\"local-network-panel\""))
        assertTrue(index.contains("id=\"local-network-message\""))
        assertTrue(index.contains("id=\"local-network-allow\" type=\"button\""))
        assertTrue(index.contains("data-i18n=\"localNetworkPrompt\""))
        assertTrue(index.contains("data-i18n=\"localNetworkAllow\""))
        assertTrue(
            index.contains(
                """#viewer:fullscreen #local-network-panel,
    body.theater-mode #local-network-panel {
      display: none !important;
    }""",
            ),
        )

        listOf(
            "localNetworkPrompt:",
            "localNetworkDenied:",
            "localNetworkAllow:",
            "localNetworkRetry:",
        ).forEach { marker ->
            assertEquals("missing one translation per locale: $marker", 2, Regex(marker).findAll(script).count())
        }
        assertTrue(script.contains("navigator.permissions.query({name: 'local-network'})"))
        assertTrue(script.contains("navigator.permissions.query({name: 'local-network-access'})"))
        assertTrue(script.contains("pad.dataset.navonwebLocalNetworkPermission"))
        assertTrue(script.contains("startWebRtc(true)"))

        assertFalse(script.contains("localNetworkPermissionAllowsWebRtc"))

        val panelStart = script.indexOf("function syncLocalNetworkPermissionPanel")
        val panelEnd = script.indexOf("function syncMediaPermissionPanel", panelStart)
        assertTrue(panelStart >= 0 && panelEnd > panelStart)
        val panelScript = script.substring(panelStart, panelEnd)
        assertTrue(panelScript.contains("localNetworkPermissionState === 'denied'"))
        assertFalse(panelScript.contains("localNetworkPermissionState === 'prompt' ||"))

        val changeStart = script.indexOf("function handleLocalNetworkPermissionChange")
        val changeEnd = script.indexOf("function refreshLocalNetworkPermission", changeStart)
        assertTrue(changeStart >= 0 && changeEnd > changeStart)
        val changeScript = script.substring(changeStart, changeEnd)
        assertFalse(changeScript.contains("resetWebRtc"))
        assertTrue(changeScript.contains("scheduleWebRtcRecovery"))

        val requestStart = script.indexOf("async function requestLocalNetworkAccess")
        val requestEnd = script.indexOf("function showAuthenticatedView", requestStart)
        assertTrue(requestStart >= 0 && requestEnd > requestStart)
        val requestScript = script.substring(requestStart, requestEnd)
        assertTrue(requestScript.contains("startWebRtc(true)"))
        assertFalse(requestScript.contains("localNetworkPermissionState === 'denied'"))

        val recoveryStart = script.indexOf("function scheduleWebRtcRecovery")
        val recoveryEnd = script.indexOf("async function ensureWebRtcCapabilities", recoveryStart)
        assertTrue(recoveryStart >= 0 && recoveryEnd > recoveryStart)
        assertFalse(script.substring(recoveryStart, recoveryEnd).contains("localNetworkPermissionState"))

        val start = script.indexOf("async function startWebRtc")
        val end = script.indexOf("function renderAndroidAutoStatus", start)
        assertTrue(start >= 0 && end > start)
        assertFalse(script.substring(start, end).contains("localNetworkPermissionState"))

        assertTrue(script.contains("addEventListener('icecandidateerror'"))
        assertTrue(script.contains("NAVONWEB_ICE_CANDIDATE_ERROR"))
    }

    @Test
    fun cloudflarePagesAllowsLocalButNotLoopbackNetworkAccess() {
        val expectedPolicy =
            "Permissions-Policy: microphone=(self), camera=(), geolocation=(), " +
                "local-network=(self), loopback-network=()"
        val templateFile = listOf(
            File("cloudflare/pages/_headers.template"),
            File("../cloudflare/pages/_headers.template"),
        ).firstOrNull(File::isFile)
        assumeTrue(
            "Cloudflare deployment configuration is maintained outside the Android source distribution",
            templateFile != null,
        )
        val template = requireNotNull(templateFile).readText(Charsets.UTF_8)
        val policy = template.lineSequence()
            .map(String::trim)
            .single { it.startsWith("Permissions-Policy:") }

        assertEquals(expectedPolicy, policy)
        assertFalse(policy.contains("local-network-access"))
        assertEquals(
            "Content-Security-Policy: default-src 'self'; script-src 'self'; " +
                "style-src 'self' 'unsafe-inline'; " +
                "connect-src 'self' __SIGNALING_HTTP_ORIGIN__ __SIGNALING_WEBSOCKET_ORIGIN__; " +
                "media-src 'self' blob:; img-src 'self' data: blob:; " +
                "object-src 'none'; base-uri 'none'; frame-ancestors 'none'; " +
                "form-action 'self'",
            template.lineSequence()
                .map(String::trim)
                .single { it.startsWith("Content-Security-Policy:") },
        )
    }

    @Test
    fun browserMicrophoneCaptureIsSecureAuthenticatedBoundedAndFailClosed() {
        val index = readAsset("index.html")
        val script = readAsset("app.js")
        val microphoneStart = script.indexOf("async function prepareMicrophoneCapture()")
        val microphoneEnd = script.indexOf("function ensureAudioContext()", microphoneStart)

        assertTrue(microphoneStart >= 0 && microphoneEnd > microphoneStart)
        val microphoneScript = script.substring(microphoneStart, microphoneEnd)
        val secureContextCheck = microphoneScript.indexOf("if (!window.isSecureContext)")
        val permissionCheck = microphoneScript.indexOf(
            "navigator.permissions.query({name: 'microphone'})",
        )
        val mediaRequest = microphoneScript.indexOf("navigator.mediaDevices.getUserMedia({")
        assertTrue(secureContextCheck >= 0)
        assertTrue(permissionCheck > secureContextCheck)
        assertTrue(mediaRequest > permissionCheck)
        assertTrue(microphoneScript.contains("echoCancellation: true"))
        assertTrue(microphoneScript.contains("noiseSuppression: true"))
        assertTrue(microphoneScript.contains("autoGainControl: true"))
        assertTrue(microphoneScript.contains("channelCount: {ideal: 1}"))
        assertTrue(microphoneScript.contains("video: false"))
        assertFalse(microphoneScript.contains("streamState"))

        assertTrue(script.contains("const MICROPHONE_SCRIPT_BUFFER_SIZE = 2048"))
        assertTrue(script.contains("const MICROPHONE_MAX_RAW_BYTES = 32 * 1024"))
        assertTrue(script.contains("const MICROPHONE_MAX_QUEUED_CHUNKS = 8"))
        assertTrue(script.contains("const MICROPHONE_UPLOAD_TIMEOUT_MILLIS = 5000"))
        assertTrue(script.contains("const MICROPHONE_RECOVERY_BASE_DELAY_MILLIS = 1000"))
        assertTrue(script.contains("const MICROPHONE_RECOVERY_MAX_DELAY_MILLIS = 10000"))
        assertTrue(script.contains("const MICROPHONE_READY_HEARTBEAT_INTERVAL_MILLIS = 5000"))
        assertTrue(script.contains("const MICROPHONE_IDLE_HEARTBEAT_BYTES = new Uint8Array([0, 0])"))
        assertTrue(script.contains("const MICROPHONE_FALLBACK_SAMPLE_RATE_HZ = 48000"))
        assertTrue(script.contains("new MicrophoneAudioContextClass({latencyHint: 'interactive'})"))
        assertTrue(script.contains("createScriptProcessor(MICROPHONE_SCRIPT_BUFFER_SIZE, 1, 1)"))
        assertTrue(script.contains("mono / channelCount"))
        assertTrue(script.contains("view.setInt16(frameIndex * 2, sample, true)"))
        assertTrue(script.contains("window.btoa(binary)"))
        assertTrue(script.contains("if (!microphoneCaptureRequested) return;"))
        assertTrue(script.contains("MICROPHONE_IDLE_HEARTBEAT_BYTES"))
        assertTrue(script.contains("function enqueueMicrophoneReadyHeartbeat()"))
        assertTrue(script.contains("function scheduleMicrophoneReadyHeartbeat()"))
        assertTrue(script.contains("let microphonePermissionPrimed = false"))
        assertTrue(script.contains("microphoneInputSampleRateHz"))
        val idleRelease = microphoneScript.indexOf("if (!microphoneCaptureRequested) {")
        val captureContext = microphoneScript.indexOf(
            "new MicrophoneAudioContextClass({latencyHint: 'interactive'})",
        )
        assertTrue(idleRelease >= 0 && captureContext > idleRelease)
        assertTrue(microphoneScript.contains("for (const mediaTrack of stream.getTracks()) mediaTrack.stop();"))
        assertTrue(microphoneScript.contains("updateMicrophoneState('ready')"))
        assertTrue(script.contains("stopMicrophoneCapture('ready')"))
        assertTrue(script.contains("await response.body.cancel().catch(() => null)"))
        assertTrue(script.contains("updateMicrophoneCaptureRequest(data.microphone)"))

        assertTrue(script.contains("microphoneQueue.length >= MICROPHONE_MAX_QUEUED_CHUNKS"))
        assertTrue(script.contains("microphoneQueue.shift()"))
        assertTrue(script.contains("await api(MICROPHONE_ENDPOINT"))
        assertTrue(script.contains("'Content-Type': 'text/plain;charset=UTF-8'"))
        assertTrue(script.contains("'X-Audio-Codec': 'pcm-s16le'"))
        assertTrue(script.contains("'X-Audio-Sample-Rate': String(chunk.sampleRate)"))
        assertTrue(script.contains("'X-Audio-Channels': '1'"))
        assertTrue(script.contains("if (response.status === 401)"))
        assertTrue(script.contains("stopMicrophoneCapture('credential_invalid')"))
        assertTrue(script.contains("stopMicrophoneCapture('track_ended')"))
        assertTrue(script.contains("stopMicrophoneCapture('permission_denied')"))
        assertTrue(script.contains("permissionStatus.state === 'granted'"))
        assertTrue(script.contains("microphoneState === 'permission_denied'"))
        assertTrue(script.contains("updateMicrophoneState('permission_changed')"))
        assertTrue(script.contains("microphoneState = nextState"))
        assertTrue(script.contains("pad.dataset.navonwebMicrophoneState = nextState"))
        assertTrue(script.contains("stopMicrophoneCapture('visibility_hidden')"))
        assertTrue(script.contains("stopMicrophoneCapture('offline')"))
        assertTrue(script.contains("stopMicrophoneCapture('pagehide')"))
        assertTrue(script.contains("!androidAutoInteractive"))
        assertTrue(script.contains("controller.abort()"))
        assertTrue(script.contains("scheduleMicrophoneRecovery()"))
        assertTrue(script.contains("stopMicrophoneCapture('android_auto_unavailable')"))
        assertTrue(script.contains("ensureMicrophoneCapture()"))
        assertTrue(script.contains("renderAndroidAutoStatus(data.androidAuto, data.projection)"))

        assertFalse(index.contains("id=\"microphone\""))
        assertFalse(index.contains("id=\"microphone-state\""))
        assertFalse(index.contains("id=\"microphone-control\""))
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

    private fun readProjectFile(path: String): String {
        val candidates = listOf(File(path), File("../$path"))
        val file = candidates.firstOrNull(File::isFile)
            ?: error("missing project file $path")
        return file.readText(Charsets.UTF_8)
    }
}
