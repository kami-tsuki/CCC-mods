package kami.essentials.chat

import kami.libs.text.Text
import kami.libs.text.Phrase
import kami.essentials.Config
import kami.essentials.Flag
import kami.essentials.Store
import kami.essentials.vanish.Vanish
import kami.libs.chat.Theme
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.UUID

object Feed {
    private val vanilla = setOf("multiplayer.player.joined", "multiplayer.player.joined.renamed", "multiplayer.player.left")
    private val advancement = "chat.type.advancement."

    fun intercept(message: Component): Boolean {
        val contents = message.contents as? TranslatableContents ?: return false
        val joinLeave = contents.key in vanilla
        val death = contents.key.startsWith("death.")
        val achieved = contents.key.startsWith(advancement)
        if (!joinLeave && !death && !achieved) return false
        val id = (contents.args.firstOrNull() as? Component)?.style?.hoverEvent?.getValue(HoverEvent.Action.SHOW_ENTITY)?.id
        val hidden = id != null && id in Store.ids(Flag.INVISIBLE)
        return when {
            joinLeave -> Config.s.joinLeave || hidden
            death && (Config.s.deaths || hidden) -> death(message, id)
            achieved && (Config.s.achievements || hidden) -> achievement(message, id, contents.key)
            else -> false
        }
    }

    fun join(p: ServerPlayer, toggle: Phrase? = null) {
        if (Config.s.joinLeave || toggle != null) send(p, line(p, "[+]", Theme.OK, "kami_essentials.feed.joined"), toggle)
    }

    fun leave(p: ServerPlayer, toggle: Phrase? = null) {
        if (Config.s.joinLeave || toggle != null) send(p, line(p, "[-]", Theme.BAD, "kami_essentials.feed.left"), toggle)
    }

    private fun death(message: Component, id: UUID?): Boolean {
        val p = id?.let { ServerLifecycleHooks.getCurrentServer()?.playerList?.getPlayer(it) } ?: return false
        send(p, Component.empty().append(Component.literal("[☠] ").withColor(Theme.MUTED)).append(message.copy().withColor(Theme.TEXT)), null)
        return true
    }

    private fun achievement(message: Component, id: UUID?, key: String): Boolean {
        val p = id?.let { ServerLifecycleHooks.getCurrentServer()?.playerList?.getPlayer(it) } ?: return false
        val (icon, color) = when {
            key.endsWith("challenge") -> "[✪]" to Theme.WARN
            key.endsWith("goal") -> "[◎]" to Theme.ACCENT
            else -> "[✦]" to Theme.OK
        }
        send(p, Component.empty().append(Component.literal("$icon ").withColor(color).withStyle(ChatFormatting.BOLD)).append(message.copy().withColor(Theme.TEXT)), null)
        return true
    }

    private fun line(p: ServerPlayer, mark: String, color: Int, key: String): Component = Component.empty()
        .append(Component.literal(mark).withColor(color).withStyle(ChatFormatting.BOLD))
        .append(" ")
        .append(Text.msg(key, Names.player(p)).withColor(Theme.MUTED))

    private fun send(subject: ServerPlayer, line: Component, toggle: Phrase?) {
        val hidden = toggle == null && Vanish.active(subject)
        val reason = (toggle ?: Phrase.of("kami_essentials.feed.invisible")).component()
        val note = Component.empty().append(line).append(Component.literal(" (").append(reason).append(")").withColor(Theme.MUTED))
        subject.server.sendSystemMessage(if (hidden || toggle != null) note else line)
        subject.server.playerList.players.forEach { v ->
            val seer = v === subject || Vanish.sees(v)
            when {
                toggle != null -> v.sendSystemMessage(if (seer) note else line)
                !hidden -> v.sendSystemMessage(line)
                seer -> v.sendSystemMessage(note)
            }
        }
    }
}
