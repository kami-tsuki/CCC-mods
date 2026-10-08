package kami.essentials.admin

import com.mojang.authlib.GameProfile
import com.mojang.brigadier.arguments.StringArgumentType
import kami.essentials.Config
import kami.essentials.KamiEssentials
import kami.essentials.Perms
import kami.essentials.chat.Talk
import kami.libs.admin.Durations
import kami.libs.chat.Theme
import kami.libs.chat.duration
import kami.libs.chat.tell
import kami.libs.command.Ctx
import kami.libs.command.KamiCommands
import kami.libs.command.arg
import kami.libs.command.does
import kami.libs.command.fail
import kami.libs.command.info
import kami.libs.command.lit
import kami.libs.command.ok
import kami.libs.command.text
import kami.libs.db.Db
import kami.libs.discord.DiscordApi
import kami.libs.discord.DiscordFeature
import kami.libs.discord.DiscordText
import kami.libs.discord.Embed
import kami.libs.discord.SlashCommand
import kami.libs.discord.SlashCtx
import kami.libs.discord.SlashOption
import kami.libs.text.Phrase
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.commands.ReloadCommand
import net.neoforged.neoforge.event.CommandEvent
import net.neoforged.neoforge.server.ServerLifecycleHooks
import net.neoforged.neoforge.server.permission.PermissionAPI
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

object Maintenance : DiscordFeature {
    enum class Kind { MAINTENANCE, STOP, RESTART, RELOAD }

    private class Pending(val kind: Kind, val at: Long, val reason: String, var last: Long)

    private const val KEY = "ess.maintenance"
    private const val MIN = 60L
    private val marks = listOf(600L, 300L, 60L, 30L, 10L, 5L, 3L, 1L)
    private val posted = setOf(600L, 300L, 60L, 10L)
    private val pending = ConcurrentHashMap<Kind, Pending>()
    private val server get() = ServerLifecycleHooks.getCurrentServer()
    @Volatile private var reason: String? = null

    val active get() = reason != null

    fun load() {
        reason = Db.read { one("SELECT value FROM libs_meta WHERE key = ?", KEY) { it.string("value") } }
    }

    private fun text(key: String, vararg args: Any) = DiscordText.get("kami_essentials", Config.s.discord.language, "kami_essentials.maintenance.$key", *args)

    private fun short(s: Long) = if (s >= MIN && s % MIN == 0L) "${s / MIN}m" else "${s}s"

    private fun span(s: Long) = if (s < MIN) Phrase.of("kami_essentials.maintenance.seconds", s) else duration(s)

    private fun admin(server: MinecraftServer, profile: GameProfile) =
        server.getProfilePermissions(profile) >= 2 || runCatching { PermissionAPI.getOfflinePermission(profile.id, Perms.MAINTENANCE_BYPASS) }.getOrDefault(false)

    private fun kick(): Component {
        val out = Phrase.of("kami_essentials.maintenance.kick").component().withColor(Theme.VALUE)
        reason?.takeIf { it.isNotBlank() }?.let { out.append("\n\n").append(Component.literal(it).withColor(Theme.TEXT)) }
        val invite = DiscordApi.keys.inviteLink
        if (invite.isNotBlank()) out.append("\n\n").append(Phrase.of("kami_essentials.discord.kick.invite", invite).component().withColor(Theme.LINK))
        return out
    }

    fun check(profile: GameProfile): Component? {
        val s = server ?: return null
        return if (active && !admin(s, profile)) kick() else null
    }

    fun setActive(on: Boolean, why: String = "") {
        val s = server ?: return
        reason = if (on) why else null
        Db.write {
            if (on) exec("INSERT INTO libs_meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", KEY, why)
            else exec("DELETE FROM libs_meta WHERE key = ?", KEY)
        }
        if (on) {
            val closed = kick()
            s.playerList.players.toList().filterNot { admin(s, it.gameProfile) }.forEach { it.connection.disconnect(closed) }
        }
        s.invalidateStatus()
    }

    fun schedule(kind: Kind, seconds: Long, why: String) {
        val s = server ?: return
        val delay = max(MIN, seconds)
        val at = System.currentTimeMillis() + delay * 1000
        if ((pending[kind]?.at ?: Long.MAX_VALUE) <= at) return
        pending[kind] = Pending(kind, at, why, delay)
        announce(s, kind, delay, why, true)
        discord(Theme.ACCENT, line(kind, delay, why))
    }

