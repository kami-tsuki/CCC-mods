package kami.essentials.chat

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

    fun intercept(message: Component): Boolean {
        val contents = message.contents as? TranslatableContents ?: return false
        val joinLeave = contents.key in vanilla
        if (!joinLeave && !contents.key.startsWith("death.")) return false
        val id = (contents.args.firstOrNull() as? Component)?.style?.hoverEvent?.getValue(HoverEvent.Action.SHOW_ENTITY)?.id
        val hidden = id != null && id in Store.ids(Flag.INVISIBLE)
        return when {
            joinLeave -> Config.s.joinLeave || hidden
            Config.s.deaths || hidden -> death(message, id)
            else -> false
        }
    }

    fun join(p: ServerPlayer, toggle: String? = null) {
        if (Config.s.joinLeave || toggle != null) send(p, line(p, "[+]", Theme.OK, " joined"), toggle)
    }

    fun leave(p: ServerPlayer, toggle: String? = null) {
        if (Config.s.joinLeave || toggle != null) send(p, line(p, "[-]", Theme.BAD, " left"), toggle)
    }

    private fun death(message: Component, id: UUID?): Boolean {
        val p = id?.let { ServerLifecycleHooks.getCurrentServer()?.playerList?.getPlayer(it) } ?: return false
        send(p, Component.empty().append(Component.literal("[☠] ").withColor(Theme.MUTED)).append(message.copy().withColor(Theme.TEXT)), null)
        return true
    }

    private fun line(p: ServerPlayer, mark: String, color: Int, verb: String): Component = Component.empty()
        .append(Component.literal(mark).withColor(color).withStyle(ChatFormatting.BOLD))
        .append(" ")
        .append(Names.player(p))
        .append(Component.literal(verb).withColor(Theme.MUTED))

    private fun send(subject: ServerPlayer, line: Component, toggle: String?) {
        val hidden = toggle == null && Vanish.active(subject)
        val note = Component.empty().append(line).append(Component.literal(" (${toggle ?: "invisible"})").withColor(Theme.MUTED))
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
