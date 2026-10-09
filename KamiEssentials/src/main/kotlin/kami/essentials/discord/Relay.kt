package kami.essentials.discord

import com.mojang.authlib.GameProfile
import kami.essentials.Config
import kami.essentials.Flag
import kami.essentials.Store
import kami.essentials.admin.Mutes
import kami.essentials.chat.Names
import kami.essentials.chat.Talk
import kami.essentials.vanish.Vanish
import kami.libs.chat.Theme
import kami.libs.discord.Avatars
import kami.libs.discord.DiscordApi
import kami.libs.discord.DiscordBans
import kami.libs.discord.DiscordFeature
import kami.libs.discord.Embed
import kami.libs.discord.Inbound
import kami.libs.discord.Link
import kami.libs.discord.Links
import kami.libs.discord.id
import kami.libs.discord.Post
import kami.libs.discord.Templates
import kami.libs.stats.Stats
import kami.libs.util.RateLimit
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object Relay : DiscordFeature {
    private val sent = object : LinkedHashMap<Long, UUID>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, UUID>) = size > 512
    }
    private const val MAX_MENTIONS = 3
    private val mention = Regex("@(\\w{3,16})")
    private const val PING_GAP_MS = 3_000L
    private val recent = RateLimit<UUID>(5, 10_000L)
    private val incoming = RateLimit<UUID>(5, 10_000L)
    private const val MENTION_GAP_MS = 60_000L
    private val pinged = HashMap<UUID, Long>()
    private val mentioned = ConcurrentHashMap<UUID, Long>()
    private const val MAX_SPOKE = 2000
    private val spoke = ConcurrentHashMap<UUID, Long>()

    fun lastMessage(id: UUID) = spoke[id] ?: 0L

    private fun traffic(key: String, id: UUID) {
        spoke[id] = System.currentTimeMillis()
        if (spoke.size > MAX_SPOKE) spoke.entries.minByOrNull { it.value }?.let { spoke.remove(it.key) }
        Stats.add(key, "p:$id")
    }

    override fun onReady() = Bot.status()

    fun chat(p: ServerPlayer, text: String) {
        val d = Config.s.discord
        if (!d.enabled || !d.chat.toDiscord || !recent.allow(p.uuid)) return
        traffic("discord.out", p.uuid)
        val ids = LinkedHashSet<Long>()
        val out = Templates.outsideCode(text) { part, _ -> resolve(part.replace("](", "]\\("), ids) }
        DiscordApi.chat(Post(Templates.fill(d.templates.username, values(p)), Avatars.of(p.uuid), out, ids)) { sent[it] = p.uuid }
    }

    fun join(p: ServerPlayer) = feed(Config.s.discord.events.join, Config.s.discord.templates.join, Theme.OK, p)

    fun leave(p: ServerPlayer) {
        recent.forget(p.uuid)
        incoming.forget(p.uuid)
        pinged.remove(p.uuid)
        mentioned.remove(p.uuid)
        feed(Config.s.discord.events.leave, Config.s.discord.templates.leave, Theme.MUTED, p)
    }

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
        val d = Config.s.discord
        if (!d.enabled || !d.chat.toMinecraft) return
        val link = Links.byDiscord(m.userId) ?: return
        val server = ServerLifecycleHooks.getCurrentServer() ?: return
        val author = link.id
        if (Mutes[author] != null || DiscordBans.banned(m.userId) != null || server.playerList.bans.isBanned(GameProfile(author, link.name)) || !incoming.allow(author)) return
        traffic("discord.in", author)
        Talk.external(server, author, link.name, m)
        val targets = HashSet<UUID>()
        m.mentions.forEach { id -> Links.byDiscord(id)?.let { targets += it.id } }
        m.replyTo?.let { sent[it] }?.let(targets::add)
        targets.remove(author)
        targets.mapNotNull(server.playerList::getPlayer).filterNot { Store[Flag.QUIET_DISCORD, it.uuid] }
            .forEach { ping(it) }
    }

    private fun ping(p: ServerPlayer) {
        val now = System.currentTimeMillis()
        if (now - (pinged[p.uuid] ?: 0L) < PING_GAP_MS) return
        pinged[p.uuid] = now
        Stats.add("discord.ping", "p:${p.uuid}")
        p.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.5f, 1.2f)
    }

    private fun resolve(part: String, ids: MutableSet<Long>) = mention.replace(part) { m ->
        val found = Links.byName(m.groupValues[1])
        val link = found.singleOrNull() ?: found.firstOrNull { online(it) }
        val now = System.currentTimeMillis()
        if (link != null && !Store[Flag.QUIET_DISCORD, link.id] && (link.discordId in ids || ids.size < MAX_MENTIONS && now - (mentioned[link.id] ?: 0L) >= MENTION_GAP_MS)) {
            if (ids.add(link.discordId)) mentioned[link.id] = now
            "<@${link.discordId}>"
        } else m.value
    }

    private fun online(link: Link) = ServerLifecycleHooks.getCurrentServer()?.playerList?.getPlayer(link.id)?.let { !Vanish.active(it) } == true

    private fun feed(on: Boolean, template: String, color: Int, p: ServerPlayer, vararg extra: Pair<String, String>) {
        if (!Config.s.discord.enabled || !on) return
        val name = p.gameProfile.name
        val shown = values(p) + extra.map { (k, v) -> k to if (k == "message") v.replace(name, Templates.player(name)) else v }
        DiscordApi.event(Embed(Templates.fillCoded(template, shown, extra.mapTo(mutableSetOf("uuid")) { it.first }), color, Avatars.of(p.uuid), name))
    }

    private fun values(p: ServerPlayer) = mapOf(
        "player" to p.gameProfile.name,
        "uuid" to p.uuid.toString(),
        "country" to (Names.citizenship(p.uuid)?.let { it.parent ?: it.name } ?: "")
    )
}
