package kami.essentials.discord

import kami.essentials.Config
import kami.essentials.Flag
import kami.essentials.Store
import kami.essentials.chat.Names
import kami.essentials.chat.Talk
import kami.libs.chat.Theme
import kami.libs.discord.Avatars
import kami.libs.discord.DiscordApi
import kami.libs.discord.DiscordFeature
import kami.libs.discord.Embed
import kami.libs.discord.Inbound
import kami.libs.discord.Links
import kami.libs.discord.Post
import kami.libs.discord.Templates
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.UUID

object Relay : DiscordFeature {
    private val sent = object : LinkedHashMap<Long, UUID>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, UUID>) = size > 512
    }
    private val mention = Regex("@(\\w{3,16})")

    override fun onReady() = Bot.status()

    fun chat(p: ServerPlayer, text: String) {
        val d = Config.s.discord
        if (!d.enabled || !d.chat.toDiscord) return
        val ids = HashSet<Long>()
        val out = mention.replace(text) { m -> Links.all().firstOrNull { it.name.equals(m.groupValues[1], true) }?.let { ids += it.discordId; "<@${it.discordId}>" } ?: m.value }
        DiscordApi.chat(Post(Templates.fill(d.templates.username, values(p)), Avatars.of(p.uuid), out, ids)) { sent[it] = p.uuid }
    }

    fun join(p: ServerPlayer) = feed(Config.s.discord.events.join, Config.s.discord.templates.join, Theme.OK, p)

    fun leave(p: ServerPlayer) = feed(Config.s.discord.events.leave, Config.s.discord.templates.leave, Theme.MUTED, p)

    fun death(p: ServerPlayer, message: String) =
        feed(Config.s.discord.events.death, Config.s.discord.templates.death, Theme.BAD, p, "message" to message)

    fun advancement(p: ServerPlayer, message: String, title: String, kind: String) {
        val color = when (kind) {
            "challenge" -> Theme.ACCENT
            "goal" -> Theme.LINK
            else -> Theme.TEXT
        }
        feed(Config.s.discord.events.advancement, Config.s.discord.templates.advancement, color, p, "message" to message, "advancement" to title)
    }

    override fun onMessage(m: Inbound) {
        if (!Config.s.discord.chat.toMinecraft) return
        val link = Links.byDiscord(m.userId) ?: return
        val server = ServerLifecycleHooks.getCurrentServer() ?: return
        val author = UUID.fromString(link.uuid)
        Talk.external(server, author, link.name, m)
        val targets = HashSet<UUID>()
        m.mentions.forEach { id -> Links.byDiscord(id)?.let { targets += UUID.fromString(it.uuid) } }
        m.replyTo?.let { sent[it] }?.let(targets::add)
        targets.remove(author)
        targets.mapNotNull(server.playerList::getPlayer).filterNot { Store[Flag.QUIET_DISCORD, it.uuid] }
            .forEach { it.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.5f, 1.2f) }
    }

    private fun feed(on: Boolean, template: String, color: Int, p: ServerPlayer, vararg extra: Pair<String, String>) {
        if (!Config.s.discord.enabled || !on) return
        DiscordApi.event(Embed(Templates.fill(template, values(p) + extra), color, Avatars.of(p.uuid)))
    }

    private fun values(p: ServerPlayer) = mapOf(
        "player" to p.gameProfile.name,
        "uuid" to p.uuid.toString(),
        "country" to (Names.citizenship(p.uuid)?.let { it.parent ?: it.name } ?: "")
    )
}
