package kami.libs.tunnel

import kami.libs.db.Db
import kami.libs.log.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object Tunnel {
    private enum class State { UNKNOWN, UP, DOWN }

    private val log = Log.of("tunnel")
    private val http by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() }
    private val ID = Regex("[0-9a-f]{16}")
    private val POLL = Duration.ofSeconds(35)
    private val REPLY = Duration.ofSeconds(10)
    private const val POLL_LIMIT_S = 40L
    private const val BODY_LIMIT_S = 10L
    private const val RETRY_MIN = 1_000L
    private const val RETRY_MAX = 10_000L
    private const val MAX_JOB = 64 * 1024
    private const val MAX_REPLY = 1024 * 1024

    private var worker: Thread? = null

    @Synchronized
    fun start(keys: TunnelKeys = TunnelFile.load()) {
        if (worker != null) return
        if (!keys.enabled) return log.info("Tunnel is off, set enabled to true in kami-tunnel.json to open it")
        keys.problem()?.let { return log.error("Tunnel stays off, kami-tunnel.json: {}", it) }
        worker = Thread({ run(keys) }, "kami-tunnel").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        worker?.interrupt()
        worker = null
    }

    private fun run(keys: TunnelKeys) {
        try {
            poll(keys)
        } catch (_: InterruptedException) {
        } catch (e: Throwable) {
            log.error("Tunnel stopped: {}", e.toString())
        } finally {
            synchronized(this) { if (worker === Thread.currentThread()) worker = null }
        }
    }

    private fun poll(keys: TunnelKeys) {
        var wait = RETRY_MIN
        var state = State.UNKNOWN
        var reason = ""
        while (!Thread.currentThread().isInterrupted) {
            try {
                fetch(keys)
                if (state != State.UP) log.info("Tunnel to {} is up as {}", keys.url, keys.server)
                state = State.UP
                wait = RETRY_MIN
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                val cause = e.message ?: e.javaClass.simpleName
                if (state != State.DOWN || cause != reason) log.warn("Tunnel to {} is down: {}", keys.url, cause)
                reason = cause
                state = State.DOWN
                Thread.sleep(wait)
                wait = (wait * 2).coerceAtMost(RETRY_MAX)
            }
        }
    }

    private fun fetch(keys: TunnelKeys) {
        val res = await(http.sendAsync(request(keys, "/v1/next").GET().timeout(POLL).build(), HttpResponse.BodyHandlers.ofInputStream()), POLL_LIMIT_S)
        res.body().use { body ->
            when (res.statusCode()) {
                200 -> answer(keys, await(CompletableFuture.supplyAsync { body.readNBytes(MAX_JOB + 1) }, BODY_LIMIT_S))
                204 -> Unit
                else -> throw IOException("HTTP ${res.statusCode()}")
            }
        }
    }

    private fun <T> await(future: CompletableFuture<T>, seconds: Long): T = try {
        future.get(seconds, TimeUnit.SECONDS)
    } catch (e: ExecutionException) {
        throw (e.cause as? Exception) ?: e
    } catch (_: TimeoutException) {
        future.cancel(true)
        throw IOException("no answer within $seconds s")
    }

    private fun answer(keys: TunnelKeys, body: ByteArray) {
        if (body.size > MAX_JOB) return
        val job = runCatching { Db.json.parseToJsonElement(body.decodeToString()).jsonObject }.getOrNull() ?: return
        val id = job.text("id")?.takeIf(ID::matches) ?: return
        val reply = encode(compute(job.text("op").orEmpty(), job["args"] as? JsonObject ?: JsonObject(emptyMap())))
        val post = request(keys, "/v1/reply/$id").header("Content-Type", "application/json").timeout(REPLY).POST(HttpRequest.BodyPublishers.ofByteArray(reply)).build()
        http.sendAsync(post, HttpResponse.BodyHandlers.discarding()).exceptionally { log.debug("Reply {} not delivered: {}", id, it.message); null }
    }

    private fun compute(op: String, args: JsonObject): JsonObject = try {
        buildJsonObject { put("ok", true); put("data", Probe.run(op, args)) }
    } catch (e: Probe.Refused) {
        failure(e.message.orEmpty())
    } catch (e: Exception) {
        log.warn("Query {} failed: {}", op, e.toString())
        failure("failed")
    }

    internal fun encode(reply: JsonObject): ByteArray = reply.toString().toByteArray(Charsets.UTF_8).takeIf { it.size <= MAX_REPLY } ?: failure("too large").toString().toByteArray(Charsets.UTF_8)

    private fun failure(error: String) = buildJsonObject { put("ok", false); put("error", error) }

    private fun request(keys: TunnelKeys, path: String) = HttpRequest.newBuilder(URI.create(keys.url + path))
        .header("Authorization", "Bearer ${keys.token}")
        .header("X-Mc-Server", keys.server)
}
