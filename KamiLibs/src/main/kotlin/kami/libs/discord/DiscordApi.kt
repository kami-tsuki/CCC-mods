package kami.libs.discord

import net.dv8tion.jda.api.EmbedBuilder
import net.minecraft.server.MinecraftServer

enum class DiscordState { OFF, CONNECTING, READY, DISABLED }

class Post(val name: String, val avatar: String, val text: String, val mentions: Set<Long> = emptySet())
class Embed(val text: String, val color: Int, val icon: String? = null)
class Attachment(val name: String, val url: String)
class Inbound(
    val userId: Long, val userName: String, val messageId: Long, val text: String, val overflow: Boolean, val full: String,
    val replyTo: Long?, val replyName: String?, val replySnippet: String?, val mentions: Set<Long>, val attachments: List<Attachment>
)

class SlashOption(val name: String, val description: String, val required: Boolean = true)
class SlashCommand(
    val name: String, val description: String, val options: List<SlashOption> = emptyList(), val admin: Boolean = false,
    val subcommands: List<SlashCommand> = emptyList(), val run: (SlashCtx) -> Unit = {}
)

interface SlashCtx {
    val userId: Long
    val userName: String
    val admin: Boolean
    val subcommand: String?
    fun opt(name: String): String?
    fun reply(text: String)
}

class Member(val id: Long, val name: String, val hasRole: Boolean)

interface DiscordFeature {
    fun commands(): List<SlashCommand> = emptyList()
    fun onReady() {}
    fun onMessage(m: Inbound) {}
    fun onLeave(userId: Long) {}
    fun onBan(userId: Long) {}
}

object DiscordApi {
    @Volatile
    var inboundMax = 256

    val state: DiscordState get() = Discord.state
    val ready: Boolean get() = Discord.state == DiscordState.READY
    val keys: DiscordKeys get() = Discord.keys

    fun register(feature: DiscordFeature) {
        Discord.features += feature
    }

    fun start(server: MinecraftServer, flushSeconds: Int) = Discord.start(server, flushSeconds)
    fun stop(timeoutMs: Long = 5000) = Discord.stop(timeoutMs)
    fun reload(server: MinecraftServer) = Discord.reload(server)

    fun chat(post: Post, sent: (Long) -> Unit = {}) {
        if (!Discord.active) return
        val text = Sanitize.outbound(post.text).take(2000)
        if (text.isBlank()) return
        val name = Sanitize.name(post.name)
        val ids = post.mentions.toLongArray()
        Outbox.submit(Outbox.Channel.CHAT, Outbox.Task({ c ->
            c.sendMessage(text).setUsername(name).setAllowedMentions(emptySet()).mentionUsers(*ids).also { if (post.avatar.isNotBlank()) it.setAvatarUrl(post.avatar) }
        }) { id -> Discord.post { sent(id) } })
    }

    fun event(embed: Embed) {
        if (!Discord.active) return
        val text = Sanitize.outbound(embed.text).take(256)
        if (text.isBlank()) return
        val built = EmbedBuilder().setColor(embed.color).setAuthor(text, null, embed.icon?.takeIf { it.isNotBlank() }).build()
        Outbox.submit(Outbox.Channel.CHAT, Outbox.Task({ it.sendMessageEmbeds(built) }))
    }

    fun console(line: String) {
        if (!Discord.active || !Discord.consoleOn) return
        Outbox.console(Sanitize.console(line))
    }

    fun presence(text: String) = Discord.presence(text)
    fun topic(text: String) = Discord.topic(text)
    fun grant(userId: Long) = Discord.role(userId, true)
    fun revoke(userId: Long) = Discord.role(userId, false)
    fun members(ids: Collection<Long>, result: (Map<Long, Member>?) -> Unit) = Discord.members(ids, result)
    fun bans(result: (Set<Long>?) -> Unit) = Discord.bans(result)
    fun banned(userId: Long, result: (Boolean?) -> Unit) = Discord.banned(userId, result)
}
