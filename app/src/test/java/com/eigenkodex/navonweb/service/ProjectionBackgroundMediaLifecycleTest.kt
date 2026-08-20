package com.eigenkodex.navonweb.service

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionBackgroundMediaLifecycleTest {
    @Test
    fun `activity background only releases its preview binding`() {
        val activity = readProjectFile(
            "app/src/main/java/com/eigenkodex/navonweb/MainActivity.kt",
        )
        val onStop = functionBody(activity, "override fun onStop()", "override fun onDestroy()")

        assertTrue(onStop.contains("detachProjectionSurface(lease.generation)"))
        assertTrue(onStop.contains("unbindService(projectionServiceConnection)"))
        assertFalse(onStop.contains("ProjectionService.stop("))
        assertFalse(onStop.contains("stopService("))
    }

    @Test
    fun `service unbind keeps the foreground projection session alive`() {
        val service = readProjectFile(
            "app/src/main/java/com/eigenkodex/navonweb/service/ProjectionService.kt",
        )
        val onUnbind = functionBody(
            service,
            "override fun onUnbind(intent: Intent?): Boolean",
            "override fun onStartCommand(",
        )

        assertTrue(onUnbind.contains("detachCurrentProjectionSurface()"))
        assertFalse(onUnbind.contains("releaseResources()"))
        assertFalse(onUnbind.contains("stopSelf()"))
        assertFalse(onUnbind.contains("server?.close()"))
    }

    @Test
    fun `AA decoder always binds one service-owned surface`() {
        val service = readProjectFile(
            "app/src/main/java/com/eigenkodex/navonweb/service/ProjectionService.kt",
        )
        val launch = functionBody(
            service,
            "private fun launchProjection(",
            "private fun safeProjectionFailure(",
        )
        val detach = functionBody(
            service,
            "private fun detachProjectionSurface(generation: Long)",
            "private fun detachCurrentProjectionSurface()",
        )

        assertTrue(launch.contains("val nativeDecoderSurface = decoderController"))
        assertTrue(launch.contains("val fallbackDecoderSurface = fallbackCapture"))
        assertTrue(launch.contains("nativeDecoderSurface ?: fallbackDecoderSurface"))
        assertTrue(launch.contains("decoderSurface.takeIf(Surface::isValid)"))
        assertFalse(launch.contains("::currentProjectionSurface"))
        assertFalse(launch.contains("projectionSurface?.takeIf"))
        assertTrue(detach.contains("fallbackDecoderSurfaceValid = projectionDecoderFrameCapture"))
        assertTrue(detach.contains("browserFrameCapture?.detach(generation)"))
        assertFalse(detach.contains("browserFrameStore?.clear()"))
    }

    @Test
    fun `fallback texture is continuously drained and JPEG work is subscriber gated`() {
        val capture = readProjectFile(
            "app/src/main/java/com/eigenkodex/navonweb/browser/BrowserSurfaceFrameCapture.kt",
        )
        val onFrame = functionBody(
            capture,
            "override fun onFrame(frame: VideoFrame)",
            "override fun close()",
        )

        assertTrue(capture.contains("SurfaceTextureHelper.create("))
        assertTrue(capture.contains("textureHelper.startListening(this)"))
        assertTrue(capture.contains("val decoderSurface: Surface"))
        assertFalse(capture.contains("PixelCopy"))
        assertTrue(onFrame.contains("frameStore.activeSubscriberCount > 0"))
        assertTrue(onFrame.contains("if (queueForJpeg) frame.retain()"))
        assertTrue(capture.contains("frame.buffer.toI420()"))
        assertTrue(capture.contains("pendingFrame?.release()"))
        assertTrue(capture.contains("copyI420ToNv21("))
    }

    @Test
    fun `HTTP and WebRTC consumers receive the same Activity-independent frame stream`() {
        val server = readProjectFile(
            "app/src/main/java/com/eigenkodex/navonweb/browser/BrowserProbeServer.kt",
        )
        val frameEndpoint = functionBody(
            server,
            "private fun serveFrame(request: HttpRequest, output: OutputStream)",
            "private fun serveAudio(",
        )
        val bridge = readProjectFile(
            "app/src/main/java/com/eigenkodex/navonweb/browser/webrtc/WebRtcTextureVideoBridge.kt",
        )
        val onTextureFrame = functionBody(
            bridge,
            "private fun onTextureFrame(frame: VideoFrame)",
            "override fun close()",
        )

        assertTrue(frameEndpoint.contains("frameStore.subscribe()"))
        assertTrue(frameEndpoint.contains("frameStore.awaitNext("))
        assertFalse(frameEndpoint.contains("Activity"))
        assertTrue(onTextureFrame.contains("capturerObserver.onFrameCaptured(frame)"))
        assertTrue(onTextureFrame.contains("previewBinding.dispatch"))
        assertTrue(onTextureFrame.contains("auxiliarySink?.onFrame(frame)"))
        assertTrue(
            onTextureFrame.indexOf("capturerObserver.onFrameCaptured(frame)") <
                onTextureFrame.indexOf("auxiliarySink?.onFrame(frame)"),
        )
    }

    @Test
    fun `preview attachment is generation idempotent and releases before reconnect`() {
        val bridge = readProjectFile(
            "app/src/main/java/com/eigenkodex/navonweb/browser/webrtc/" +
                "WebRtcTextureVideoBridge.kt",
        )
        val capture = readProjectFile(
            "app/src/main/java/com/eigenkodex/navonweb/browser/BrowserSurfaceFrameCapture.kt",
        )
        val binding = readProjectFile(
            "app/src/main/java/com/eigenkodex/navonweb/browser/PreviewRendererBinding.kt",
        )
        val service = readProjectFile(
            "app/src/main/java/com/eigenkodex/navonweb/service/ProjectionService.kt",
        )
        val attach = functionBody(binding, "fun attach(", "/** Exact Activity detach")

        assertTrue(attach.contains("requestedGeneration == generation"))
        assertTrue(attach.contains("sameTarget(boundTarget, requestedTarget)"))
        assertTrue(attach.indexOf("previous?.let(releaseRenderer)") < attach.indexOf("createRenderer("))
        listOf(bridge, capture).forEach { producer ->
            assertTrue(producer.contains("previewBinding.attach("))
            assertTrue(producer.contains("createInitializedPreviewRenderer("))
        }
        assertTrue(service.contains("attachPreviewSurface(target.first, target.second)"))
        assertTrue(service.contains("detachPreviewSurface(generation)"))
        assertTrue(service.contains("browserFrameCapture?.detach()"))
        assertTrue(service.contains("webRtcController?.detachPreviewSurface()"))
    }

    @Test
    fun `no encoder keeps decoder bridge but rejects WebRTC sessions`() {
        val controller = readProjectFile(
            "app/src/main/java/com/eigenkodex/navonweb/browser/webrtc/" +
                "WebRtcProjectionController.kt",
        )
        val start = functionBody(
            controller,
            "fun start(): Result<Unit>",
            "/** MediaCodec must render Android Auto frames into this Surface",
        )
        val capabilities = functionBody(
            controller,
            "override fun capabilities(): BrowserWebRtcCapabilities",
            "override fun openSession(",
        )
        val open = functionBody(
            controller,
            "override fun openSession(",
            "override fun session(",
        )

        assertTrue(start.contains("resources = CoreResources("))
        assertFalse(start.contains("check(orderedAvailableCodecs.isNotEmpty())"))
        assertTrue(capabilities.contains("available = current.availableCodecs.isNotEmpty()"))
        assertTrue(open.contains("if (readyResources.availableCodecs.isEmpty())"))
        assertTrue(open.contains("Native WebRTC video encoder is unavailable"))
    }

    @Test
    fun `reconfiguration and service close retire runtime before decoder owners`() {
        val service = readProjectFile(
            "app/src/main/java/com/eigenkodex/navonweb/service/ProjectionService.kt",
        )
        val profileApply = functionBody(
            service,
            "private suspend fun applyProjectionProfileLocked(",
            "private fun scheduleProjectionViewportApply(",
        )
        val viewportApply = functionBody(
            service,
            "private suspend fun applyProjectionViewportLocked(",
            "private fun launchProjection(",
        )
        val release = functionBody(
            service,
            "private fun releaseResources()",
            "override fun onDestroy()",
        )

        assertTrue(
            profileApply.indexOf("previousRuntime?.close()") <
                profileApply.indexOf("replaceBrowserFrameCapture("),
        )
        assertTrue(
            viewportApply.indexOf("previousRuntime?.close()") <
                viewportApply.indexOf("replaceBrowserFrameCapture("),
        )
        assertTrue(release.indexOf("runtime?.close()") < release.indexOf("activeWebRtcController?.close()"))
        assertTrue(release.indexOf("runtime?.close()") < release.indexOf("browserFrameCapture?.close()"))
    }

    @Test
    fun `rejected viewport encoder replacement closes its service-owned candidate`() {
        val service = readProjectFile(
            "app/src/main/java/com/eigenkodex/navonweb/service/ProjectionService.kt",
        )
        val viewportApply = functionBody(
            service,
            "private suspend fun applyProjectionViewportLocked(",
            "private fun launchProjection(",
        )
        val policyCheck = viewportApply.indexOf(
            "!ProjectionViewportTransportReplacementPolicy.canCommit(",
        )
        val rejectedReturn = viewportApply.indexOf("return false", policyCheck)
        val candidateClose = viewportApply.indexOf("replacementController?.close()", policyCheck)

        assertTrue(policyCheck >= 0)
        assertTrue(rejectedReturn > policyCheck)
        assertTrue(candidateClose in (policyCheck + 1) until rejectedReturn)
    }

    @Test
    fun `manifest keeps the started foreground service after task backgrounding`() {
        val manifest = readProjectFile("app/src/main/AndroidManifest.xml")
        val serviceStart = manifest.indexOf("android:name=\".service.ProjectionService\"")
        assertTrue(serviceStart >= 0)
        val serviceEnd = manifest.indexOf("/>", serviceStart)
        assertTrue(serviceEnd > serviceStart)
        val declaration = manifest.substring(serviceStart, serviceEnd)

        assertTrue(declaration.contains("android:foregroundServiceType=\"connectedDevice\""))
        assertTrue(declaration.contains("android:stopWithTask=\"false\""))

        val service = readProjectFile(
            "app/src/main/java/com/eigenkodex/navonweb/service/ProjectionService.kt",
        )
        assertTrue(service.contains("startForeground("))
        assertTrue(service.contains("return START_NOT_STICKY"))
    }

    private fun functionBody(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        val end = source.indexOf(endMarker, start + startMarker.length)
        check(start >= 0) { "missing start marker $startMarker" }
        check(end > start) { "missing end marker $endMarker" }
        return source.substring(start, end)
    }

    private fun readProjectFile(relativePath: String): String {
        val candidates = listOf(
            File(relativePath),
            File(relativePath.removePrefix("app/")),
        )
        return candidates.firstOrNull(File::isFile)?.readText(Charsets.UTF_8)
            ?: error("missing project file $relativePath")
    }
}
