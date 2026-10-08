package kami.essentials.discord

import kami.essentials.Config
import kami.essentials.vanish.Vanish
import kami.libs.discord.DiscordApi
import kami.libs.util.RateLimit
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.rcon.RconConsoleSource
import net.minecraft.world.level.BaseCommandBlock
import net.neoforged.neoforge.event.CommandEvent

object Console {
    private val ROOTS = setOf("kami", "claims", "economy", "essentials", "discord", "geology", "library")
    private val PRIVATE = setOf("tellraw", "title", "trigger", "say", "me")
    private const val REPEAT_MS = 5_000L
    private val limit = RateLimit<String>(8, 10_000L)
    private val suppressed = HashMap<String, Int>()
    private val last = HashMap<String, Pair<String, Long>>()

    fun onCommand(e: CommandEvent) {
        val c = Config.s.discord.console
        if (!c.enabled || !DiscordApi.ready) return
        val who = when (val s = e.parseResults.context.source.source) {
            is ServerPlayer -> if (Vanish.active(s)) return else s.gameProfile.name
            is MinecraftServer -> "Console"
            is RconConsoleSource -> "RCON"
            is BaseCommandBlock -> if (c.commandBlocks) "Command block" else return
            else -> return
        }
        val command = e.parseResults.reader.string.trim().removePrefix("/")
        val tokens = command.split(' ')
        val first = tokens.indexOfFirst { it.substringAfter(':').lowercase() !in ROOTS }
        val secret = tokens.size > 1 && tokens.withIndex().any { (i, t) -> (i == first || i > 0 && tokens[i - 1] == "run") && t.substringAfter(':').lowercase().let { it in c.redact || it in PRIVATE } }
        if (!secret && !throttle(who, command)) return
        DiscordApi.console("$who: /" + if (secret) "${command.substringBefore(' ')} [redacted]" else command)
    }

    private fun throttle(who: String, command: String): Boolean {
        val now = System.currentTimeMillis()
        val before = last.put(who, command to now)
        val pass = !(before != null && before.first == command && now - before.second < REPEAT_MS) && limit.allow(who, now)
        if (!pass) {
            suppressed.merge(who, 1, Int::plus)
            return false
        }
        suppressed.remove(who)?.let { DiscordApi.console("$who: $it suppressed") }
        return true
    }
}
