package kami.essentials.client

import kami.essentials.display.Motd
import kami.libs.log.Log
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.ServerStatusPinger
import net.minecraft.network.chat.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object MotdRefresh {
    private const val TIMEOUT = 10_000L
    private const val IDLE = 30_000L
    private const val PENDING = 2_000L
    private val pinger = ServerStatusPinger()
    private val pool = Executors.newFixedThreadPool(2) { Thread(it, "kami-motd-refresh").apply { isDaemon = true } }
    private val due = ConcurrentHashMap<String, Long>()
    private val log = Log.of("motd")
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private var active = false
    @Volatile private var gen = 0
    private var failed = false

    fun tick(mc: Minecraft) {
        if (failed) return
        runCatching { update(mc) }.onFailure {
            log.error("Server list MOTD refresh disabled after an error", it)
            failed = true
            reset()
        }
    }

    private fun update(mc: Minecraft) {
        val screen = mc.screen as? JoinMultiplayerScreen
        if (screen == null) {
            if (active) {
                active = false
                reset()
            }
            return
        }
        if (!active) {
            active = true
            pinger.removeAll()
        }
        pinger.tick()
        val now = Util.getMillis()
        val mine = gen
        val servers = screen.servers
        for (i in 0 until servers.size()) {
            val data = servers.get(i)
            if ((due[data.ip] ?: 0L) > now) continue
            val motd = motd(data)
            val period = period(motd)
            due[data.ip] = now + when {
                period != null -> TIMEOUT
                motd == null || motd.string.isEmpty() -> PENDING
                else -> IDLE
            }
            if (period != null) {
                if (seen.add(data.ip)) log.info("Live MOTD found for {}, refreshing every {} s", data.ip, period / 1000)
                refresh(mc, data, period, mine)
            }
        }
    }

    private fun reset() {
        gen++
        pinger.removeAll()
        due.clear()
    }

    private fun refresh(mc: Minecraft, data: ServerData, period: Long, mine: Int) {
        val probe = ServerData(data.name, data.ip, data.type())
        pool.execute {
            if (mine != gen) return@execute
            runCatching {
                pinger.pingServer(probe, {}) {
                    mc.execute {
                        if (mine != gen) return@execute
                        val fresh = motd(probe)
                        if (period(fresh) != null) {
                            data.motd = fresh
                            data.status = probe.status
                            data.players = probe.players
                            data.ping = probe.ping
                        }
                        due[data.ip] = Util.getMillis() + period
                    }
                }
            }.onFailure { log.warn("MOTD refresh ping to {} failed: {}", data.ip, it.toString()) }
        }
    }

    private fun motd(data: ServerData): Component? = data.motd

    private fun period(motd: Component?): Long? =
        motd?.style?.insertion?.takeIf { it.startsWith(Motd.MARKER) }?.removePrefix(Motd.MARKER)?.toLongOrNull()?.let { it.coerceIn(5L, 300L) * 1000 }
}
