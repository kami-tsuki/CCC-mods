package kami.essentials.chat

import kami.libs.text.Text
import kami.libs.text.Phrase
import kami.essentials.Config
import kami.essentials.Flag
import kami.essentials.KamiEssentials
import kami.essentials.Perms
import kami.essentials.Store
import kami.essentials.vanish.Vanish
import kami.libs.claims.Citizenship
import kami.libs.chat.Chat
import kami.libs.chat.Theme
import kami.libs.chat.tell
import kami.libs.command.fail
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.ChatFormatting
import net.minecraft.world.entity.player.ChatVisiblity
import net.neoforged.neoforge.event.ServerChatEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object Talk {
    val chat = Chat.of("essentials")
    private val partner = ConcurrentHashMap<UUID, UUID>()

    fun onChat(e: ServerChatEvent) {
        val p = e.player
        val country = Store[Flag.COUNTRY_CHAT, p.uuid]
        val admin = Store[Flag.ADMIN_CHAT, p.uuid] && Perms.has(p, Perms.ADMINCHAT)
        if (!Config.s.chat && !country && !admin) return
        e.isCanceled = true
        when {
            admin -> admin(p, e.rawText)
            country -> country(p, e.rawText)
            Vanish.active(p) -> p.tell(chat.warn(Phrase.of("kami_essentials.talk.invisible", Phrase.value("/invis"), Phrase.value("/msg"))))
            else -> broadcast(p, line(channelTag(Config.s.channels.globalIcon, Config.s.channels.globalColor).append(Names.playerGlobal(p)), e.rawText, p)) { true }
        }
    }

    fun direct(from: ServerPlayer?, to: ServerPlayer, text: String) {
        if (from === to) fail(Phrase.of("kami_essentials.talk.self"))
        val sender = from?.let { Names.player(it) } ?: Text.msg("kami_essentials.talk.server").withColor(Theme.ACCENT)
        val you = Text.msg("kami_essentials.talk.you").withColor(Theme.MUTED)
        to.tell(dm(sender, you, text, from).withStyle { it.withClickEvent(ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/r ")).withHoverEvent(hover(Phrase.of("kami_essentials.talk.reply"))) })
        if (!Store[Flag.QUIET_DM, to.uuid]) to.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.4f, 1.6f)
        from?.tell(dm(you, Names.player(to), text, from))
        KamiEssentials.LOG.info("{} -> {}: {}", from?.gameProfile?.name ?: "Server", to.gameProfile.name, text)
        if (from != null) {
            partner[to.uuid] = from.uuid
            partner[from.uuid] = to.uuid
        }
    }

    fun reply(from: ServerPlayer, text: String) {
        val to = partner[from.uuid]?.let(from.server.playerList::getPlayer)?.takeUnless { Vanish.hides(it, from) }
            ?: fail(Phrase.of("kami_essentials.talk.no_partner", Phrase.value("/msg <player> <text>")))
        direct(from, to, text)
    }

    fun country(p: ServerPlayer, text: String) {
        val c = Names.citizenship(p.uuid)
        if (c == null) {
            Store[Flag.COUNTRY_CHAT, p.uuid] = false
            return p.tell(chat.warn(Phrase.of("kami_essentials.talk.country.lost")))
        }
        val root = countryRoot(c)
        val rootLabel = c.parent ?: c.name
        val tag = channelTag(Config.s.channels.countryIcon, Config.s.channels.countryColor).withStyle { it.withHoverEvent(hover(Phrase.of("kami_essentials.talk.country.tooltip", rootLabel))) }
        broadcast(p, line(tag.append(Names.playerCountry(p)), text, p)) { viewer ->
            Names.citizenship(viewer.uuid)?.let(::countryRoot) == root
        }
    }

    fun channels(p: ServerPlayer) = listOfNotNull(
        "global",
        "country".takeIf { Names.citizenship(p.uuid) != null && Perms.has(p, Perms.COUNTRYCHAT) },
        "admin".takeIf { Perms.has(p, Perms.ADMINCHAT) }
    )

    fun channel(p: ServerPlayer) = when {
        Store[Flag.ADMIN_CHAT, p.uuid] && Perms.has(p, Perms.ADMINCHAT) -> "admin"
        Store[Flag.COUNTRY_CHAT, p.uuid] -> "country"
        else -> "global"
    }

    fun channel(p: ServerPlayer, to: String) {
        if (to !in channels(p)) return
        Store[Flag.COUNTRY_CHAT, p.uuid] = to == "country"
        Store[Flag.ADMIN_CHAT, p.uuid] = to == "admin"
    }

    fun toggleCountry(p: ServerPlayer) {
        val c = Names.citizenship(p.uuid) ?: fail(Phrase.of("kami_essentials.talk.no_country"))
        val on = !Store[Flag.COUNTRY_CHAT, p.uuid]
        Store[Flag.COUNTRY_CHAT, p.uuid] = on
        if (on && Store[Flag.ADMIN_CHAT, p.uuid]) Store[Flag.ADMIN_CHAT, p.uuid] = false
        p.tell(if (on) chat.ok(Phrase.of("kami_essentials.talk.country.on", Phrase.value(c.name))) else chat.ok(Phrase.of("kami_essentials.talk.country.off")))
    }

    fun admin(p: ServerPlayer, text: String) {
        if (!Perms.has(p, Perms.ADMINCHAT)) fail(Phrase.of("kami_essentials.talk.admin.denied"))
        val tag = channelTag(Config.s.channels.adminIcon, Config.s.channels.adminColor).withStyle { it.withHoverEvent(hover(Phrase.of("kami_essentials.talk.admin.tooltip"))) }
        broadcast(p, line(tag.append(Names.name(p)), text, p)) { Perms.has(it, Perms.ADMINCHAT) }
    }

    fun toggleAdmin(p: ServerPlayer) {
        if (!Perms.has(p, Perms.ADMINCHAT)) fail(Phrase.of("kami_essentials.talk.admin.denied"))
        val on = !Store[Flag.ADMIN_CHAT, p.uuid]
        Store[Flag.ADMIN_CHAT, p.uuid] = on
        if (on && Store[Flag.COUNTRY_CHAT, p.uuid]) Store[Flag.COUNTRY_CHAT, p.uuid] = false
        p.tell(if (on) chat.ok(Phrase.of("kami_essentials.talk.admin.on")) else chat.ok(Phrase.of("kami_essentials.talk.admin.off")))
    }

    private fun broadcast(from: ServerPlayer, line: Component, to: (ServerPlayer) -> Boolean) {
        from.server.sendSystemMessage(line)
        from.server.playerList.players.filter { it === from || it.chatVisibility == ChatVisiblity.FULL && to(it) }.forEach { it.tell(line) }
    }

    private fun line(name: Component, text: String, speaker: ServerPlayer): Component =
        Component.empty().append(name).append(Component.literal(Theme.SEP).withColor(Theme.MUTED)).append(body(text, speaker))

    private fun channelTag(icon: String, color: Int): MutableComponent = Component.empty()
        .append(Component.literal("[").withColor(color).withStyle(ChatFormatting.BOLD))
        .append(Component.literal(icon).withColor(color).withStyle(ChatFormatting.BOLD))
        .append(Component.literal("] ").withColor(color).withStyle(ChatFormatting.BOLD))

    private fun countryRoot(c: Citizenship): String = c.parent?.lowercase() ?: c.country

    private fun dm(from: Component, to: Component, text: String, speaker: ServerPlayer?): MutableComponent = Component.empty()
        .append(Component.literal("✉ ").withColor(Theme.LINK))
        .append(from)
        .append(Component.literal(" → ").withColor(Theme.MUTED))
        .append(to)
        .append(Component.literal(Theme.SEP).withColor(Theme.MUTED))
        .append(body(text, speaker))

    private fun body(text: String, speaker: ServerPlayer?): Component = speaker?.let { InlineFeatures.render(it, text) } ?: Component.literal(text).withColor(Theme.TEXT)

    private fun hover(text: Phrase) = HoverEvent(HoverEvent.Action.SHOW_TEXT, text.component().withColor(Theme.TEXT))
}
