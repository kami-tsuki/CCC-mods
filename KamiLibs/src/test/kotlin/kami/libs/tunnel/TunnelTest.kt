package kami.libs.tunnel

import com.sun.net.httpserver.HttpServer
import kami.libs.db.Db
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TunnelTest {
    private lateinit var world: Path
    private lateinit var server: HttpServer
    private val token = "t".repeat(40)
    private val jobs = ConcurrentLinkedQueue<Pair<Int, String>>()
    private val replies = LinkedBlockingQueue<String>()
    private val polls = AtomicInteger()

    @Volatile
    private var auth: List<String?> = emptyList()

    private fun keys(url: String = "http://127.0.0.1:${server.address.port}", name: String = "mc-server-1", token: String = this.token) = TunnelKeys(true, url, name, token)

    private fun job(id: String, op: String, args: String = "{}") = """{"id":"$id","op":"$op","args":$args}"""

    @BeforeTest
    fun setUp() {
        world = Files.createTempDirectory("kami-tunnel")
        Db.open(world.resolve("kami").resolve("kami.db"))
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/next") { ex ->
            auth = listOf(ex.requestHeaders.getFirst("Authorization"), ex.requestHeaders.getFirst("X-Mc-Server"))
            polls.incrementAndGet()
            val next = jobs.poll()
            when {
                next == null -> {
                    Thread.sleep(100)
                    ex.sendResponseHeaders(204, -1)
                }
                next.first == 0 -> {
                    ex.sendResponseHeaders(200, 100)
                    ex.responseBody.write("{\"id\"".toByteArray())
                    ex.responseBody.flush()
                    Thread.sleep(3_000)
                }
                next.first != 200 -> ex.sendResponseHeaders(next.first, -1)
                else -> {
                    val body = next.second.toByteArray()
                    ex.sendResponseHeaders(200, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                }
            }
            ex.close()
        }
        server.createContext("/v1/reply/") { ex ->
            replies.add(ex.requestURI.path + " " + ex.requestBody.readAllBytes().decodeToString())
            ex.sendResponseHeaders(204, -1)
            ex.close()
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        Tunnel.stop()
        Tunnel.bodyLimitS = 10L
        server.stop(0)
        Db.close()
        world.toFile().deleteRecursively()
    }

    private fun reply() = replies.poll(10, TimeUnit.SECONDS)

    @Test
    fun answersAJobWithTheCredentials() {
        jobs.add(200 to job("0123456789abcdef", "keys"))
        Tunnel.start(keys())
        assertEquals("""/v1/reply/0123456789abcdef {"ok":true,"data":{"keys":[]}}""", reply())
        assertEquals(listOf("Bearer $token", "mc-server-1"), auth)
    }

    @Test
    fun reportsARefusedJobAsAFailure() {
        jobs.add(200 to job("0123456789abcdef", "drop"))
        Tunnel.start(keys())
        assertEquals("""/v1/reply/0123456789abcdef {"ok":false,"error":"unknown op"}""", reply())
    }

    @Test
    fun ignoresJobsWithABadIdOrTooLargeAndKeepsPolling() {
        jobs.add(200 to job("not-hex", "keys"))
        jobs.add(200 to job("aaaaaaaaaaaaaaaa", "keys", """{"x":"${"y".repeat(70_000)}"}"""))
        jobs.add(200 to "not json")
        jobs.add(200 to job("bbbbbbbbbbbbbbbb", "keys"))
        Tunnel.start(keys())
        assertTrue(reply().startsWith("/v1/reply/bbbbbbbbbbbbbbbb "))
        assertNull(replies.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun backsOffOnASupersededPollAndRecovers() {
        jobs.add(409 to "")
        jobs.add(200 to job("0123456789abcdef", "keys"))
        Tunnel.start(keys())
        assertNotNull(reply())
    }

    @Test
    fun aReplyOverOneMebibyteBecomesTooLarge() {
        val big = buildJsonObject { put("ok", true); put("data", "x".repeat(2_000_000)) }
        assertEquals("""{"ok":false,"error":"too large"}""", Tunnel.encode(big).decodeToString())
        val small = buildJsonObject { put("ok", true); put("data", "é") }
        assertEquals(small.toString(), Tunnel.encode(small).decodeToString())
    }

    @Test
    fun aBodyThatStallsDoesNotPinTheTunnel() {
        Tunnel.bodyLimitS = 1L
        jobs.add(0 to "")
        jobs.add(200 to job("0123456789abcdef", "keys"))
        Tunnel.start(keys())
        assertTrue(replies.poll(10, TimeUnit.SECONDS)!!.startsWith("/v1/reply/0123456789abcdef "))
    }

    @Test
    fun staysOffUnlessEnabledAndValid() {
        Tunnel.start(keys().copy(enabled = false))
        Tunnel.start(keys(token = "short"))
        Thread.sleep(300)
        assertEquals(0, polls.get())
    }

    @Test
    fun validatesTheKeys() {
        assertNull(keys("https://mc-ingest.example.org").problem())
        assertNotNull(keys("http://mc-ingest.example.org").problem())
        assertNotNull(keys("https://mc-ingest.example.org/v1").problem())
        assertNotNull(keys("https://user@mc-ingest.example.org").problem())
        assertNotNull(keys("not a url").problem())
        assertNotNull(keys(name = "Bad Name").problem())
        assertNotNull(keys(token = "short").problem())
        assertNotNull(keys(token = "a".repeat(40) + " b").problem())
        assertNotNull(keys(token = "a".repeat(40) + "\n").problem())
        assertNotNull(keys(token = "a".repeat(40) + "é").problem())
        assertEquals("TunnelKeys(***)", keys().toString())
    }
}
