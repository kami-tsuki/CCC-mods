package kami.essentials.discord

import com.mojang.authlib.GameProfile
import kami.essentials.Config
import kami.essentials.chat.Names
import kami.libs.chat.Theme
import kami.libs.discord.Codes
import kami.libs.discord.DiscordApi
import kami.libs.discord.DiscordFeature
import kami.libs.discord.DiscordText
import kami.libs.discord.Link
import kami.libs.discord.Links
import kami.libs.discord.SlashCommand
import kami.libs.discord.SlashCtx
import kami.libs.discord.SlashOption
import kami.libs.text.Phrase
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.players.UserBanListEntry
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.UUID

object Gate : DiscordFeature {
    private val server get() = ServerLifecycleHooks.getCurrentServer()

    private fun text(key: String, vararg args: Any) = DiscordText.get("kami_essentials", Config.s.discord.language, "kami_essentials.discord.$key", *args)

    private fun kick(key: String): Component = Phrase.of("kami_essentials.discord.kick.$key").component()

    fun check(profile: GameProfile): Component? {
        if (!Config.s.discord.enabled) return null
        if (Links.broken) return kick("unavailable")
        if (Links.get(profile.id) != null) return null
        if (!DiscordApi.ready) return kick("unavailable")
        val code = Codes.issue(profile.id, profile.name, Config.s.discord.verify.codeMinutes)
        val out = Phrase.of("kami_essentials.discord.kick.title").component().withColor(Theme.VALUE)
            .append("\n\n").append(Component.literal(code).withColor(Theme.ACCENT).withStyle { it.withBold(true) })
            .append("\n\n").append(Phrase.of("kami_essentials.discord.kick.verify", code).component().withColor(Theme.TEXT))
        val invite = DiscordApi.keys.inviteLink
        if (invite.isNotBlank()) out.append("\n").append(Phrase.of("kami_essentials.discord.kick.invite", invite).component().withColor(Theme.LINK))
        return out
    }

    override fun commands() = listOf(
        SlashCommand("verify", "Link your Minecraft account", listOf(SlashOption("code", "Code shown when joining the server")), run = ::verify),
        SlashCommand("unverify", "Unlink your Minecraft account", run = ::unverify),
        SlashCommand("players", "Show who is online", run = ::players),
        SlashCommand(
            "link", "Manage account links", admin = true, subcommands = listOf(
                SlashCommand("lookup", "Find a link", listOf(SlashOption("user", "Discord user", false), SlashOption("player", "Minecraft name", false)), true, run = ::lookup),
                SlashCommand("remove", "Remove a link", listOf(SlashOption("user", "Discord user", false), SlashOption("player", "Minecraft name", false)), true, run = ::remove)
            )
        )
    )

    private fun verify(ctx: SlashCtx) {
        val d = Config.s.discord.verify
        if (Codes.limited(ctx.userId, d.attempts, d.windowMinutes)) return ctx.reply(text("verify.limited"))
        if (Links.byDiscord(ctx.userId) != null) return ctx.reply(text("verify.taken"))
        val pending = Codes.consume(ctx.opt("code").orEmpty().trim().uppercase())
        if (pending == null) {
            Codes.failed(ctx.userId)
            return ctx.reply(text("verify.invalid"))
        }
        Links.put(Link(pending.uuid.toString(), pending.name, ctx.userId, ctx.userName, System.currentTimeMillis()))
        DiscordApi.grant(ctx.userId)
        ctx.reply(text("verify.ok", pending.name))
    }

    private fun unverify(ctx: SlashCtx) {
        val link = Links.byDiscord(ctx.userId) ?: return ctx.reply(text("unverify.none"))
        unlink(UUID.fromString(link.uuid), "unlinked")
        ctx.reply(text("unverify.ok", link.name))
    }

    private fun players(ctx: SlashCtx) {
        val list = server?.let { Bot.visible(it) }.orEmpty()
        if (list.isEmpty()) return ctx.reply(text("players.none"))
        ctx.reply(text("players.list", list.size, list.joinToString(", ") { p -> p.gameProfile.name + (Names.citizenship(p.uuid)?.let { " [${it.name}]" } ?: "") }))
    }

    private fun find(ctx: SlashCtx): Link? =
        ctx.opt("user")?.filter(Char::isDigit)?.toLongOrNull()?.let(Links::byDiscord)
            ?: ctx.opt("player")?.trim()?.let { n -> Links.all().firstOrNull { it.name.equals(n, true) } }

    private fun lookup(ctx: SlashCtx) {
        if (!ctx.admin) return ctx.reply(text("link.forbidden"))
        val link = find(ctx) ?: return ctx.reply(text("link.none"))
        ctx.reply(text("link.found", link.name, link.discordName, link.discordId))
    }

    private fun remove(ctx: SlashCtx) {
        if (!ctx.admin) return ctx.reply(text("link.forbidden"))
        val link = find(ctx) ?: return ctx.reply(text("link.none"))
        unlink(UUID.fromString(link.uuid), "unlinked")
        ctx.reply(text("link.removed", link.name))
    }

    override fun onReady() = reconcile()

    override fun onLeave(userId: Long) {
        if (Links.byDiscord(userId) == null) return
        DiscordApi.banned(userId) { banned -> if (banned == true) onBan(userId) else Links.byDiscord(userId)?.let { unlink(UUID.fromString(it.uuid), "unlinked") } }
    }

    override fun onBan(userId: Long) {
        if (Config.s.discord.verify.banSync) Links.byDiscord(userId)?.let(::banFromDiscord)
    }

    fun join(p: ServerPlayer) {
        val link = Links.get(p.uuid) ?: return
        if (link.name != p.gameProfile.name) Links.rename(p.uuid, p.gameProfile.name)
        if (DiscordApi.ready) DiscordApi.grant(link.discordId)
    }

    fun reconcile() {
        if (!DiscordApi.ready) return
        val links = Links.all()
        DiscordApi.members(links.map { it.discordId }) { found ->
            if (found != null) links.forEach { l ->
                val m = found[l.discordId]
                if (m == null) { if (Links.byDiscord(l.discordId)?.uuid == l.uuid) unlink(UUID.fromString(l.uuid), "unlinked") }
                else if (!m.hasRole) DiscordApi.grant(l.discordId)
            }
        }
        if (!Config.s.discord.verify.banSync) return
        val bans = server?.playerList?.bans ?: return
        links.filter { bans.isBanned(GameProfile(UUID.fromString(it.uuid), it.name)) }.forEach { drop(UUID.fromString(it.uuid)) }
        DiscordApi.bans { banned ->
            if (banned != null) Links.all().filter { it.discordId in banned }.forEach(::banFromDiscord)
        }
    }

    fun unlink(uuid: UUID, reason: String) {
        drop(uuid)
        server?.playerList?.getPlayer(uuid)?.connection?.disconnect(kick(reason))
    }

    fun mcBanned(uuid: UUID) {
        if (Config.s.discord.verify.banSync) drop(uuid)
    }

    private fun drop(uuid: UUID) {
        Links.remove(uuid)?.let { DiscordApi.revoke(it.discordId) }
    }

    private fun banFromDiscord(link: Link) {
        val uuid = UUID.fromString(link.uuid)
        server?.playerList?.bans?.add(UserBanListEntry(GameProfile(uuid, link.name), null, "Discord", null, null))
        unlink(uuid, "banned")
    }
}
