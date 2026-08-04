/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.diagnostics.upload

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal data class DiagnosticHttpRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray,
)

internal data class DiagnosticHttpResponse(
    val statusCode: Int,
    val body: ByteArray,
)

internal fun interface DiagnosticHttpTransport {
    @Throws(IOException::class)
    fun execute(request: DiagnosticHttpRequest): DiagnosticHttpResponse
}

internal class UrlConnectionDiagnosticHttpTransport(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 30_000,
) : DiagnosticHttpTransport {
    override fun execute(request: DiagnosticHttpRequest): DiagnosticHttpResponse {
        val endpoint = URL(request.url)
        require(endpoint.protocol.equals("https", ignoreCase = true)) { "HTTPS is required" }
        val connection = endpoint.openConnection() as? HttpsURLConnection
            ?: throw IOException("HTTPS connection unavailable")
        return try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.doInput = true
            connection.doOutput = true
            request.headers.forEach(connection::setRequestProperty)
            connection.setFixedLengthStreamingMode(request.body.size)
            connection.outputStream.use { output -> output.write(request.body) }
            val statusCode = connection.responseCode
            val responseStream = if (statusCode >= 400) {
                connection.errorStream
            } else {
                connection.inputStream
            }
            val responseBody = responseStream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1_024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_RESPONSE_BYTES) {
                        throw IOException("response exceeds maximum")
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: ByteArray(0)
            DiagnosticHttpResponse(statusCode, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 64 * 1_024
    }
}