    fun cancel(kind: Kind): Boolean {
        pending.remove(kind) ?: return false
        discord(Theme.OK, text("cancelled", text("kind.${kind.name.lowercase()}")))
        server?.playerList?.players?.forEach { it.tell(Talk.chat.info(Phrase.of("kami_essentials.maintenance.cancelled", kindName(kind)))) }
        return true
    }

    fun tick(server: MinecraftServer) {
        val now = System.currentTimeMillis()
        pending.values.toList().forEach { p ->
            val left = (p.at - now + 999) / 1000
            if (left <= 0) return@forEach run(server, p)
            val mark = marks.filter { it < p.last && it >= left }.minOrNull() ?: return@forEach
            p.last = mark
            announce(server, p.kind, mark, p.reason, mark <= MIN)
            if (mark in posted) discord(Theme.ACCENT, line(p.kind, mark, p.reason))
        }
    }

    private fun run(server: MinecraftServer, p: Pending) {
        if (!pending.remove(p.kind, p)) return
        discord(Theme.BAD, text("running", text("kind.${p.kind.name.lowercase()}")))
        runCatching {
            when (p.kind) {
                Kind.MAINTENANCE -> setActive(true, p.reason)
                Kind.STOP, Kind.RESTART -> server.halt(false)
                Kind.RELOAD -> reload(server)
            }
        }.onFailure { KamiEssentials.LOG.error("Scheduled {} failed", p.kind, it) }
    }

    private fun reload(server: MinecraftServer) {
        val repo = server.packRepository
        val old = repo.selectedIds.toList()
        repo.reload()
        val disabled = server.worldData.dataConfiguration.dataPacks().disabled
        ReloadCommand.reloadPacks(old + repo.availableIds.filter { it !in old && it !in disabled }, server.createCommandSourceStack())
    }

    fun intercept(e: CommandEvent) {
        val ctx = e.parseResults.context
        val name = ctx.nodes.firstOrNull()?.node?.name ?: return
        val kind = when (name) { "stop" -> Kind.STOP; "reload" -> Kind.RELOAD; else -> return }
        if (ctx.nodes.size > 1) return
        e.isCanceled = true
        schedule(kind, 0, "")
    }

    private fun kindName(kind: Kind) = Phrase.of("kami_essentials.maintenance.kind.${kind.name.lowercase()}")

    private fun line(kind: Kind, left: Long, why: String): String {
        val name = text("kind.${kind.name.lowercase()}")
        return if (why.isBlank()) text("announce", name, short(left)) else text("announce_reason", name, short(left), why)
    }

    private fun announce(server: MinecraftServer, kind: Kind, left: Long, why: String, title: Boolean) {
        val time = span(left)
        val chat = if (why.isBlank()) Phrase.of("kami_essentials.maintenance.announce", kindName(kind), time) else Phrase.of("kami_essentials.maintenance.announce_reason", kindName(kind), time, why)
        val head = kindName(kind).component().withColor(Theme.ACCENT)
        val sub = Phrase.of("kami_essentials.maintenance.subtitle", time).component().withColor(Theme.MUTED)
        server.playerList.players.forEach { p ->
            p.tell(Talk.chat.warn(chat))
            if (title) {
                p.connection.send(ClientboundSetTitlesAnimationPacket(8, 40, 12))
                p.connection.send(ClientboundSetSubtitleTextPacket(sub))
                p.connection.send(ClientboundSetTitleTextPacket(head))
            }
        }
    }

    private fun discord(color: Int, text: String) {
        if (Config.s.discord.enabled) DiscordApi.event(Embed(text, color, Config.s.discord.avatar.server.ifBlank { null }))
    }

    private fun status(name: (Kind) -> Any): List<Pair<String, List<Any>>> {
        val now = System.currentTimeMillis()
        val state = reason?.let { if (it.isBlank()) "status.on" to emptyList() else "status.on_reason" to listOf<Any>(it) } ?: ("status.off" to emptyList())
        return listOf(state) + pending.values.sortedBy { it.at }.map { "status.pending" to listOf(name(it.kind), short(max(1L, (it.at - now + 999) / 1000))) }
    }

