package kami.libs.discord

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.minecraft.server.MinecraftServer
import java.util.UUID

internal const val MESSAGE_MAX = 2000

internal enum class DiscordState { OFF, CONNECTING, READY, DISABLED }

class Post(val name: String, val avatar: String, val text: String, val mentions: Set<Long> = emptySet())
class Embed(val text: String, val color: Int, val icon: String? = null, val author: String? = null)
class Attachment(val name: String, val url: String)
class Inbound(
    val userId: Long, val userName: String, val text: String, val overflow: Boolean, val full: String,
    val replyTo: Long?, val replyName: String?, val replySnippet: String?, val mentions: Set<Long>, val attachments: List<Attachment>, val channelId: Long = 0
)

class Room(val key: String, val name: String, val members: Set<Long>)

class SlashOption(
    val name: String, val description: String, val required: Boolean = true,
    val complete: ((String) -> List<String>)? = null, val choices: List<String> = emptyList()
)

class SlashCommand(
    val name: String, val description: String, val options: List<SlashOption> = emptyList(), val admin: Boolean = false,
    val subcommands: List<SlashCommand> = emptyList(), val public: Boolean = false, val linked: Boolean = false, val run: (SlashCtx) -> Unit = {}
)

interface SlashCtx {
    val userId: Long
    val userName: String
    val admin: Boolean
    val player: UUID?
    val locale: String
    fun opt(name: String): String?
    fun reply(text: String)
    fun reply(card: Card)
    fun fail(text: String) = reply(text)
    fun background(action: () -> Unit) = action()
    fun text(modId: String, key: String, vararg args: Any): String = DiscordText.get(modId, locale, key, *args)
}

class Member(val id: Long, val hasRole: Boolean)
class GuildInfo(val userName: String, val nickname: String, val avatar: String, val joined: Long, val roles: List<String>, val verified: Boolean)

interface DiscordFeature {
    fun commands(): List<SlashCommand> = emptyList()
    fun onReady() {}
    fun onMessage(m: Inbound) {}
    fun onLeave(userId: Long) {}
    fun onBan(userId: Long) {}
    fun onJoin(userId: Long) {}
    fun onRoom(room: String, m: Inbound) {}
}

object DiscordApi {
    @Volatile
    var inboundMax = 256

    val ready: Boolean get() = Discord.state == DiscordState.READY && Discord.jda?.status == JDA.Status.CONNECTED
    val keys: DiscordKeys get() = Discord.keys
    val configured: Boolean get() = keys.let { it.token.isNotBlank() && it.guildId.toLongOrNull() != null && it.chatChannelId.toLongOrNull() != null }

    val marketOn: Boolean get() = ready && Discord.marketId != 0L

    val broken: Boolean get() = !configured && KeyFile.broken

    fun register(feature: DiscordFeature) {
        Discord.features += feature
    }

    fun start(server: MinecraftServer, flushSeconds: Int) = Discord.start(server, flushSeconds)
    fun stop(timeoutMs: Long = Discord.CLOSE_MS) = Discord.stop(timeoutMs)
    fun reload(server: MinecraftServer, flushSeconds: Int) = Discord.reload(server, flushSeconds)

    fun chat(post: Post, sent: (Long) -> Unit = {}) = Discord.guard("chat") {
        if (Discord.active) chat(Outbox.Channel.CHAT, post, sent)
    }

    fun event(embed: Embed) = Discord.guard("event") {
        if (Discord.active) event(Outbox.Channel.CHAT, embed)
    }

    private fun chat(channel: Outbox.Channel, post: Post, sent: (Long) -> Unit) {
        val text = Sanitize.outbound(post.text).take(MESSAGE_MAX)
        if (text.isBlank()) return
        val name = Sanitize.name(post.name)
        val ids = post.mentions.toLongArray()
        Outbox.submit(channel, Outbox.Task({ c ->
            c.sendMessage(text).setUsername(name).setAllowedMentions(emptySet()).mentionUsers(*ids).also { if (post.avatar.isNotBlank()) it.setAvatarUrl(post.avatar) }
        }, { c ->
            c.sendMessage("${Templates.code(name)}: $text".take(MESSAGE_MAX)).setAllowedMentions(emptySet()).mentionUsers(*ids)
        }) { id -> Discord.post { sent(id) } })
    }

