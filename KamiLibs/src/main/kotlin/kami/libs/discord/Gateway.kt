package kami.libs.discord

import net.dv8tion.jda.api.entities.MessageType
import net.dv8tion.jda.api.events.guild.GuildBanEvent
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.events.session.ShutdownEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.CloseCode
import net.dv8tion.jda.api.entities.Member
import kami.libs.db.Db
import kami.libs.util.RateLimit
import java.util.UUID

internal class Gateway(private val epoch: Int) : ListenerAdapter() {
    private val live get() = epoch == Discord.epoch

    override fun onReady(e: ReadyEvent) {
        if (live) Discord.ready(e.jda, epoch)
    }

    override fun onShutdown(e: ShutdownEvent) {
        if (!live || !Discord.active) return
        val code = e.closeCode
        Discord.disable(epoch, if (code == CloseCode.DISALLOWED_INTENTS) "privileged intents (Message Content, Server Members) are not enabled in the developer portal" else "gateway closed (${code ?: "unknown"})", e.jda)
    }

    override fun onGuildLeave(e: GuildLeaveEvent) {
        if (live && e.guild.idLong == Discord.guildId) Discord.disable(epoch, "bot removed from guild", e.jda)
    }

    override fun onGuildMemberRemove(e: GuildMemberRemoveEvent) {
        if (live && e.guild.idLong == Discord.guildId) Discord.each { it.onLeave(e.user.idLong) }
    }

    override fun onGuildBan(e: GuildBanEvent) {
        if (live && e.guild.idLong == Discord.guildId) Discord.each { it.onBan(e.user.idLong) }
    }

    override fun onMessageReceived(e: MessageReceivedEvent) {
        if (!live || !e.isFromGuild || e.isWebhookMessage || e.author.isBot || e.channel.idLong != Discord.chatId) return
        val m = e.message
        if (m.type != MessageType.DEFAULT && m.type != MessageType.INLINE_REPLY) return
        val full = Sanitize.inbound(m.contentDisplay)
        if (full.isEmpty() && m.attachments.isEmpty()) return
        val (text, overflow) = Sanitize.cap(full, DiscordApi.inboundMax)
        val ref = m.referencedMessage
        val inbound = Inbound(
            e.author.idLong, Sanitize.inbound(e.member?.effectiveName ?: e.author.effectiveName), text, overflow, full,
            ref?.idLong ?: m.messageReference?.messageIdLong,
            ref?.let { Sanitize.inbound(it.member?.effectiveName ?: it.author.effectiveName) },
            ref?.let { Sanitize.inbound(it.contentDisplay).take(SNIPPET) },
            m.mentions.users.mapTo(HashSet()) { it.idLong },
            m.attachments.map { Attachment(Sanitize.inbound(it.fileName), it.url) }
        )
        Discord.each(true) { it.onMessage(inbound) }
    }

    override fun onSlashCommandInteraction(e: SlashCommandInteractionEvent) {
        if (!live || e.guild?.idLong != Discord.guildId) return
        val parent = Discord.commands().firstOrNull { it.name == e.name } ?: return
        if (!slash.allow(e.user.idLong)) return e.reply(text("slow")).setEphemeral(true).queue(null) { }
        if (!Db.isOpen) return e.reply(text("error")).setEphemeral(true).queue(null) { }
        val leaf = resolve(parent, e.subcommandGroup, e.subcommandName)
        val ephemeral = leaf?.public != true
        e.deferReply(ephemeral).queue()
        val ctx = Ctx(e, ephemeral)
        Discord.post {
            try {
                if ((parent.admin || leaf?.admin == true) && !ctx.admin) ctx.fail(text("denied"))
                else if (leaf == null || leaf.subcommands.isNotEmpty()) ctx.fail(text("error"))
                else if (leaf.linked && ctx.player == null) ctx.fail(ctx.text("kami_libs", "kami_libs.discord.mc.unlinked"))
                else leaf.run(ctx)
            } catch (ex: Throwable) {
                Discord.log.error("Slash command /{} failed: {}", e.name, ex.toString())
            } finally {
                if (!ctx.replied) ctx.fail(text("error"))
            }
        }
    }

