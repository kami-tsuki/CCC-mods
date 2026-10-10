package kami.libs.stats

import kami.libs.LibConfig
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.GameType
import net.neoforged.neoforge.common.util.FakePlayer
import net.neoforged.neoforge.event.CommandEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object Staff {
    const val PREFIX = "staff."
    val hidden = CopyOnWriteArrayList<(ServerPlayer) -> Boolean>()
    private val modes = ConcurrentHashMap.newKeySet<String>()
    private val until = ConcurrentHashMap<String, Long>()
    private val own = setOf("staff", "server", "discord", "money", "skill")
    private val kept = setOf("session.login", "session.logout", "market.watch")

    fun active(subject: String) = subject in modes || (until[subject] ?: 0L) > System.currentTimeMillis()

    fun active(p: ServerPlayer) = p !is FakePlayer && active(subject(p))

    fun route(key: String, subject: String) =
        if (subject.startsWith("p:") && key.substringBefore('.') !in own && key !in kept && active(subject)) PREFIX + key else key

    fun refresh(p: ServerPlayer, mode: GameType = p.gameMode.gameModeForPlayer) {
        if (p is FakePlayer) return
        if (mode == GameType.CREATIVE || mode == GameType.SPECTATOR || veiled(p)) modes.add(subject(p)) else modes.remove(subject(p))
    }

    fun veiled(p: ServerPlayer) = hidden.any { runCatching { it(p) }.getOrDefault(false) }

    fun mark(p: ServerPlayer) {
        if (p !is FakePlayer) until[subject(p)] = System.currentTimeMillis() + LibConfig.s.staffCommandSeconds * 1000L
    }

    fun command(e: CommandEvent) {
        val ctx = e.parseResults.context
        val p = ctx.source.player ?: return
        val root = ctx.nodes.firstOrNull()?.node ?: return
        val anyone = ctx.source.withPermission(0)
        if (p is FakePlayer || ctx.nodes.all { it.node.canUse(anyone) }) return
        mark(p)
        Stats.add("staff.command", subject(p), root.name)
    }

    fun tick(players: List<ServerPlayer>) {
        val now = System.currentTimeMillis()
        until.values.removeIf { it <= now }
        players.forEach { refresh(it) }
    }

    fun forget(id: UUID) {
        modes.remove("p:$id")
        until.remove("p:$id")
    }

    fun clear() {
        modes.clear()
        until.clear()
    }

    private fun subject(p: ServerPlayer) = "p:${p.uuid}"
}
