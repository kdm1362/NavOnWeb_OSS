/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto.credential

import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/** Public AASDK development identity from commit 046b3b381595509d0939fa84b14a90978f46ff63. */
internal class BundledAasdkCredentialProvider(
    private val validator: HeadUnitCredentialValidator = HeadUnitCredentialValidator(),
) : HeadUnitCredentialProvider {
    override fun load(): HeadUnitCredentialResult {
        var certificateBytes: ByteArray? = null
        var privateKeyBytes: ByteArray? = null
        return try {
            certificateBytes = Base64.getDecoder().decode(CERTIFICATE_DER_BASE64)
            privateKeyBytes = Base64.getDecoder().decode(PRIVATE_KEY_PKCS8_DER_BASE64)
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certificateBytes)) as X509Certificate
            val privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes))
            validator.validate(
                certificateChain = listOf(certificate),
                privateKey = privateKey,
                source = HeadUnitCredentialSource.BUNDLED_RELEASE,
            )
        } catch (_: Exception) {
            unavailable(
                HeadUnitCredentialCode.INVALID_CERTIFICATE,
                HeadUnitCredentialSource.BUNDLED_RELEASE,
            )
        } finally {
            certificateBytes?.fill(0)
            privateKeyBytes?.fill(0)
        }
    }

    private companion object {
        const val CERTIFICATE_DER_BASE64 = "MIIDKjCCAhICARswDQYJKoZIhvcNAQELBQAwWzELMAkGA1UEBhMCVVMxEzARBgNVBAgMCkNhbGlmb3JuaWExFjAUBgNVBAcMDU1vdW50YWluIFZpZXcxHzAdBgNVBAoMFkdvb2dsZSBBdXRvbW90aXZlIExpbmswJhcRMTQwNzA0MDAwMDAwLTA3MDAXETQ1MDQyOTE0MjgzOC0wNzAwMFMxCzAJBgNVBAYTAkpQMQ4wDAYDVQQIDAVUb2t5bzERMA8GA1UEBwwISGFjaGlvamkxFDASBgNVBAoMC0pWQyBLZW53b29kMQswCQYDVQQLDAIwMTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAM911mNnUfx+WJtxuk06GO7kXRW/gXUVNQBkbAFZmVdVNvLoEQNthi2X8WCOwX6n6oMPxU2MGJnvicP36kBqfHhfQ2Fvqlf7YjjhgBHh0lqKShVPxIvdatBjVQ76aym5H3GpkigLGkmeyiVoVO8oc3cJ1bO96wFRmk7kJbYcEjQyakODPDu4QgWUTwp1Z8Dn41ARMG5OFh6otITLXBzj9REkUPkxfS03dBXGr5/LIqvSsnxib1hJ47xnYJXROUsBy3e6T+fYZEEzZa7y7tFioHIQ8G/TziPmvFzmQpaWMGiYfoIgX8WoR3GD1diYW+wBaZTW+4SFUZJmRKgqTbMNFkMCAwEAATANBgkqhkiG9w0BAQsFAAOCAQEAsGdH5VFn78WsBElMXaMziqFCzmilkvr85/QpGCIztI0FdF6xyMBJk/gYs2thwvF+tCCpXoO8mjgJuvJZlwr6fHzKOx5hNUb06AeMtsUzUfFjSZXKrSR+XmclVd+Z6/ie33VhGePOPTKYmJ/PPfTT9wvT93qswcxhA+oX5yqLbU3uDPF1ZnJaEeD/YN45K/4eEA4/0SDXaWW14OScdS2LV0BcYmsbkPVNYZn37FlY7e2Z4FUphh0A7yME2Eh/e57QxWrJ1wubdzGnX8mrABc67ADUU5r9tlTRqMs7FGOk6QS2Cxp4pqeVQsrPts4OEwyPUyb3LfFNo3+sP111D9zEow=="
        const val PRIVATE_KEY_PKCS8_DER_BASE64 = "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDPddZjZ1H8flibcbpNOhju5F0Vv4F1FTUAZGwBWZlXVTby6BEDbYYtl/FgjsF+p+qDD8VNjBiZ74nD9+pAanx4X0Nhb6pX+2I44YAR4dJaikoVT8SL3WrQY1UO+mspuR9xqZIoCxpJnsolaFTvKHN3CdWzvesBUZpO5CW2HBI0MmpDgzw7uEIFlE8KdWfA5+NQETBuThYeqLSEy1wc4/URJFD5MX0tN3QVxq+fyyKr0rJ8Ym9YSeO8Z2CV0TlLAct3uk/n2GRBM2Wu8u7RYqByEPBv084j5rxc5kKWljBomH6CIF/FqEdxg9XYmFvsAWmU1vuEhVGSZkSoKk2zDRZDAgMBAAECggEAHa7tkuF4oJjvUqZuEpiqcpvoGbGB81+qarjznynTv+QobY74yDXGigWAeuFSHC4oZsI957+Q0Y2td4WkVb0mvA5dVLamd9o3Do5tRaG6+EtrGCuGosB2hQSBahg4dwrOzfOGPwZ/p2L552pLJMDz7GdS1VnqIxEq8/i+0JSpoiAGdd2ZBsYg0/7/hCFvlHtfMy3/E97q4zIAjILLkWb0ee96Rp6lp4UR/duHg1XWI39HynHpwVzoQtjEhlz/hdub3VYapJ0HSJKuJplOOCuZoWNI7jS2QJrEj03oMbMmmamcyfTuJ+GXZWOcY5jC3K6HowoSfIupOtOD3HSLeYzCyQKBgQD0jKWM9F6tfEdQAm35wo5rK5fI23zSr08hwZvJE2+fuohjq2vm635ltoWB+Ad1In/icgJGrTYel4MnyiP0GaFMCFxfpzWKsfkgKNGW3jSps/B6YhynJu2Zv4Og1hsjw7h1Q0Yf0g98Yrx1Gk6iTab5+1+T3gabKRZtkXkKc4oinwKBgQDZLJznGUoHlCTZmTBcjPFvOjNJ+6ceFZZ2DiUBvIifBrpjkYHflQ101YjxMApmw3Z8q9dEWAKNs+t5RtEQlozehn3ajlficXGCO8ATW3JGpw9Vt0pthmC1yqolfTqxb+Dev/dEfRc+PZKAkgd7D2wzOMZYNWIwdcuae0jytUDt3QKBgE6vfokXC+uoRE3TKk2lsyt6kFEZhlVIAR8sa5LMRStQ2pevTX20oivaCaUjCEtBOYLECkVxcCtxtsqzuNPO914+hnJkm86vqygU2jM/9hPwiNzn+q4x0VuaCqFSotLkI9LfPY35ifAM2PSY7Vo9wA9JOZybYObF1qiUmSyqtnSjAoGBAIPQokNahCZpcpxocIQcQAaEytCi3+JQtAxftXKCXmI3kTYSAUQVkh9R5FaQFCAfj3FIU9Z1nMcpZ0krBIYO+t2twAHB1/HnbT/gyEp3fLsJdzNNlu03XMe6hN9QTSmZgGFzHsABNPRgJuXGvKeiysekNC8h58EGHlhbcAFwpkI5AoGAQz+LHMj85VJnQ/LaD5dXz64ZDILleNDpzGRVNfcazRldt6aBQ6dfxyh4JdLhWuER/j8dgxY/sYHkwVQ4RW2UuIszTmu5QvsKumF1+BrINdVxM9+TvmUuGa4oDCne0gc+gk5CIo1Df+qSzmSb/126NQZTYBGJTeqbEsx2B6AXPx4="
    }
}
