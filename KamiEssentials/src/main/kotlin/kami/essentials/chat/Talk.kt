package kami.essentials.chat

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
import net.minecraft.world.entity.player.ChatVisiblity
import net.neoforged.neoforge.event.ServerChatEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object Talk {
    val chat = Chat.of("essentials")
    private val partner = ConcurrentHashMap<UUID, UUID>()
    private const val CH_GLOBAL = "[GC]"
    private const val CH_COUNTRY = "[CC]"
    private const val CH_ADMIN = "[AC]"

    fun onChat(e: ServerChatEvent) {
        val p = e.player
        val country = Store[Flag.COUNTRY_CHAT, p.uuid]
        val admin = Store[Flag.ADMIN_CHAT, p.uuid] && Perms.has(p, Perms.ADMINCHAT)
        if (!Config.s.chat && !country && !admin) return
        e.isCanceled = true
        when {
            admin -> admin(p, e.rawText)
            country -> country(p, e.rawText)
            Vanish.active(p) -> p.tell(chat.warn("You are invisible, so this was not sent. Use {/invis} to show yourself or {/msg} to whisper."))
            else -> broadcast(p, line(channelTag(CH_GLOBAL, Theme.MUTED).append(Names.player(p)), e.rawText, p)) { true }
        }
    }

    fun direct(from: ServerPlayer?, to: ServerPlayer, text: String) {
        if (from === to) fail("Talking to yourself? Try someone else.")
        val sender = from?.let { Names.player(it) } ?: Component.literal("Server").withColor(Theme.ACCENT)
        val you = Component.literal("you").withColor(Theme.MUTED)
        to.tell(dm(sender, you, text, from).withStyle { it.withClickEvent(ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/r ")).withHoverEvent(hover("Click to reply")) })
        to.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.4f, 1.6f)
        from?.tell(dm(you, Names.player(to), text, from))
        KamiEssentials.LOG.info("{} -> {}: {}", from?.gameProfile?.name ?: "Server", to.gameProfile.name, text)
        if (from != null) {
            partner[to.uuid] = from.uuid
            partner[from.uuid] = to.uuid
        }
    }

    fun reply(from: ServerPlayer, text: String) {
        val to = partner[from.uuid]?.let(from.server.playerList::getPlayer)?.takeUnless { Vanish.hides(it, from) }
            ?: fail("Nobody to reply to. Use {/msg <player> <text>} first.")
        direct(from, to, text)
    }

    fun country(p: ServerPlayer, text: String) {
        val c = Names.citizenship(p.uuid)
        if (c == null) {
            Store[Flag.COUNTRY_CHAT, p.uuid] = false
            return p.tell(chat.warn("You are not in a country, so country chat is off now."))
        }
        val root = countryRoot(c)
        val rootLabel = c.parent ?: c.name
        val tag = channelTag(CH_COUNTRY, Names.color(c)).withStyle { it.withHoverEvent(hover("Country chat of $rootLabel (includes provinces)")) }
        broadcast(p, line(tag.append(Names.name(p)), text, p)) { viewer ->
            Names.citizenship(viewer.uuid)?.let(::countryRoot) == root
        }
    }

    fun toggleCountry(p: ServerPlayer) {
        val c = Names.citizenship(p.uuid) ?: fail("You are not in a country.")
        val on = !Store[Flag.COUNTRY_CHAT, p.uuid]
        Store[Flag.COUNTRY_CHAT, p.uuid] = on
        if (on && Store[Flag.ADMIN_CHAT, p.uuid]) Store[Flag.ADMIN_CHAT, p.uuid] = false
        p.tell(if (on) chat.ok("Country chat on. Your messages go to {${c.name}} only.") else chat.ok("Country chat off. Your messages go to everyone."))
    }

    fun admin(p: ServerPlayer, text: String) {
        if (!Perms.has(p, Perms.ADMINCHAT)) fail("You are not allowed to use admin chat.")
        val tag = channelTag(CH_ADMIN, Theme.WARN).withStyle { it.withHoverEvent(hover("Admin chat")) }
        broadcast(p, line(tag.append(Names.name(p)), text, p)) { Perms.has(it, Perms.ADMINCHAT) }
    }

    fun toggleAdmin(p: ServerPlayer) {
        if (!Perms.has(p, Perms.ADMINCHAT)) fail("You are not allowed to use admin chat.")
        val on = !Store[Flag.ADMIN_CHAT, p.uuid]
        Store[Flag.ADMIN_CHAT, p.uuid] = on
        if (on && Store[Flag.COUNTRY_CHAT, p.uuid]) Store[Flag.COUNTRY_CHAT, p.uuid] = false
        p.tell(if (on) chat.ok("Admin chat on. Your messages go to online admins only.") else chat.ok("Admin chat off. Your messages go to everyone."))
    }

    private fun broadcast(from: ServerPlayer, line: Component, to: (ServerPlayer) -> Boolean) {
        from.server.sendSystemMessage(line)
        from.server.playerList.players.filter { it === from || it.chatVisibility == ChatVisiblity.FULL && to(it) }.forEach { it.tell(line) }
    }

    private fun line(name: Component, text: String, speaker: ServerPlayer): Component =
        Component.empty().append(name).append(Component.literal(Theme.SEP).withColor(Theme.MUTED)).append(body(text, speaker))

    private fun channelTag(code: String, color: Int): MutableComponent = Component.literal("$code ").withColor(color)

    private fun countryRoot(c: Citizenship): String = c.parent?.lowercase() ?: c.country

    private fun dm(from: Component, to: Component, text: String, speaker: ServerPlayer?): MutableComponent = Component.empty()
        .append(Component.literal("✉ ").withColor(Theme.LINK))
        .append(from)
        .append(Component.literal(" → ").withColor(Theme.MUTED))
        .append(to)
        .append(Component.literal(Theme.SEP).withColor(Theme.MUTED))
        .append(body(text, speaker))

    private fun body(text: String, speaker: ServerPlayer?): Component = speaker?.let { InlineFeatures.render(it, text) } ?: Component.literal(text).withColor(Theme.TEXT)

    private fun hover(text: String) = HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(text).withColor(Theme.TEXT))
}
