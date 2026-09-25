package kami.claims.social

import kami.claims.*
import kami.libs.chat.Chat
import kami.libs.chat.Tone
import kami.libs.chat.tell
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.UUID

object Mail {
    val chat = Chat.of("claims")
    private val server get() = ServerLifecycleHooks.getCurrentServer()

    fun direct(id: String, text: String, tone: Tone = Tone.INFO) {
        val online = runCatching { UUID.fromString(id) }.getOrNull()?.let { server?.playerList?.getPlayer(it) }
        if (online != null) online.tell(chat.of(tone, text))
        else Realm.of(id)?.members?.get(id)?.mail?.let { if (it.size < Config.s.mailLimit) it += "${tone.name}|$text" }
    }

    fun broadcast(c: Country, text: String, tone: Tone = Tone.INFO) = c.members.keys.forEach { direct(it, text, tone) }

    fun officers(c: Country, text: String, tone: Tone = Tone.INFO) = c.members.filterValues { it.rank >= Rank.OFFICER }.keys.forEach { direct(it, text, tone) }

    fun deliver(p: ServerPlayer) {
        val m = Realm.of(p.stringUUID)?.members?.get(p.stringUUID) ?: return
        if (m.mail.isEmpty()) return
        p.tell(chat.info("While you were away:"))
        m.mail.forEach { p.tell(Chat.row { Tone.of(it.substringBefore('|', "INFO")).let { t -> text("• ", t.mark); markup(it.substringAfter('|')) } }) }
        m.mail.clear()
        Realm.dirty = true
    }
}
