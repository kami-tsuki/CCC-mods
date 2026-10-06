package kami.libs.admin

import kami.libs.economy.PlayerParty
import kami.libs.log.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object Admin {
    private class Handler(val level: AdminPerms.Level, val needsReason: Boolean, val run: (AdminCtx, List<String>) -> JsonElement)

    private const val PER_SECOND = 20
    private const val DENIED_EVERY = 60_000L
    private const val AUDIT = "audit"
    private val log = Log.of("library")
    private val handlers = ConcurrentHashMap<String, Handler>()
    private val window = HashMap<UUID, LongArray>()
    private val denied = HashMap<UUID, Long>()

    val level get() = AdminClient.level

    fun view(name: String, handler: (ctx: AdminCtx, args: List<String>) -> JsonElement) = register(name, AdminPerms.Level.VIEW, false, handler)

    fun act(name: String, needsReason: Boolean = true, handler: (ctx: AdminCtx, args: List<String>) -> JsonElement) =
        register(name, AdminPerms.Level.MANAGE, needsReason, handler)

    fun request(name: String, args: List<String> = emptyList(), reason: String? = null, onReply: (AdminReply) -> Unit = {}) =
        AdminClient.request(name, args, reason, onReply)

    fun refreshLevel() = AdminClient.refreshLevel()

    fun done(target: Any, vararg extra: Pair<String, JsonElement>, audit: Map<String, String> = emptyMap()): JsonObject {
        val m = mapOf<String, JsonElement>("target" to JsonPrimitive(target.toString())) + extra
        return JsonObject(if (audit.isEmpty()) m else m + (AUDIT to JsonObject(audit.mapValues { JsonPrimitive(it.value) })))
    }

    internal fun register(name: String, level: AdminPerms.Level, needsReason: Boolean, handler: (AdminCtx, List<String>) -> JsonElement) {
        handlers[name] = Handler(level, needsReason, handler)
    }

    internal fun forget(id: UUID) {
        window.remove(id)
        denied.remove(id)
    }

    internal fun handle(p: ServerPlayer, name: String, args: List<String>, rid: Int) {
        val r = dispatch(p, name, args)
        AdminNet.reply(p, AdminReply(r.ok, r.data, r.error, rid))
    }

    private fun dispatch(p: ServerPlayer, name: String, raw: List<String>): AdminReply {
        val now = System.currentTimeMillis()
        if (!allowed(p.uuid, now)) return AdminReply(false, error = "kami_libs.admin.rate_limited")
        val h = handlers[name] ?: return AdminReply(false, error = "kami_libs.admin.unknown")
        val level = AdminPerms.level(p)
        if (level < h.level) {
            if (now - (denied[p.uuid] ?: 0L) >= DENIED_EVERY) {
                denied[p.uuid] = now
                audit(p, "denied:$name", "", "", emptyMap(), false)
            }
            return AdminReply(false, error = "kami_libs.admin.denied")
        }
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
        if (acting) audit(p, name, target, reason.orEmpty(), extra + args.withIndex().associate { "${it.index}" to it.value }.minus(extra.keys), reply.ok)
        return reply
    }

    private fun allowed(id: UUID, now: Long): Boolean {
        val w = window.getOrPut(id) { LongArray(2) }
        if (w[0] != now / 1000) {
            w[0] = now / 1000
            w[1] = 0
        }
        return ++w[1] <= PER_SECOND
    }

    private fun audit(p: ServerPlayer, action: String, target: String, reason: String, args: Map<String, String>, ok: Boolean) = runCatching {
        Audit.record(PlayerParty(p.uuid), p.scoreboardName, action, target, reason, args, ok)
    }.onFailure { log.error("Could not write audit row {} by {}", action, p.scoreboardName, it) }
}
