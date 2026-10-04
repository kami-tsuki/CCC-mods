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
import kami.libs.discord.id
import kami.libs.discord.SlashCommand
import kami.libs.discord.SlashCtx
import kami.libs.discord.SlashOption
import kami.libs.log.Log
import kami.libs.text.Phrase
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.players.UserBanListEntry
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.UUID

object Gate : DiscordFeature {
    private val log = Log.of("discord")
    private val server get() = ServerLifecycleHooks.getCurrentServer()

    private fun text(key: String, vararg args: Any) = DiscordText.get("kami_essentials", Config.s.discord.language, "kami_essentials.discord.$key", *args)

    private fun kick(key: String): Component =
        Phrase.of("kami_essentials.discord.kick.$key").component().withColor(if (key == "banned") Theme.BAD_TEXT else Theme.TEXT)

    fun check(profile: GameProfile): Component? {
        if (!Config.s.discord.enabled) return null
        if (!DiscordApi.configured) return if (DiscordApi.broken) kick("unavailable") else null
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
                SlashCommand("lookup", "Find a link", listOf(SlashOption("user", "Discord user", false), SlashOption("player", "Minecraft name", false)), run = ::lookup),
                SlashCommand("remove", "Remove a link", listOf(SlashOption("user", "Discord user", false), SlashOption("player", "Minecraft name", false)), run = ::remove)
            )
        )
    )

    private fun verify(ctx: SlashCtx) {
        val d = Config.s.discord.verify
        if (Codes.limited(ctx.userId, d.attempts, d.windowMinutes)) return ctx.reply(text("verify.limited"))
        if (Links.byDiscord(ctx.userId) != null) return ctx.reply(text("verify.taken"))
        val code = ctx.opt("code").orEmpty()
        val pending = Codes.consume(code)
        if (pending == null) {
            Codes.failed(ctx.userId, d.windowMinutes)
            return ctx.reply(text("verify.invalid"))
        }
        var saved = false
        try {
            saved = Links.put(Link(pending.uuid.toString(), pending.name, ctx.userId, ctx.userName, System.currentTimeMillis()))
        } finally {
            if (!saved) Codes.restore(code, pending)
        }
        if (!saved) return ctx.reply(text("verify.unavailable"))
        DiscordApi.grant(ctx.userId)
        ctx.reply(text("verify.ok", pending.name))
    }

    private fun unverify(ctx: SlashCtx) {
        val link = Links.byDiscord(ctx.userId) ?: return ctx.reply(text("unverify.none"))
        unlink(link.id)
        ctx.reply(text("unverify.ok", link.name))
    }

    private fun players(ctx: SlashCtx) {
        val list = server?.let { Bot.visible(it) }.orEmpty()
        if (list.isEmpty()) return ctx.reply(text("players.none"))
        ctx.reply(text("players.list", list.size, list.joinToString(", ") { p -> p.gameProfile.name + (Names.citizenship(p.uuid)?.let { " [${it.name}]" } ?: "") }))
    }

    private fun find(ctx: SlashCtx): List<Link> {
        ctx.opt("user")?.filter(Char::isDigit)?.toLongOrNull()?.let { return listOfNotNull(Links.byDiscord(it)) }
        val name = ctx.opt("player")?.trim() ?: return emptyList()
        server?.profileCache?.get(name)?.orElse(null)?.let { p -> Links.get(p.id)?.let { return listOf(it) } }
        return Links.byName(name)
    }

    private fun target(ctx: SlashCtx, action: (Link) -> Unit) {
        val found = find(ctx)
        when (found.size) {
            0 -> ctx.reply(text("link.none"))
            1 -> action(found[0])
            else -> ctx.reply(text("link.ambiguous"))
        }
    }

    private fun lookup(ctx: SlashCtx) = target(ctx) { ctx.reply(text("link.found", it.name, it.discordName, it.discordId)) }

    private fun remove(ctx: SlashCtx) = target(ctx) {
        unlink(it.id)
        ctx.reply(text("unverify.ok", it.name))
    }

    override fun onReady() {
        server?.playerList?.players?.toList()?.forEach { p -> check(p.gameProfile)?.let(p.connection::disconnect) }
        reconcile()
    }

    override fun onLeave(userId: Long) {
        if (Links.byDiscord(userId) == null) return
        DiscordApi.banned(userId) { banned -> if (banned == true && Config.s.discord.verify.banSync) onBan(userId) else Links.byDiscord(userId)?.let { unlink(it.id) } }
    }

    override fun onBan(userId: Long) {
        if (Config.s.discord.verify.banSync) Links.byDiscord(userId)?.let { banAll(listOf(it)) }
    }

    fun join(p: ServerPlayer) {
        val link = Links.get(p.uuid) ?: return
        if (link.name != p.gameProfile.name) Links.rename(p.uuid, p.gameProfile.name)
        if (DiscordApi.ready) DiscordApi.grant(link.discordId)
    }

    private fun reconcile() {
        if (!DiscordApi.ready || Links.broken) return
        val links = Links.all()
        DiscordApi.members(links.map { it.discordId }) { found ->
            if (found != null) {
                val gone = ArrayList<UUID>()
                links.forEach { l ->
                    val m = found[l.discordId]
                    when {
                        m == null -> if (Links.byDiscord(l.discordId)?.uuid == l.uuid) gone += l.id
                        !m.hasRole -> DiscordApi.grant(l.discordId)
                    }
                }
                unlinkAll(gone)
            }
        }
        DiscordApi.holders { holders ->
            if (holders != null && !Links.broken) {
                val linked = Links.all().mapTo(HashSet()) { it.discordId }
                holders.filter { it !in linked }.forEach(DiscordApi::revoke)
            }
        }
        if (!Config.s.discord.verify.banSync) return
        val bans = server?.playerList?.bans ?: return
        dropAll(links.filter { bans.isBanned(GameProfile(it.id, it.name)) }.map { it.id })
        DiscordApi.bans { banned ->
            if (banned != null) banAll(Links.all().filter { it.discordId in banned })
        }
    }

    fun unlink(uuid: UUID) = unlinkAll(listOf(uuid))

    private fun unlinkAll(uuids: Collection<UUID>) = kickAll(dropAll(uuids).map { it.id }, "unlinked")

    private fun kickAll(uuids: Collection<UUID>, reason: String) =
        uuids.forEach { server?.playerList?.getPlayer(it)?.connection?.disconnect(kick(reason)) }

    fun mcBanned(uuid: UUID) {
        if (Config.s.discord.verify.banSync) dropAll(listOf(uuid))
    }

    private fun dropAll(uuids: Collection<UUID>): List<Link> {
        val removed = runCatching { Links.removeAll(uuids) }.onFailure { log.error("Cannot update the link file: {}", it.message) }.getOrDefault(emptyList())
        removed.forEach { DiscordApi.revoke(it.discordId) }
        return removed
    }

    private fun banAll(links: Collection<Link>) {
        val bans = server?.playerList?.bans
        val uuids = links.map { it.id }
        dropAll(uuids)
        links.forEach { bans?.add(UserBanListEntry(GameProfile(it.id, it.name), null, "Discord", null, null)) }
        kickAll(uuids, "banned")
    }
}
