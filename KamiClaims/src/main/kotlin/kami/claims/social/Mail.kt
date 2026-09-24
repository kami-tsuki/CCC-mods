package kami.claims.social

import kami.claims.*

import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.UUID

object Mail {
    private val server get() = ServerLifecycleHooks.getCurrentServer()

    fun direct(id: String, text: String) {
        val online = runCatching { UUID.fromString(id) }.getOrNull()?.let { server?.playerList?.getPlayer(it) }
        if (online != null) online.sendSystemMessage(Component.literal(text))
        else Realm.of(id)?.members?.get(id)?.mail?.let { if (it.size < Config.s.mailLimit) it += text }
    }

    fun broadcast(c: Country, text: String) = c.members.keys.forEach { direct(it, text) }

    fun officers(c: Country, text: String) = c.members.filterValues { it.rank >= Rank.OFFICER }.keys.forEach { direct(it, text) }

    fun deliver(p: ServerPlayer) {
        val m = Realm.of(p.stringUUID)?.members?.get(p.stringUUID) ?: return
        if (m.mail.isEmpty()) return
        m.mail.forEach { p.sendSystemMessage(Component.literal(it)) }
        m.mail.clear()
        Realm.dirty = true
    }
}
