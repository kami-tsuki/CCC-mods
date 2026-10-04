package kami.libs.discord

import net.dv8tion.jda.api.entities.MessageType
import net.dv8tion.jda.api.events.guild.GuildBanEvent
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.events.session.ShutdownEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.CloseCode

internal class Gateway(private val epoch: Int) : ListenerAdapter() {
    private val live get() = epoch == Discord.epoch

    override fun onReady(e: ReadyEvent) {
        if (live) Discord.ready(e.jda, epoch)
    }

    override fun onShutdown(e: ShutdownEvent) {
        if (!live || !Discord.active) return
        val code = e.closeCode
        Discord.disable(epoch, if (code == CloseCode.DISALLOWED_INTENTS) "privileged intents (Message Content, Server Members) are not enabled in the developer portal" else "gateway closed (${code ?: "unknown"})")
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
            e.author.idLong, Sanitize.inbound(e.member?.effectiveName ?: e.author.effectiveName), m.idLong, text, overflow, full,
            ref?.idLong ?: m.messageReference?.messageIdLong,
            ref?.let { Sanitize.inbound(it.member?.effectiveName ?: it.author.effectiveName) },
            ref?.let { Sanitize.inbound(it.contentDisplay).take(SNIPPET) },
            m.mentions.users.mapTo(HashSet()) { it.idLong },
            m.attachments.map { Attachment(Sanitize.inbound(it.fileName), it.url) }
        )
        Discord.each { it.onMessage(inbound) }
    }

    override fun onSlashCommandInteraction(e: SlashCommandInteractionEvent) {
        if (!live || e.guild?.idLong != Discord.guildId) return
        val parent = Discord.commands().firstOrNull { it.name == e.name } ?: return
        val sub = parent.subcommands.firstOrNull { it.name == e.subcommandName }
        e.deferReply(true).queue()
        val ctx = Ctx(e)
        Discord.post {
            if ((parent.admin || sub?.admin == true) && !ctx.admin) ctx.reply(DiscordText.get("kami_libs", "en_us", "kami_libs.discord.denied"))
            else try {
                (sub ?: parent).run(ctx)
            } catch (ex: Exception) {
                Discord.log.error("Slash command /{} failed: {}", e.name, ex.toString())
                if (!ctx.replied) ctx.reply(DiscordText.get("kami_libs", "en_us", "kami_libs.discord.error"))
            }
        }
    }

    private class Ctx(private val e: SlashCommandInteractionEvent) : SlashCtx {
        var replied = false
        override val userId = e.user.idLong
        override val userName = e.member?.effectiveName ?: e.user.effectiveName
        override val admin = Discord.connected.adminRoleId.toLongOrNull()?.let { id -> e.member?.roles?.any { it.idLong == id } } == true
        override val subcommand: String? = e.subcommandName

        override fun opt(name: String): String? = e.getOption(name)?.asString

        override fun reply(text: String) {
            replied = true
            e.hook.editOriginal(text.take(2000)).queue()
        }
    }

    private companion object {
        const val SNIPPET = 200
    }
}
