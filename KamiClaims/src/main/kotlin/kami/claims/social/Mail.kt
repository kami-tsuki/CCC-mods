package kami.claims.social

import kami.claims.*
import kami.libs.chat.Chat
import kami.libs.chat.Tone
import kami.libs.chat.tell
import kami.libs.text.Phrase
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.UUID

object Mail {
    val chat = Chat.of("claims")
    private val server get() = ServerLifecycleHooks.getCurrentServer()

    fun direct(id: String, text: Phrase, tone: Tone = Tone.INFO) {
        val online = runCatching { UUID.fromString(id) }.getOrNull()?.let { server?.playerList?.getPlayer(it) }
        if (online != null) {
            online.tell(chat.say(tone, text.component()))
            return
        }
        val box = Realm.of(id)?.members?.get(id)?.mail ?: Realm.data.letters.getOrPut(id) { mutableListOf() }
        if (box.size < Config.s.mailLimit) box += "${tone.name}|${text.json()}"
    }

    fun broadcast(c: Country, text: Phrase, tone: Tone = Tone.INFO) = c.members.keys.forEach { direct(it, text, tone) }

    fun officers(c: Country, text: Phrase, tone: Tone = Tone.INFO) = c.members.filterValues { it.rank >= Rank.OFFICER }.keys.forEach { direct(it, text, tone) }

    fun deliver(p: ServerPlayer) {
        val id = p.stringUUID
        val own = Realm.of(id)?.members?.get(id)?.mail
        val lines = own.orEmpty() + Realm.data.letters[id].orEmpty()
        if (lines.isEmpty()) return
        p.tell(chat.info(Phrase.of("kami_claims.mail.away").component()))
        lines.forEach { line ->
            val tone = Tone.of(line.substringBefore('|', "INFO"))
            val body = line.substringAfter('|')
            p.tell(Chat.row { text("• ", tone.mark); Phrase.parse(body)?.let { add(it.component()) } ?: markup(body) })
        }
        own?.clear()
        Realm.data.letters.remove(id)
        Realm.dirty = true
    }
}
