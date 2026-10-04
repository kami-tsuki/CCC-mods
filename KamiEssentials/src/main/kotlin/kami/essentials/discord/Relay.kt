package kami.essentials.discord

import kami.essentials.Config
import kami.essentials.Flag
import kami.essentials.Store
import kami.essentials.chat.Names
import kami.essentials.chat.Talk
import kami.essentials.vanish.Vanish
import kami.libs.chat.Theme
import kami.libs.discord.Avatars
import kami.libs.discord.DiscordApi
import kami.libs.discord.DiscordFeature
import kami.libs.discord.Embed
import kami.libs.discord.Inbound
import kami.libs.discord.Link
import kami.libs.discord.Links
import kami.libs.discord.id
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
    private const val WINDOW_MS = 10_000L
    private const val BURST = 5
    private const val MAX_MENTIONS = 3
    private val mention = Regex("@(\\w{3,16})")
    private const val PING_GAP_MS = 3_000L
    private val recent = HashMap<UUID, ArrayDeque<Long>>()
    private val pinged = HashMap<UUID, Long>()

    override fun onReady() = Bot.status()

    fun chat(p: ServerPlayer, text: String) {
        val d = Config.s.discord
        if (!d.enabled || !d.chat.toDiscord || !allowed(p.uuid)) return
        val ids = LinkedHashSet<Long>()
        val out = Templates.outsideCode(text) { part, _ -> resolve(part, ids) }
        DiscordApi.chat(Post(Templates.fill(d.templates.username, values(p)), Avatars.of(p.uuid), out, ids)) { sent[it] = p.uuid }
    }

    fun join(p: ServerPlayer) = feed(Config.s.discord.events.join, Config.s.discord.templates.join, Theme.OK, p)

    fun leave(p: ServerPlayer) {
        recent.remove(p.uuid)
        pinged.remove(p.uuid)
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
        p.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.5f, 1.2f)
    }

    private fun allowed(id: UUID): Boolean {
        val now = System.currentTimeMillis()
        val times = recent.getOrPut(id) { ArrayDeque() }
        while (times.isNotEmpty() && times.first() < now - WINDOW_MS) times.removeFirst()
        if (times.size >= BURST) return false
        times.addLast(now)
        return true
    }

    private fun resolve(part: String, ids: MutableSet<Long>) = mention.replace(part) { m ->
        val found = Links.byName(m.groupValues[1])
        val link = found.singleOrNull() ?: found.firstOrNull { online(it) }
        if (link != null && (link.discordId in ids || ids.size < MAX_MENTIONS)) {
            ids += link.discordId
            "<@${link.discordId}>"
        } else m.value
    }

    private fun online(link: Link) = ServerLifecycleHooks.getCurrentServer()?.playerList?.getPlayer(link.id)?.let { !Vanish.active(it) } == true

    private fun feed(on: Boolean, template: String, color: Int, p: ServerPlayer, vararg extra: Pair<String, String>) {
        if (!Config.s.discord.enabled || !on) return
        val name = p.gameProfile.name
        val coded = values(p).mapValues { (k, v) -> if (k == "uuid") v else Templates.code(v) } + extra.map { (k, v) -> k to if (k == "message") v.replace(name, Templates.code(name)) else v }
        DiscordApi.event(Embed(Templates.fill(template, coded), color, Avatars.of(p.uuid), name))
    }

    private fun values(p: ServerPlayer) = mapOf(
        "player" to p.gameProfile.name,
        "uuid" to p.uuid.toString(),
        "country" to (Names.citizenship(p.uuid)?.let { it.parent ?: it.name } ?: "")
    )
}