    private fun event(channel: Outbox.Channel, embed: Embed) {
        val text = Sanitize.outbound(embed.text).take(1024)
        if (text.isBlank()) return
        val icon = embed.icon?.takeIf { it.startsWith("http") && it.length <= 2000 }
        val author = embed.author?.let(Sanitize::name)
        val built = EmbedBuilder().setColor(embed.color).setDescription(text).also { if (author != null) it.setAuthor(author, null, icon) }.build()
        Outbox.submit(channel, Outbox.embed(built))
    }

    val roomsOn: Boolean get() = ready && Rooms.categoryId != 0L

    fun rooms(routes: Map<String, Long>) = Rooms.routes(routes)

    fun roomExists(channelId: Long): Boolean = runCatching { Rooms.exists(channelId) }.getOrDefault(false)

    fun syncRoom(room: Room, channelId: Long, done: (Long) -> Unit) = Discord.guard("room") {
        if (!roomsOn) return@guard
        Discord.members(room.members) { found -> if (found != null) Discord.guard("room") { Rooms.sync(room, channelId, found.keys, done) } }
    }

    fun roomMembers(channelId: Long): Set<Long>? = runCatching { Rooms.members(channelId) }.getOrNull()

    fun roomOrphans(): Map<Long, String> = runCatching { Rooms.orphans() }.getOrDefault(emptyMap())

    val roomCategory: Boolean get() = Rooms.categoryId != 0L

    fun deleteRoom(channelId: Long) = Discord.guard("room") { if (ready) Rooms.delete(channelId) }

    fun roomChat(key: String, post: Post, sent: (Long) -> Unit = {}) = Discord.guard("room-chat") {
        val id = Rooms.byKey[key] ?: return@guard
        if (Discord.active) chat(Outbox.Channel.of(id), post, sent)
    }

    fun roomEvent(key: String, embed: Embed) = Discord.guard("room-event") {
        val id = Rooms.byKey[key] ?: return@guard
        if (Discord.active) event(Outbox.Channel.of(id), embed)
    }

    fun market(card: Card): Boolean {
        var queued = false
        Discord.guard("market") { queued = Push.market(card) }
        return queued
    }

    fun dm(userId: Long, card: Card) = Discord.guard("dm") { Push.dm(userId, card) }

    fun console(line: String) = Discord.guard("console") {
        if (Discord.active && Discord.consoleOn) Outbox.console(Sanitize.console(line))
    }

    fun presence(text: String) = Discord.guard("presence") { Discord.presence(text) }
    fun topic(text: String) = Discord.guard("topic") { Discord.topic(text) }
    fun finalTopic(text: String) = Discord.guard("topic") { Discord.topic(text, true) }
    fun grant(userId: Long) = Discord.guard("role") { Discord.role(userId, true) }
    fun revoke(userId: Long) = Discord.guard("role") { Discord.role(userId, false) }
    fun members(ids: Collection<Long>, result: (Map<Long, Member>?) -> Unit) = Discord.guard("members", { Discord.post { result(null) } }) { Discord.members(ids, result) }
    fun info(userId: Long, timeoutMs: Long = 1500): GuildInfo? = runCatching { Discord.info(userId, timeoutMs) }.getOrNull()
    fun info(userId: Long, result: (GuildInfo?) -> Unit) = Discord.guard("info", { Discord.post { result(null) } }) { Discord.info(userId, result) }
    fun holders(result: (Set<Long>?) -> Unit) = Discord.guard("holders", { Discord.post { result(null) } }) { Discord.holders(result) }
    fun bans(result: (Set<Long>?) -> Unit) = Discord.guard("bans", { Discord.post { result(null) } }) { Discord.bans(result) }
    fun banned(userId: Long, result: (Boolean?) -> Unit) = Discord.guard("ban", { Discord.post { result(null) } }) { Discord.banned(userId, result) }
}
