/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto.protocol

import com.pebble.tecomheadunit.openauto.credential.HeadUnitCredential
import com.pebble.tecomheadunit.openauto.credential.HeadUnitCredentialSource
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.security.Principal
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSession
import javax.security.auth.x500.X500Principal
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AasdkTlsEngineTest {
    @Test
    fun `phone peer pins normalize separators and reject malformed values`() {
        val normalized = "AA:".repeat(31) + "AA"
        val policy = AasdkTlsPeerTrustPolicy.PinnedLeafSha256(setOf(normalized))

        assertEquals(setOf("AA".repeat(32)), policy.fingerprints)
        assertThrows(IllegalArgumentException::class.java) {
            AasdkTlsPeerTrustPolicy.PinnedLeafSha256(setOf("not-a-sha256-pin"))
        }
    }

    @Test
    fun `non-exportable identity key is returned directly to SSLEngine key manager`() {
        val key = NonExportablePrivateKey()
        val certificate = StubCertificate()
        val credential = credential(key, certificate, HeadUnitCredentialSource.ANDROID_KEYSTORE)
        val manager = AasdkCredentialKeyManager(credential)

        val alias = manager.chooseEngineClientAlias(arrayOf("RSA"), null, null)

        assertEquals("aasdk_head_unit_identity", alias)
        assertSame(key, manager.getPrivateKey(alias))
        assertNull(key.encoded)
        assertArrayEquals(arrayOf(certificate), manager.getCertificateChain(alias))
        assertNull(manager.chooseEngineClientAlias(arrayOf("EC"), null, null))
    }

    @Test
    fun `release boundary rejects development identity and debug unverified peer`() {
        val development = credential(
            NonExportablePrivateKey(),
            StubCertificate(),
            HeadUnitCredentialSource.DEVELOPMENT_PEM,
        )
        val production = credential(
            NonExportablePrivateKey(),
            StubCertificate(),
            HeadUnitCredentialSource.ANDROID_KEYSTORE,
        )

        assertThrows(AasdkTlsPolicyException::class.java) {
            AasdkTlsEngineFactory.validateBoundary(
                development,
                AasdkTlsBuildBoundary.RELEASE,
                AasdkTlsPeerTrustPolicy.PlatformTrust,
            )
        }
        assertThrows(AasdkTlsPolicyException::class.java) {
            AasdkTlsEngineFactory.validateBoundary(
                production,
                AasdkTlsBuildBoundary.RELEASE,
                AasdkTlsPeerTrustPolicy.DebugOnlyUnverifiedPeer,
            )
        }
        assertThrows(AasdkTlsPolicyException::class.java) {
            AasdkTlsEngineFactory.validateBoundary(
                production,
                AasdkTlsBuildBoundary.RELEASE,
                AasdkTlsPeerTrustPolicy.NotConfigured,
            )
        }
        AasdkTlsEngineFactory.validateBoundary(
            development,
            AasdkTlsBuildBoundary.DEBUG,
            AasdkTlsPeerTrustPolicy.DebugOnlyUnverifiedPeer,
        )
    }

    @Test
    fun `handshake handles delegated task output overflow and input underflow`() {
        val engine = ScriptedEngine()
        val session = AasdkTlsEngineSession(engine)

        val started = session.beginHandshake()

        assertTrue(engine.delegatedTaskRan)
        assertTrue(engine.handshakeWrapOverflowObserved)
        assertEquals(AasdkTlsState.HANDSHAKING, started.state)
        assertTrue(started.needsPeerData)
        assertArrayEquals(ScriptedEngine.CLIENT_HELLO, started.outboundRecords)

        val underflow = session.receiveHandshakeRecords(byteArrayOf(1, 2))
        assertEquals(AasdkTlsState.HANDSHAKING, underflow.state)
        assertTrue(underflow.needsPeerData)
        assertTrue(underflow.outboundRecords.isEmpty())

        val completed = session.receiveHandshakeRecords(byteArrayOf(3, 4))
        assertEquals(AasdkTlsState.ACTIVE, completed.state)
        assertFalse(completed.needsPeerData)
        assertArrayEquals(ScriptedEngine.CLIENT_FINISHED, completed.outboundRecords)
        assertEquals(AasdkTlsState.ACTIVE, session.state)
    }

    @Test
    fun `zero progress need unwrap waits for more peer records without spinning`() {
        val engine = ScriptedEngine(returnZeroProgressNeedUnwrapOnce = true)
        val session = AasdkTlsEngineSession(engine)
        session.beginHandshake()

        val waiting = session.receiveHandshakeRecords(byteArrayOf(1, 2))

        assertTrue(engine.zeroProgressNeedUnwrapObserved)
        assertEquals(AasdkTlsState.HANDSHAKING, waiting.state)
        assertTrue(waiting.needsPeerData)
        assertTrue(waiting.outboundRecords.isEmpty())

        val completed = session.receiveHandshakeRecords(byteArrayOf(3, 4))
        assertEquals(AasdkTlsState.ACTIVE, completed.state)
        assertArrayEquals(ScriptedEngine.CLIENT_FINISHED, completed.outboundRecords)
    }

    @Test
    fun `handshake channel uses plain specific control message id 0003`() {
        val channel = AasdkTlsHandshakeChannel(AasdkTlsEngineSession(ScriptedEngine()))

        val started = channel.begin()
        val frame = AasdkFrameCodec.decodeFrame(started.outboundFrames.single())

        assertEquals(AasdkFrameCodec.CONTROL_CHANNEL, frame.channelId)
        assertFalse(frame.encrypted)
        assertFalse(frame.controlMessage)
        assertArrayEquals(
            byteArrayOf(0x00, 0x03) + ScriptedEngine.CLIENT_HELLO,
            frame.payload,
        )

        val responseFrame = AasdkTlsHandshakeWireCodec.encode(byteArrayOf(1, 2, 3, 4)).single()
        val completed = channel.acceptFrame(responseFrame)
        assertEquals(AasdkTlsState.ACTIVE, completed.state)
        val finalFlight = AasdkFrameCodec.decodeFrame(completed.outboundFrames.single())
        assertArrayEquals(
            byteArrayOf(0x00, 0x03) + ScriptedEngine.CLIENT_FINISHED,
            finalFlight.payload,
        )
    }

    @Test
    fun `application wrap and unwrap recover from overflow and preserve underflow bytes`() {
        val engine = ScriptedEngine()
        val session = activeSession(engine)
        val plaintext = "projection".toByteArray()

        val ciphertext = session.wrapApplicationData(plaintext)
        assertTrue(engine.applicationWrapOverflowObserved)

        val first = session.unwrapApplicationData(ciphertext.copyOfRange(0, 3))
        assertTrue(first.needsMoreNetworkData)
        assertTrue(first.plaintext.isEmpty())

        val second = session.unwrapApplicationData(ciphertext.copyOfRange(3, ciphertext.size))
        assertTrue(engine.applicationUnwrapOverflowObserved)
        assertFalse(second.needsMoreNetworkData)
        assertArrayEquals(plaintext, second.plaintext)
    }

    @Test
    fun `encrypted message layer wraps each plaintext fragment before outer framing`() {
        val session = activeSession(ScriptedEngine())
        val layer = AasdkTlsMessageLayer(session)
        val plaintext = ByteArray(AasdkFrameCodec.MAX_PLAINTEXT_FRAME_PAYLOAD + 1) {
            (it and 0x7F).toByte()
        }

        val encoded = layer.encodeEncryptedMessage(3, controlMessage = false, plaintext)

        assertEquals(2, encoded.size)
        val first = AasdkFrameCodec.decodeFrame(encoded[0])
        val last = AasdkFrameCodec.decodeFrame(encoded[1])
        assertEquals(AasdkFrameType.FIRST, first.type)
        assertEquals(AasdkFrameType.LAST, last.type)
        assertTrue(first.encrypted)
        assertEquals(plaintext.size, first.declaredTotalSize)

        val assembler = AasdkPlainMessageAssembler()
        assertNull(assembler.accept(layer.unwrapEncryptedFrame(first)))
        val complete = assembler.accept(layer.unwrapEncryptedFrame(last))
        assertArrayEquals(plaintext, complete?.payload)
    }

    @Test
    fun `close emits close notify then clears state`() {
        val engine = ScriptedEngine()
        val session = activeSession(engine)

        val closeNotify = session.closeOutbound()

        assertArrayEquals(ScriptedEngine.CLOSE_NOTIFY, closeNotify)
        assertEquals(AasdkTlsState.CLOSING, session.state)
        session.close()
        assertEquals(AasdkTlsState.CLOSED, session.state)
        assertTrue(engine.isInboundDone)
        assertTrue(engine.isOutboundDone)
    }

    private fun activeSession(engine: ScriptedEngine): AasdkTlsEngineSession =
        AasdkTlsEngineSession(engine).also { session ->
            session.beginHandshake()
            session.receiveHandshakeRecords(byteArrayOf(1, 2, 3, 4))
            assertEquals(AasdkTlsState.ACTIVE, session.state)
        }

    private fun credential(
        key: PrivateKey,
        certificate: X509Certificate,
        source: HeadUnitCredentialSource,
    ) = HeadUnitCredential(
        certificateChain = listOf(certificate),
        privateKey = key,
        source = source,
        leafFingerprintSha256 = "00".repeat(32),
    )

    private class NonExportablePrivateKey : PrivateKey {
        override fun getAlgorithm() = "RSA"
        override fun getFormat(): String? = null
        override fun getEncoded(): ByteArray? = null
    }

    @Suppress("DEPRECATION")
    private class StubCertificate : X509Certificate() {
        private val principal = X500Principal("CN=AASDK test")
        private val publicKey = object : PublicKey {
            override fun getAlgorithm() = "RSA"
            override fun getFormat() = "X.509"
            override fun getEncoded() = byteArrayOf(1, 2, 3)
        }

        override fun checkValidity() = Unit
        override fun checkValidity(date: Date?) = Unit
        override fun getVersion() = 3
        override fun getSerialNumber() = BigInteger.ONE
        override fun getIssuerDN(): Principal = principal
        override fun getSubjectDN(): Principal = principal
        override fun getIssuerX500Principal() = principal
        override fun getSubjectX500Principal() = principal
        override fun getNotBefore() = Date(0)
        override fun getNotAfter() = Date(Long.MAX_VALUE)
        override fun getTBSCertificate() = byteArrayOf(1)
        override fun getSignature() = byteArrayOf(1)
        override fun getSigAlgName() = "SHA256withRSA"
        override fun getSigAlgOID() = "1.2.840.113549.1.1.11"
        override fun getSigAlgParams(): ByteArray? = null
        override fun getIssuerUniqueID(): BooleanArray? = null
        override fun getSubjectUniqueID(): BooleanArray? = null
        override fun getKeyUsage(): BooleanArray? = null
        override fun getBasicConstraints() = -1
        override fun getEncoded() = byteArrayOf(1, 2, 3)
        override fun verify(key: PublicKey?) = Unit
        override fun verify(key: PublicKey?, sigProvider: String?) = Unit
        override fun toString() = "StubCertificate"
        override fun getPublicKey() = publicKey
        override fun getCriticalExtensionOIDs(): MutableSet<String>? = null
        override fun getNonCriticalExtensionOIDs(): MutableSet<String>? = null
        override fun getExtensionValue(oid: String?): ByteArray? = null
        override fun hasUnsupportedCriticalExtension() = false
    }

    private class ScriptedEngine(
        private val returnZeroProgressNeedUnwrapOnce: Boolean = false,
    ) : SSLEngine() {
        private enum class Phase {
            NEW,
            TASK,
            HANDSHAKE_WRAP,
            HANDSHAKE_UNWRAP,
            HANDSHAKE_FINAL_WRAP,
            ACTIVE,
            CLOSING,
            CLOSED,
        }

        private var phase = Phase.NEW
        private var taskAvailable = false
        private var clientMode = false
        private var needClientAuth = false
        private var wantClientAuth = false
        private var sessionCreation = true
        private var inboundDone = false
        private var outboundDone = false
        private var enabledSuites = arrayOf("TLS_FAKE_WITH_AES_128_GCM_SHA256")
        private var enabledProtocols = arrayOf("TLSv1.2")
        var delegatedTaskRan = false
            private set
        var handshakeWrapOverflowObserved = false
            private set
        var applicationWrapOverflowObserved = false
            private set
        var applicationUnwrapOverflowObserved = false
            private set
        var zeroProgressNeedUnwrapObserved = false
            private set

        override fun beginHandshake() {
            if (phase != Phase.NEW) throw SSLException("already started")
            phase = Phase.TASK
            taskAvailable = true
        }

        override fun getHandshakeStatus(): SSLEngineResult.HandshakeStatus = when (phase) {
            Phase.TASK -> SSLEngineResult.HandshakeStatus.NEED_TASK
            Phase.HANDSHAKE_WRAP, Phase.HANDSHAKE_FINAL_WRAP, Phase.CLOSING ->
                SSLEngineResult.HandshakeStatus.NEED_WRAP
            Phase.HANDSHAKE_UNWRAP -> SSLEngineResult.HandshakeStatus.NEED_UNWRAP
            else -> SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
        }

        override fun getDelegatedTask(): Runnable? {
            if (phase != Phase.TASK || !taskAvailable) return null
            taskAvailable = false
            return Runnable {
                delegatedTaskRan = true
                phase = Phase.HANDSHAKE_WRAP
            }
        }

        override fun wrap(source: ByteBuffer, destination: ByteBuffer): SSLEngineResult = when (phase) {
            Phase.HANDSHAKE_WRAP -> wrapHandshake(destination)
            Phase.HANDSHAKE_FINAL_WRAP -> wrapHandshakeFinished(destination)
            Phase.ACTIVE -> wrapApplication(source, destination)
            Phase.CLOSING -> wrapClose(destination)
            Phase.CLOSED -> result(SSLEngineResult.Status.CLOSED, 0, 0)
            else -> throw SSLException("wrap in $phase")
        }

        override fun unwrap(source: ByteBuffer, destination: ByteBuffer): SSLEngineResult = when (phase) {
            Phase.HANDSHAKE_UNWRAP -> unwrapHandshake(source)
            Phase.ACTIVE -> unwrapApplication(source, destination)
            else -> throw SSLException("unwrap in $phase")
        }

        private fun wrapHandshake(destination: ByteBuffer): SSLEngineResult {
            if (!handshakeWrapOverflowObserved) {
                handshakeWrapOverflowObserved = true
                return result(SSLEngineResult.Status.BUFFER_OVERFLOW, 0, 0)
            }
            if (destination.remaining() < CLIENT_HELLO.size) {
                return result(SSLEngineResult.Status.BUFFER_OVERFLOW, 0, 0)
            }
            destination.put(CLIENT_HELLO)
            phase = Phase.HANDSHAKE_UNWRAP
            return result(
                SSLEngineResult.Status.OK,
                0,
                CLIENT_HELLO.size,
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP,
            )
        }

        private fun unwrapHandshake(source: ByteBuffer): SSLEngineResult {
            if (
                returnZeroProgressNeedUnwrapOnce &&
                !zeroProgressNeedUnwrapObserved &&
                source.hasRemaining()
            ) {
                zeroProgressNeedUnwrapObserved = true
                return result(
                    SSLEngineResult.Status.OK,
                    0,
                    0,
                    SSLEngineResult.HandshakeStatus.NEED_UNWRAP,
                )
            }
            if (source.remaining() < SERVER_HELLO_SIZE) {
                return result(SSLEngineResult.Status.BUFFER_UNDERFLOW, 0, 0)
            }
            source.position(source.position() + SERVER_HELLO_SIZE)
            phase = Phase.HANDSHAKE_FINAL_WRAP
            return result(
                SSLEngineResult.Status.OK,
                SERVER_HELLO_SIZE,
                0,
                SSLEngineResult.HandshakeStatus.NEED_WRAP,
            )
        }

        private fun wrapHandshakeFinished(destination: ByteBuffer): SSLEngineResult {
            if (destination.remaining() < CLIENT_FINISHED.size) {
                return result(SSLEngineResult.Status.BUFFER_OVERFLOW, 0, 0)
            }
            destination.put(CLIENT_FINISHED)
            phase = Phase.ACTIVE
            return result(
                SSLEngineResult.Status.OK,
                0,
                CLIENT_FINISHED.size,
                SSLEngineResult.HandshakeStatus.FINISHED,
            )
        }

        private fun wrapApplication(source: ByteBuffer, destination: ByteBuffer): SSLEngineResult {
            val size = source.remaining()
            val required = size + 2
            if (!applicationWrapOverflowObserved) {
                applicationWrapOverflowObserved = true
                return result(SSLEngineResult.Status.BUFFER_OVERFLOW, 0, 0)
            }
            if (destination.remaining() < required) {
                return result(SSLEngineResult.Status.BUFFER_OVERFLOW, 0, 0)
            }
            destination.putShort(size.toShort())
            repeat(size) { destination.put((source.get().toInt() xor 0x5A).toByte()) }
            return result(SSLEngineResult.Status.OK, size, required)
        }

        private fun unwrapApplication(source: ByteBuffer, destination: ByteBuffer): SSLEngineResult {
            if (source.remaining() < 2) return result(SSLEngineResult.Status.BUFFER_UNDERFLOW, 0, 0)
            val position = source.position()
            val size = source.getShort(position).toInt() and 0xFFFF
            if (source.remaining() < size + 2) {
                return result(SSLEngineResult.Status.BUFFER_UNDERFLOW, 0, 0)
            }
            if (!applicationUnwrapOverflowObserved) {
                applicationUnwrapOverflowObserved = true
                return result(SSLEngineResult.Status.BUFFER_OVERFLOW, 0, 0)
            }
            if (destination.remaining() < size) {
                return result(SSLEngineResult.Status.BUFFER_OVERFLOW, 0, 0)
            }
            source.position(position + 2)
            repeat(size) { destination.put((source.get().toInt() xor 0x5A).toByte()) }
            return result(SSLEngineResult.Status.OK, size + 2, size)
        }

        private fun wrapClose(destination: ByteBuffer): SSLEngineResult {
            if (destination.remaining() < CLOSE_NOTIFY.size) {
                return result(SSLEngineResult.Status.BUFFER_OVERFLOW, 0, 0)
            }
            destination.put(CLOSE_NOTIFY)
            outboundDone = true
            phase = Phase.CLOSED
            return result(SSLEngineResult.Status.CLOSED, 0, CLOSE_NOTIFY.size)
        }

        override fun wrap(
            sources: Array<out ByteBuffer>,
            offset: Int,
            length: Int,
            destination: ByteBuffer,
        ): SSLEngineResult {
            require(length == 1)
            return wrap(sources[offset], destination)
        }

        override fun unwrap(
            source: ByteBuffer,
            destinations: Array<out ByteBuffer>,
            offset: Int,
            length: Int,
        ): SSLEngineResult {
            require(length == 1)
            return unwrap(source, destinations[offset])
        }

        override fun closeInbound() {
            inboundDone = true
            if (outboundDone) phase = Phase.CLOSED
        }

        override fun isInboundDone() = inboundDone

        override fun closeOutbound() {
            if (!outboundDone && phase != Phase.NEW) phase = Phase.CLOSING
            if (phase == Phase.NEW) {
                outboundDone = true
                phase = Phase.CLOSED
            }
        }

        override fun isOutboundDone() = outboundDone
        override fun getSupportedCipherSuites() = arrayOf("TLS_FAKE_WITH_AES_128_GCM_SHA256")
        override fun getEnabledCipherSuites() = enabledSuites.clone()
        override fun setEnabledCipherSuites(suites: Array<out String>?) {
            enabledSuites = suites?.map { it }?.toTypedArray() ?: emptyArray()
        }
        override fun getSupportedProtocols() = arrayOf("TLSv1.2")
        override fun getEnabledProtocols() = enabledProtocols.clone()
        override fun setEnabledProtocols(protocols: Array<out String>?) {
            enabledProtocols = protocols?.map { it }?.toTypedArray() ?: emptyArray()
        }
        override fun getSession(): SSLSession = SSLContext.getDefault().createSSLEngine().session
        override fun setUseClientMode(mode: Boolean) { clientMode = mode }
        override fun getUseClientMode() = clientMode
        override fun setNeedClientAuth(need: Boolean) { needClientAuth = need }
        override fun getNeedClientAuth() = needClientAuth
        override fun setWantClientAuth(want: Boolean) { wantClientAuth = want }
        override fun getWantClientAuth() = wantClientAuth
        override fun setEnableSessionCreation(flag: Boolean) { sessionCreation = flag }
        override fun getEnableSessionCreation() = sessionCreation

        private fun result(
            status: SSLEngineResult.Status,
            consumed: Int,
            produced: Int,
            handshake: SSLEngineResult.HandshakeStatus = handshakeStatus,
        ) = SSLEngineResult(status, handshake, consumed, produced)

        companion object {
            val CLIENT_HELLO = byteArrayOf(0x16, 0x03, 0x03, 0x00, 0x01)
            val CLIENT_FINISHED = byteArrayOf(0x14, 0x03, 0x03, 0x00, 0x01)
            val CLOSE_NOTIFY = byteArrayOf(0x15, 0x00)
            const val SERVER_HELLO_SIZE = 4
        }
    }
}
