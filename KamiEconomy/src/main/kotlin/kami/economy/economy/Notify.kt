package kami.economy.economy

import kami.libs.util.toUuidOrNull
import kami.economy.Delivery
import kami.economy.Market
import kami.libs.admin.Players
import kami.libs.chat.Chat
import kami.libs.chat.Tone
import kami.libs.chat.tell
import kami.libs.text.Phrase
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

object Notify {
    private const val NOTE = "note:"
    private val chat = Chat.of("market")
    private val topics = mapOf(
        "order_filled" to "orders", "sold" to "orders", "peg_funds" to "orders",
        "bid" to "sales", "auction_bought" to "sales", "auction_sold" to "sales", "auction_expired" to "sales",
        "outbid" to "bids", "bought_out" to "bids", "auction_won" to "bids"
    )
    val TOPICS = topics.values.distinct()

    var server: MinecraftServer? = null

    private fun online(who: String) = who.toUuidOrNull()?.let { server?.playerList?.getPlayer(it) }

    fun playerName(who: String): String? = server?.let { s -> who.toUuidOrNull()?.let { Players.name(s, it) } }

    fun name(who: String): Phrase = Phrase.value(playerName(who) ?: "?")

    private fun itemKey(item: String) = ResourceLocation.tryParse(item)?.let { BuiltInRegistries.ITEM.get(it).descriptionId } ?: item

    fun stack(item: String, qty: Int): Phrase = Phrase.of("kami_libs.format.times", qty, Phrase.of(itemKey(item))).asValue()

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

    fun cancelled(who: String, item: String, ref: Long, reason: String) {
        if (online(who) != null) tell(who, Tone.WARN, "cancelled") { arrayOf(Phrase.of(itemKey(item)).asValue(), Phrase.value(reason)) }
        else Market.queueDelivery(who, item, 0, "$NOTE$ref:$reason")
    }

    fun note(p: ServerPlayer, d: Delivery): Boolean {
        if (d.qty != 0 || !d.id.startsWith(NOTE)) return false
        tell(p.stringUUID, Tone.WARN, "cancelled") { arrayOf(Phrase.of(itemKey(d.item)).asValue(), Phrase.value(d.id.removePrefix(NOTE).substringAfter(':'))) }
        return true
    }
}
