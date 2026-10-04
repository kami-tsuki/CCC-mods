package kami.essentials.discord

import kami.essentials.Config
import kami.essentials.vanish.Vanish
import kami.libs.claims.ClaimsApi
import kami.libs.discord.Avatars
import kami.libs.discord.DiscordApi
import kami.libs.discord.Embed
import kami.libs.discord.Templates
import kami.libs.chat.Theme
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.server.ServerLifecycleHooks

object Bot {
    private var running = false
    private var registered = false
    private var startedAt = 0L
    private var presenceDue = 0L
    private var topicDue = 0L

    fun start(server: MinecraftServer) {
        if (!Config.s.discord.enabled || running) return
        running = true
        startedAt = System.currentTimeMillis()
        apply()
        if (!registered) {
            registered = true
            DiscordApi.register(Relay)
            DiscordApi.register(Gate)
        }
        DiscordApi.start(server, Config.s.discord.console.flushSeconds)
        lifecycle(Config.s.discord.events.start, Config.s.discord.templates.start, Theme.OK)
        presenceDirty()
    }

    fun stop() {
        if (!running) return
        running = false
        val d = Config.s.discord
        lifecycle(d.events.stop, d.templates.stop, Theme.BAD)
        DiscordApi.finalTopic(d.status.topicOffline)
        DiscordApi.stop(5000)
    }

    fun reload(server: MinecraftServer) {
        when {
            Config.s.discord.enabled && !running -> start(server)
            !Config.s.discord.enabled && running -> stop()
            running -> {
                apply()
                DiscordApi.reload(server)
                status()
            }
        }
    }

    fun presenceDirty() {
        if (running && presenceDue == 0L) presenceDue = System.currentTimeMillis() + Config.s.discord.status.presenceDebounceSeconds * 1000L
    }

    fun tick(server: MinecraftServer) {
        if (!running || !DiscordApi.ready) return
        val now = System.currentTimeMillis()
        if (presenceDue in 1..now) presence(server)
        if (now >= topicDue) topic(server)
    }

    fun status() {
        topicDue = 0L
        presenceDue = 0L
        ServerLifecycleHooks.getCurrentServer()?.let { presence(it); topic(it) }
    }

    fun visible(server: MinecraftServer): List<ServerPlayer> = server.playerList.players.filterNot(Vanish::active)

    private fun apply() {
        DiscordApi.inboundMax = Config.s.discord.chat.maxLength
        Config.s.discord.avatar.let { Avatars.configure(it.primary, it.fallback) }
    }

    private fun lifecycle(on: Boolean, template: String, color: Int) {
        if (on) DiscordApi.event(Embed(template, color, Config.s.discord.avatar.server.ifBlank { null }))
    }

    private fun presence(server: MinecraftServer) {
        presenceDue = 0L
        DiscordApi.presence(Templates.fill(Config.s.discord.status.presence, counts(server)))
    }

    private fun topic(server: MinecraftServer) {
        val s = Config.s.discord.status
        topicDue = System.currentTimeMillis() + s.topicMinutes * 60_000L
        val hours = (System.currentTimeMillis() - startedAt) / 3_600_000L
        DiscordApi.topic(Templates.fill(s.topic, counts(server) + mapOf("uptime" to "${hours}h", "countries" to ClaimsApi.countries().size.toString())))
    }

    private fun counts(server: MinecraftServer) = mapOf("online" to visible(server).size.toString(), "max" to server.maxPlayers.toString())
}
