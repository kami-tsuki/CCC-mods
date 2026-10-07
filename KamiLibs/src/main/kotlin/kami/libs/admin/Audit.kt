package kami.libs.admin

import kami.libs.db.Db
import kami.libs.economy.Party
import kami.libs.log.Log
import kami.libs.economy.PlayerParty
import kami.libs.economy.ServerParty
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import net.minecraft.commands.CommandSourceStack

object Audit {
    @Serializable
    class Entry(val id: Long, val at: Long, val actor: String, val actorName: String, val action: String, val target: String, val reason: String, val args: Map<String, String>, val ok: Boolean)

    class Filter(val actor: String? = null, val action: String? = null, val target: String? = null, val from: Long? = null, val to: Long? = null, val ok: Boolean? = null)

    private val log = Log.of("library")
    private const val MAX_ARGS = 8
    private const val MAX_ARG = 256
    private const val REDACTED = "***"
    private val SENSITIVE = setOf("ips")
    private val argsSerializer = MapSerializer(String.serializer(), String.serializer())

    fun record(actor: Party, actorName: String, action: String, target: String, reason: String, args: Map<String, String> = emptyMap(), ok: Boolean = true) {
        val stored = args.entries.take(MAX_ARGS).associate { it.key.take(MAX_ARG) to it.value.take(MAX_ARG) }
        runCatching {
            Db.durable {
                exec(
                    "INSERT INTO libs_audit (at, actor, actor_name, action, target, reason, args, ok) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    System.currentTimeMillis(), actor.key, actorName.take(MAX_ARG), action.take(MAX_ARG), target.take(MAX_ARG), reason.take(MAX_ARG), Db.json.encodeToString(argsSerializer, stored), ok
                )
            }
        }.onFailure { log.error("Could not record the audit entry {} on {}", action, target, it) }
    }

    fun record(source: CommandSourceStack, action: String, target: String, reason: String, args: Map<String, String> = emptyMap(), ok: Boolean = true) =
        record(source.player?.let { PlayerParty(it.uuid) } ?: ServerParty, source.textName, action, target, reason, args, ok)

    fun query(filter: Filter, page: Int, size: Int = Page.SIZE, level: AdminPerms.Level = AdminPerms.Level.VIEW): Page<Entry> {
        val full = level >= AdminPerms.Level.MANAGE
        val w = Where()
        filter.actor?.let { w.add("(actor = ? OR actor_name = ? COLLATE NOCASE)", it, it) }
        filter.action?.let { w.add("action LIKE ? ESCAPE '!'", Where.like(it) + "%") }
        filter.target?.let { w.add("target = ?", it) }
        filter.from?.let { w.add("at >= ?", it) }
        filter.to?.let { w.add("at < ?", it) }
        filter.ok?.let { w.add("ok = ?", it) }
        return w.page("libs_audit", "id, at, actor, actor_name, action, target, reason, args, ok", "id DESC", page, size) {
            Entry(
                it.long("id"), it.long("at"), it.string("actor"), it.string("actor_name"), it.string("action"), it.string("target"), it.string("reason"),
                Db.json.decodeFromString(argsSerializer, it.string("args")).mapValues { (k, v) -> if (full || k !in SENSITIVE) v else REDACTED }, it.bool("ok")
            )
        }
    }
}
