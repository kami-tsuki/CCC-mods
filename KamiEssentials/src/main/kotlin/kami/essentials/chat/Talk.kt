package kami.essentials.chat

import kami.essentials.Config
import kami.essentials.Flag
import kami.essentials.KamiEssentials
import kami.essentials.Store
import kami.essentials.vanish.Vanish
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
    private val url = Regex("""https?://\S+""")
    private val partner = ConcurrentHashMap<UUID, UUID>()

    fun onChat(e: ServerChatEvent) {
        val p = e.player
        val country = Store[Flag.COUNTRY_CHAT, p.uuid]
        if (!Config.s.chat && !country) return
        e.isCanceled = true
        when {
            country -> country(p, e.rawText)
            Vanish.active(p) -> p.tell(chat.warn("You are invisible, so this was not sent. Use {/invis} to show yourself or {/msg} to whisper."))
            else -> broadcast(p, line(Names.player(p), e.rawText)) { true }
        }
    }

    fun direct(from: ServerPlayer?, to: ServerPlayer, text: String) {
        if (from === to) fail("Talking to yourself? Try someone else.")
        val sender = from?.let { Names.player(it) } ?: Component.literal("Server").withColor(Theme.ACCENT)
        val you = Component.literal("you").withColor(Theme.MUTED)
        to.tell(dm(sender, you, text).withStyle { it.withClickEvent(ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/r ")).withHoverEvent(hover("Click to reply")) })
        to.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.4f, 1.6f)
        from?.tell(dm(you, Names.player(to), text))
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
        val tag = Component.literal("[CC] ").withColor(Names.color(c)).withStyle { it.withHoverEvent(hover("Country chat of ${c.name}")) }
        broadcast(p, line(tag.append(Names.name(p)), text)) { Names.citizenship(it.uuid)?.country == c.country }
    }

    fun toggleCountry(p: ServerPlayer) {
        val c = Names.citizenship(p.uuid) ?: fail("You are not in a country.")
        val on = !Store[Flag.COUNTRY_CHAT, p.uuid]
        Store[Flag.COUNTRY_CHAT, p.uuid] = on
        p.tell(if (on) chat.ok("Country chat on. Your messages go to {${c.name}} only.") else chat.ok("Country chat off. Your messages go to everyone."))
    }

    private fun broadcast(from: ServerPlayer, line: Component, to: (ServerPlayer) -> Boolean) {
        from.server.sendSystemMessage(line)
        from.server.playerList.players.filter { it === from || it.chatVisibility == ChatVisiblity.FULL && to(it) }.forEach { it.tell(line) }
    }

    private fun line(name: Component, text: String): Component =
        Component.empty().append(name).append(Component.literal(Theme.SEP).withColor(Theme.MUTED)).append(body(text))

    private fun dm(from: Component, to: Component, text: String): MutableComponent = Component.empty()
        .append(Component.literal("✉ ").withColor(Theme.LINK))
        .append(from)
        .append(Component.literal(" → ").withColor(Theme.MUTED))
        .append(to)
        .append(Component.literal(Theme.SEP).withColor(Theme.MUTED))
        .append(body(text))

    private fun body(text: String): Component {
        val out = Component.empty()
        var from = 0
        url.findAll(text).forEach {
            out.append(Component.literal(text.substring(from, it.range.first)).withColor(Theme.TEXT))
            out.append(Component.literal(it.value).withStyle { s ->
                s.withColor(Theme.LINK).withUnderlined(true).withClickEvent(ClickEvent(ClickEvent.Action.OPEN_URL, it.value)).withHoverEvent(hover("Open link"))
            })
            from = it.range.last + 1
        }
        return out.append(Component.literal(text.substring(from)).withColor(Theme.TEXT))
    }

    private fun hover(text: String) = HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(text).withColor(Theme.TEXT))
}
