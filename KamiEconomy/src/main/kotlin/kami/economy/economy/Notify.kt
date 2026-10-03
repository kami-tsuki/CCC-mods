package kami.economy.economy

import kami.libs.chat.Chat
import kami.libs.chat.Tone
import kami.libs.chat.tell
import kami.libs.text.Phrase
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.server.ServerLifecycleHooks

/** Short chat notes to the other side of a market trade or auction; only online players are told. */
object Notify {
    private val chat = Chat.of("market")

    private fun server() = ServerLifecycleHooks.getCurrentServer()

    private fun online(who: String) = Trade.uuid(who)?.let { server()?.playerList?.getPlayer(it) }

    fun name(who: String): Phrase = Phrase.value(
        online(who)?.name?.string ?: Trade.uuid(who)?.let { server()?.profileCache?.get(it)?.orElse(null)?.name } ?: "?"
    )

    fun stack(item: String, qty: Int): Phrase {
        val name = ResourceLocation.tryParse(item)?.let { BuiltInRegistries.ITEM.get(it).descriptionId } ?: item
        return Phrase.of("kami_economy.notify.stack", qty, Phrase.of(name)).asValue()
    }

    fun label(text: String): Phrase = Phrase.value(text)

    fun tell(who: String, tone: Tone, key: String, vararg args: Any) {
        if (who.isEmpty()) return
        online(who)?.tell(chat.say(tone, Phrase.of("kami_economy.notify.$key", *args)))
    }
}
