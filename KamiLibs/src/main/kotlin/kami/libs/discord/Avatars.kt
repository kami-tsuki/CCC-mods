package kami.libs.discord

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

object Avatars {
    private const val CHECK_MS = 600_000L
    private val TIMEOUT = Duration.ofSeconds(5)
    private const val PROBE = "8667ba71-b85a-4004-af54-457a9734eed7"
    private val http by lazy { HttpClient.newBuilder().connectTimeout(TIMEOUT).build() }

    @Volatile private var primary = ""
    @Volatile private var fallback = ""
    @Volatile private var down = false
    @Volatile private var checked = 0L

    fun configure(primary: String, fallback: String) {
        this.primary = primary
        this.fallback = fallback
        down = false
        checked = 0L
    }

    fun of(uuid: UUID): String {
        probe()
        return fill(if (down && fallback.isNotBlank()) fallback else primary, uuid.toString())
    }

    private fun probe() {
        val now = System.currentTimeMillis()
        if (fallback.isBlank() || primary.isBlank() || now - checked < CHECK_MS) return
        checked = now
        val request = runCatching {
            HttpRequest.newBuilder(URI.create(fill(primary, PROBE))).method("HEAD", HttpRequest.BodyPublishers.noBody()).timeout(TIMEOUT).build()
        }.getOrNull() ?: return
        http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete { r, e -> down = e != null || r.statusCode() !in 200..399 }
    }

    private fun fill(template: String, uuid: String) = Templates.fill(template, mapOf("uuid" to uuid))
}
