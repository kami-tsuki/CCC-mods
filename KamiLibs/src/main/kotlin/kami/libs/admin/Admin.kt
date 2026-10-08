package kami.libs.admin

import kami.libs.db.Db
import kami.libs.economy.PlayerParty
import kami.libs.log.Log
import kami.libs.stats.Staff
import kami.libs.util.RateLimit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

object Admin {
    private class Handler(val level: AdminPerms.Level, val needsReason: Boolean, val async: Boolean, val run: (AdminCtx, List<String>) -> JsonElement)

    private const val PER_SECOND = 20
    private const val QUEUE = 64
    private const val DENIED_EVERY = 60_000L
    private const val AUDIT = "audit"
    private val log = Log.of("library")
    private val handlers = ConcurrentHashMap<String, Handler>()
    private val denied = HashMap<UUID, Long>()
    private val limiter = RateLimit<UUID>(PER_SECOND, 1000L)
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(QUEUE)) { Db.ViewThread(it, "kami-admin-views").apply { isDaemon = true } }

    val level get() = AdminClient.level

    fun view(name: String, async: Boolean = false, handler: (ctx: AdminCtx, args: List<String>) -> JsonElement) = register(name, AdminPerms.Level.VIEW, false, async, handler)

    fun act(name: String, needsReason: Boolean = true, handler: (ctx: AdminCtx, args: List<String>) -> JsonElement) =
        register(name, AdminPerms.Level.MANAGE, needsReason, false, handler)

    fun request(name: String, args: List<String> = emptyList(), reason: String? = null, onReply: (AdminReply) -> Unit = {}) =
        AdminClient.request(name, args, reason, onReply)

    fun refreshLevel() = AdminClient.refreshLevel()

    fun done(target: Any, vararg extra: Pair<String, JsonElement>, audit: Map<String, String> = emptyMap()): JsonObject {
        val m = mapOf<String, JsonElement>("target" to JsonPrimitive(target.toString())) + extra
        return JsonObject(if (audit.isEmpty()) m else m + (AUDIT to JsonObject(audit.mapValues { JsonPrimitive(it.value) })))
    }

    internal fun register(name: String, level: AdminPerms.Level, needsReason: Boolean, async: Boolean = false, handler: (AdminCtx, List<String>) -> JsonElement) {
        handlers[name] = Handler(level, needsReason, async, handler)
    }

    internal fun forget(id: UUID) {
        limiter.forget(id)
        denied.remove(id)
    }

    internal fun handle(p: ServerPlayer, name: String, args: List<String>, rid: Int) {
        val h = handlers[name]
        val level = AdminPerms.level(p)
        val refusal = refusal(p, name, h, level)
        if (refusal != null || h == null) return AdminNet.reply(p, AdminReply(false, error = refusal, rid = rid))
        if (!h.async) return reply(p, invoke(p, name, h, level, args), rid)
        val server = p.server
        try {
            worker.execute { reply(p, invoke(p, name, h, level, args), rid, server) }
        } catch (e: RejectedExecutionException) {
            AdminNet.reply(p, AdminReply(false, error = "kami_libs.admin.busy", rid = rid))
        }
    }

    private fun reply(p: ServerPlayer, r: AdminReply, rid: Int, server: MinecraftServer? = null) {
        val send = { AdminNet.reply(p, AdminReply(r.ok, r.data, r.error, rid)) }
        if (server == null) send() else server.execute(send)
    }

    private fun refusal(p: ServerPlayer, name: String, h: Handler?, level: AdminPerms.Level): String? {
        val now = System.currentTimeMillis()
        if (!limiter.allow(p.uuid, now)) return "kami_libs.admin.rate_limited"
        if (h == null) return "kami_libs.admin.unknown"
        if (level >= h.level) return null
        if (now - (denied[p.uuid] ?: 0L) >= DENIED_EVERY) {
            denied[p.uuid] = now
            audit(p, "denied:$name", "", "", emptyMap(), false)
        }
        return "kami_libs.admin.denied"
    }

    private fun invoke(p: ServerPlayer, name: String, h: Handler, level: AdminPerms.Level, raw: List<String>): AdminReply {
        val acting = h.level == AdminPerms.Level.MANAGE
        val args = raw.drop(1)
        val reason = if (acting) Reasons.clean(raw.firstOrNull()) ?: "".takeUnless { h.needsReason } else ""
        var target = args.firstOrNull().orEmpty()
        var extra = emptyMap<String, String>()
        val reply = try {
            if (reason == null) throw AdminFailure("kami_libs.admin.reason.invalid")
            val data = h.run(AdminCtx(p, level, reason), args)
            val obj = data as? JsonObject
            (obj?.get("target") as? JsonPrimitive)?.contentOrNull?.let { target = it }
            val audited = obj?.takeIf { acting && it[AUDIT] is JsonObject }
            audited?.let { o -> extra = (o[AUDIT] as JsonObject).mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap() }
            AdminReply(true, audited?.let { JsonObject(it - AUDIT) } ?: data)
        } catch (e: AdminFailure) {
            AdminReply(false, JsonArray(e.args.map { JsonPrimitive(it) }), e.key)
        } catch (e: Exception) {
            log.error("Admin request {} by {} failed", name, p.scoreboardName, e)
            AdminReply(false, error = "kami_libs.admin.error")
        }
        if (acting && reply.ok) Staff.mark(p)
        if (acting) audit(p, name, target, reason.orEmpty(), extra + args.withIndex().associate { "${it.index}" to it.value }.minus(extra.keys), reply.ok)
        return reply
    }

    private fun audit(p: ServerPlayer, action: String, target: String, reason: String, args: Map<String, String>, ok: Boolean) = runCatching {
        Audit.record(PlayerParty(p.uuid), p.scoreboardName, action, target, reason, args, ok)
    }.onFailure { log.error("Could not write audit row {} by {}", action, p.scoreboardName, it) }
}
