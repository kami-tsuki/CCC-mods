package kami.essentials.discord

import kami.essentials.Config
import kami.libs.discord.DiscordApi
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.rcon.RconConsoleSource
import net.minecraft.world.level.BaseCommandBlock
import net.neoforged.neoforge.event.CommandEvent

object Console {
    private val ROOTS = setOf("kami", "claims", "economy", "essentials", "discord", "geology", "library")

    fun onCommand(e: CommandEvent) {
        val c = Config.s.discord.console
        if (!c.enabled || !DiscordApi.ready) return
        val who = when (val s = e.parseResults.context.source.source) {
            is ServerPlayer -> s.gameProfile.name
            is MinecraftServer -> "Console"
            is RconConsoleSource -> "RCON"
            is BaseCommandBlock -> if (c.commandBlocks) "Command block" else return
            else -> return
        }
        val command = e.parseResults.reader.string.trim().removePrefix("/")
        val tokens = command.split(' ')
        val first = tokens.indexOfFirst { it.substringAfter(':').lowercase() !in ROOTS }
        val secret = tokens.size > 1 && tokens.withIndex().any { (i, t) -> (i == first || i > 0 && tokens[i - 1] == "run") && t.substringAfter(':').lowercase() in c.redact }
        DiscordApi.console("$who: /" + if (secret) "${command.substringBefore(' ')} [redacted]" else command)
    }
}