    override fun onCommandAutoCompleteInteraction(e: CommandAutoCompleteInteractionEvent) {
        if (!live || e.guild?.idLong != Discord.guildId) return
        val parent = Discord.commands().firstOrNull { it.name == e.name }
        val leaf = parent?.let { resolve(it, e.subcommandGroup, e.subcommandName) }
        val complete = leaf?.options?.firstOrNull { it.name == e.focusedOption.name }?.complete
        if (complete == null || ((parent?.admin == true || leaf?.admin == true) && !isAdmin(e.member)) || !auto.allow(e.user.idLong)) return empty(e)
        val typed = e.focusedOption.value
        Discord.post {
            val choices = try {
                complete(typed).asSequence().map { it.take(CHOICE_MAX) }.filter { it.isNotBlank() }.distinct().take(CHOICES).toList()
            } catch (ex: Exception) {
                Discord.log.error("Autocomplete of /{} failed: {}", e.name, ex.toString())
                emptyList()
            }
            e.replyChoiceStrings(choices).queue(null) { }
        }
    }

    private fun empty(e: CommandAutoCompleteInteractionEvent) = e.replyChoiceStrings(emptyList()).queue(null) { }

    private fun resolve(parent: SlashCommand, group: String?, sub: String?): SlashCommand? {
        val mid = if (group == null) parent else parent.subcommands.firstOrNull { it.name == group } ?: return null
        return if (sub == null) mid else mid.subcommands.firstOrNull { it.name == sub }
    }


    private class Ctx(private val e: SlashCommandInteractionEvent, private val ephemeral: Boolean) : SlashCtx {
        var replied = false
        override val userId = e.user.idLong
        override val userName = e.member?.effectiveName ?: e.user.effectiveName
        override val admin = isAdmin(e.member)
        override val player: UUID? get() = Links.byDiscord(userId)?.id
        override val locale = if (e.userLocale.locale.startsWith("de")) "de_de" else DiscordText.FALLBACK

        override fun opt(name: String): String? = e.getOption(name)?.asString

        override fun reply(card: Card) {
            replied = true
            e.hook.editOriginalEmbeds(card.embed()).setAllowedMentions(emptySet()).queue()
        }

        override fun fail(text: String) {
            if (ephemeral) return reply(text)
            replied = true
            e.hook.deleteOriginal().queue({ e.hook.sendMessage(Sanitize.card(text).take(MESSAGE_MAX)).setEphemeral(true).setAllowedMentions(emptySet()).queue(null) { } }) { }
        }

        override fun background(action: () -> Unit) {
            replied = true
            val run = {
                try {
                    action()
                } catch (ex: Throwable) {
                    Discord.log.error("Slash command /{} failed: {}", e.name, ex.toString())
                    fail(DiscordText.get("kami_libs", DiscordText.FALLBACK, "kami_libs.discord.error"))
                }
            }
            if (!Discord.work(run)) fail(DiscordText.get("kami_libs", DiscordText.FALLBACK, "kami_libs.discord.error"))
        }

        override fun reply(text: String) {
            replied = true
            e.hook.editOriginal(Sanitize.card(text).take(MESSAGE_MAX)).setAllowedMentions(emptySet()).queue()
        }
    }

    private companion object {
        const val SNIPPET = 200
        const val CHOICES = 25
        const val CHOICE_MAX = 100
        val slash = RateLimit<Long>(3, 5000)
        val auto = RateLimit<Long>(10, 5000)

        fun isAdmin(member: Member?) = Discord.connected.adminRoleId.toLongOrNull()?.let { id -> member?.roles?.any { it.idLong == id } } == true

        fun text(key: String) = DiscordText.get("kami_libs", DiscordText.FALLBACK, "kami_libs.discord.$key")
    }
}