    private fun seconds(raw: String): Long {
        val d = runCatching { Durations.parse(raw) }.getOrNull() ?: fail(Phrase.of("kami_libs.admin.bad_duration", raw))
        return d.inWholeSeconds
    }

    private fun Ctx.say(key: String, vararg args: Any) = ok(Phrase.of("kami_essentials.maintenance.$key", *args))

    private fun Ctx.timed(kind: Kind, delay: Long, why: String) {
        schedule(kind, delay, why)
        say("scheduled", kindName(kind))
    }

    private fun Ctx.cancelled(kind: Kind) {
        if (cancel(kind)) say("cancel_done", kindName(kind)) else fail(Phrase.of("kami_essentials.maintenance.nothing", kindName(kind)))
    }

    private fun timedNode(kind: Kind, level: Int, delay: Boolean) = lit(kind.name.lowercase()).requires { it.hasPermission(level) }
        .does { it.timed(kind, 0, "") }
        .then(lit("cancel").does { it.cancelled(kind) })
        .apply {
            if (delay) then(arg("delay", StringArgumentType.word()).does { it.timed(kind, seconds(it.text("delay")), "") }
                .then(arg("reason", StringArgumentType.greedyString()).does { it.timed(kind, seconds(it.text("delay")), it.text("reason")) }))
        }

    fun register() {
        val names = Kind.entries.map { it.name.lowercase() }
        KamiCommands.module("downtime", "Maintenance, stop, restart and reload", names.associateWith { it }) {
            then(timedNode(Kind.STOP, 4, true))
            then(timedNode(Kind.RESTART, 4, true))
            then(timedNode(Kind.RELOAD, 2, false))
            then(lit("maintenance").requires { it.hasPermission(3) }
                .does { c -> status(::kindName).forEach { (k, a) -> c.info(Phrase.of("kami_essentials.maintenance.$k", *a.toTypedArray())) } }
                .then(lit("in").then(arg("duration", StringArgumentType.word()).does { it.timed(Kind.MAINTENANCE, seconds(it.text("duration")), "") }
                    .then(arg("reason", StringArgumentType.greedyString()).does { it.timed(Kind.MAINTENANCE, seconds(it.text("duration")), it.text("reason")) })))
                .then(lit("now").does { it.timed(Kind.MAINTENANCE, 0, "") }
                    .then(arg("reason", StringArgumentType.greedyString()).does { it.timed(Kind.MAINTENANCE, 0, it.text("reason")) }))
                .then(lit("cancel").does { it.cancelled(Kind.MAINTENANCE) })
                .then(lit("off").does { c ->
                    if (!active) fail(Phrase.of("kami_essentials.maintenance.not_active"))
                    setActive(false)
                    c.say("disabled")
                }))
        }
    }

    override fun commands() = listOf(
        SlashCommand(
            "maintenance", "Schedule or end maintenance mode", listOf(
                SlashOption("in", "Delay, for example 5m", false),
                SlashOption("state", "on, off or cancel", false, choices = listOf("on", "off", "cancel")),
                SlashOption("reason", "Shown to players", false)
            ), admin = true, run = ::slash
        )
    )

    private fun slash(ctx: SlashCtx) {
        val s = server ?: return ctx.reply(text("status.off"))
        s.execute {
            val delay = ctx.opt("in")
            val state = ctx.opt("state")
            val name = { k: Kind -> text("kind.${k.name.lowercase()}") }
            when {
                state == "off" -> if (active) { setActive(false); ctx.reply(text("disabled")) } else ctx.reply(text("not_active"))
                state == "cancel" -> ctx.reply(if (cancel(Kind.MAINTENANCE)) text("cancel_done", name(Kind.MAINTENANCE)) else text("nothing", name(Kind.MAINTENANCE)))
                delay != null || state == "on" -> {
                    val seconds = delay?.let { runCatching { Durations.parse(it)?.inWholeSeconds }.getOrNull() }
                    if (delay != null && seconds == null) return@execute ctx.reply(DiscordText.get("kami_libs", Config.s.discord.language, "kami_libs.admin.bad_duration", delay))
                    schedule(Kind.MAINTENANCE, seconds ?: 0, ctx.opt("reason").orEmpty())
                    ctx.reply(text("scheduled", name(Kind.MAINTENANCE)))
                }
                else -> ctx.reply(status(name).joinToString("\n") { (k, a) -> text(k, *a.toTypedArray()) })
            }
        }
    }
}
