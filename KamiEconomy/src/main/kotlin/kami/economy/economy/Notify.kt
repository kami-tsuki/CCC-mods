package kami.economy.economy

import kami.economy.Market
import kami.libs.chat.Chat
import kami.libs.chat.Tone
import kami.libs.chat.tell
import kami.libs.text.Phrase
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer

object Notify {
    private val chat = Chat.of("market")
    private val topics = mapOf(
        "order_filled" to "orders", "sold" to "orders",
        "bid" to "sales", "auction_bought" to "sales", "auction_sold" to "sales", "auction_expired" to "sales",
        "outbid" to "bids", "bought_out" to "bids", "auction_won" to "bids"
    )
    val TOPICS = topics.values.distinct()

    var server: MinecraftServer? = null

    private fun online(who: String) = Trade.uuid(who)?.let { server?.playerList?.getPlayer(it) }

    fun name(who: String): Phrase = Phrase.value(
        online(who)?.name?.string ?: Trade.uuid(who)?.let { server?.profileCache?.get(it)?.orElse(null)?.name } ?: "?"
    )

    fun stack(item: String, qty: Int): Phrase {
        val name = ResourceLocation.tryParse(item)?.let { BuiltInRegistries.ITEM.get(it).descriptionId } ?: item
        return Phrase.of("kami_economy.notify.stack", qty, Phrase.of(name)).asValue()
    }

    fun label(text: String): Phrase = Phrase.value(text)

    fun muted(who: String): Set<String> = Market.data.muted[who].orEmpty()

    fun mute(who: String, topic: String, on: Boolean) {
        if (topic !in TOPICS) return
        val set = Market.data.muted.getOrPut(who) { mutableSetOf() }
        val changed = if (on) set.add(topic) else set.remove(topic)
        if (set.isEmpty()) Market.data.muted.remove(who)
        if (changed) Market.dirty = true
    }

    fun tell(who: String, tone: Tone, key: String, args: () -> Array<out Any>) {
        if (topics[key] in muted(who)) return
        val p = online(who) ?: return
        p.tell(chat.say(tone, Phrase.of("kami_economy.notify.$key", *args())))
    }
}
