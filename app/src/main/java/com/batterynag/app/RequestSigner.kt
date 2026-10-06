package com.batterynag.app

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signs outgoing email API requests.
 *
 * Canonical string (newline separated):
 *
 *   v1
 *   {timestamp}       unix seconds
 *   {nonce}           random hex
 *   {METHOD}          upper case HTTP method
 *   {path}            URL path, including any query string
 *   {sha256hex(body)} hex SHA-256 of the raw body
 *
 * signature = hex(HMAC_SHA256(secret, canonical))
 *
 * The secret comes from BuildConfig and is injected by GitHub Actions
 * from a repository secret, so it never appears in source control.
 */
object RequestSigner {

    private const val VERSION = "v1"
    private const val CLOCK_SKEW_SECONDS = 60L

    /**
     * Returns the signing headers for a request, or an empty map when no
     * secret is configured (which the server will reject).
     */
    fun headers(secret: String, method: String, path: String, body: String): Map<String, String> {
        if (secret.isBlank()) return emptyMap()

        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        val nonce = java.util.UUID.randomUUID().toString().replace("-", "")

        val canonical = buildString {
            append(VERSION).append('\n')
            append(timestamp).append('\n')
            append(nonce).append('\n')
            append(method.uppercase()).append('\n')
            append(path).append('\n')
            append(sha256Hex(body))
        }

        return mapOf(
            "X-BatteryNag-Timestamp" to timestamp,
            "X-BatteryNag-Nonce" to nonce,
            "X-BatteryNag-Signature" to hmacSha256Hex(secret, canonical)
        )
    }

    /** Local sanity check so an obvious clock problem is caught early. */
    fun timestampSeconds(): Long = System.currentTimeMillis() / 1000L

    fun allowedSkewSeconds(): Long = CLOCK_SKEW_SECONDS

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun hmacSha256Hex(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
