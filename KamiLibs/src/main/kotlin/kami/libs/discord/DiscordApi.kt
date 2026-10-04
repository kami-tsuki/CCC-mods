package kami.libs.discord

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.minecraft.server.MinecraftServer

internal const val MESSAGE_MAX = 2000

internal enum class DiscordState { OFF, CONNECTING, READY, DISABLED }

class Post(val name: String, val avatar: String, val text: String, val mentions: Set<Long> = emptySet())
class Embed(val text: String, val color: Int, val icon: String? = null, val author: String? = null)
class Attachment(val name: String, val url: String)
class Inbound(
    val userId: Long, val userName: String, val text: String, val overflow: Boolean, val full: String,
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
    fun opt(name: String): String?
    fun reply(text: String)
}

class Member(val id: Long, val hasRole: Boolean)

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

    val ready: Boolean get() = Discord.state == DiscordState.READY && Discord.jda?.status == JDA.Status.CONNECTED
    val keys: DiscordKeys get() = Discord.keys
    val configured: Boolean get() = keys.let { it.token.isNotBlank() && it.guildId.toLongOrNull() != null && it.chatChannelId.toLongOrNull() != null }

    val broken: Boolean get() = !configured && KeyFile.broken

    fun register(feature: DiscordFeature) {
        Discord.features += feature
    }

    fun start(server: MinecraftServer, flushSeconds: Int) = Discord.start(server, flushSeconds)
    fun stop(timeoutMs: Long = Discord.CLOSE_MS) = Discord.stop(timeoutMs)
    fun reload(server: MinecraftServer, flushSeconds: Int) = Discord.reload(server, flushSeconds)

    fun chat(post: Post, sent: (Long) -> Unit = {}) = Discord.guard("chat") {
        if (!Discord.active) return@guard
        val text = Sanitize.outbound(post.text).take(MESSAGE_MAX)
        if (text.isBlank()) return@guard
        val name = Sanitize.name(post.name)
        val ids = post.mentions.toLongArray()
        Outbox.submit(Outbox.Channel.CHAT, Outbox.Task({ c ->
            c.sendMessage(text).setUsername(name).setAllowedMentions(emptySet()).mentionUsers(*ids).also { if (post.avatar.isNotBlank()) it.setAvatarUrl(post.avatar) }
        }, { c ->
            c.sendMessage("${Templates.code(name)}: $text").setAllowedMentions(emptySet()).mentionUsers(*ids)
        }) { id -> Discord.post { sent(id) } })
    }

    fun event(embed: Embed) = Discord.guard("event") {
        if (!Discord.active) return@guard
        val text = Sanitize.outbound(embed.text).take(1024)
        if (text.isBlank()) return@guard
        val icon = embed.icon?.takeIf { it.startsWith("http") && it.length <= 2000 }
        val author = embed.author?.let(Sanitize::name)
        val built = EmbedBuilder().setColor(embed.color).setDescription(text).also { if (author != null) it.setAuthor(author, null, icon) }.build()
        Outbox.submit(Outbox.Channel.CHAT, Outbox.embed(built))
    }

    fun console(line: String) = Discord.guard("console") {
        if (Discord.active && Discord.consoleOn) Outbox.console(Sanitize.console(line))
    }

    fun presence(text: String) = Discord.guard("presence") { Discord.presence(text) }
    fun topic(text: String) = Discord.guard("topic") { Discord.topic(text) }
    fun finalTopic(text: String) = Discord.guard("topic") { Discord.topic(text, true) }
    fun grant(userId: Long) = Discord.guard("role") { Discord.role(userId, true) }
    fun revoke(userId: Long) = Discord.guard("role") { Discord.role(userId, false) }
    fun members(ids: Collection<Long>, result: (Map<Long, Member>?) -> Unit) = Discord.guard("members", { Discord.post { result(null) } }) { Discord.members(ids, result) }
    fun holders(result: (Set<Long>?) -> Unit) = Discord.guard("holders", { Discord.post { result(null) } }) { Discord.holders(result) }
    fun bans(result: (Set<Long>?) -> Unit) = Discord.guard("bans", { Discord.post { result(null) } }) { Discord.bans(result) }
    fun banned(userId: Long, result: (Boolean?) -> Unit) = Discord.guard("ban", { Discord.post { result(null) } }) { Discord.banned(userId, result) }
}
