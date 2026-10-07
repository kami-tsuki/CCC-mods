package kami.essentials.trade

import kami.essentials.KamiEssentials
import kami.essentials.chat.Talk
import kami.libs.chat.tell
import kami.libs.db.Db
import kami.libs.db.Rows
import kami.libs.db.SavePoint
import kami.libs.db.Session
import kami.libs.economy.CoinReturn
import kami.libs.text.Phrase
import net.minecraft.nbt.TagParser
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal fun away(disconnected: Boolean, alive: Boolean) = disconnected || !alive

object Deliveries {
    private class Parcel(val id: Long, val owner: UUID, val item: String)

    private val parcels = ConcurrentHashMap<Long, Parcel>()
    private val next = AtomicLong()
    private val rows = Rows<Long>("ess_delivery", listOf("id"), listOf("uuid", "item"))
    @Volatile private var attached = false

    fun load() = Db.read { load(this) }

    internal fun load(s: Session) {
        parcels.clear()
        s.query("SELECT id, uuid, item FROM ess_delivery") { Parcel(it.long("id"), UUID.fromString(it.string("uuid")), it.string("item")) }.forEach { parcels[it.id] = it }
        next.set(parcels.keys.maxOrNull() ?: 0)
        rows.prime(rowMap())
        attached = true
        SavePoint.register("ess.delivery") { if (attached) rows.diff(rowMap()) else null }
    }

    private fun rowMap() = parcels.values.associate { it.id to listOf<Any?>(it.id, it.owner.toString(), it.item) }

    fun unload() {
        attached = false
        parcels.clear()
    }

    fun send(p: ServerPlayer, items: List<ItemStack>) =
        put(p.uuid, items.filterNot { it.isEmpty || CoinReturn.keep(p.uuid, it) }.map { it.save(p.registryAccess()).toString() })

    fun deliver(p: ServerPlayer) {
        if (away(p.hasDisconnected(), p.isAlive)) return
        val raw = take(p.uuid).ifEmpty { return }
        val (good, bad) = raw.map { it to runCatching { ItemStack.parse(p.registryAccess(), TagParser.parseTag(it)).orElse(null) }.getOrNull() }.partition { it.second != null }
        bad.forEach { KamiEssentials.LOG.warn("Could not restore a delivered item for {}: {}", p.uuid, it.first) }
        put(p.uuid, bad.map { it.first })
        if (good.isEmpty()) return
        good.mapNotNull { it.second }.forEach { p.inventory.placeItemBackInInventory(it) }
        p.tell(Talk.chat.ok(Phrase.of("kami_essentials.trade.delivered")))
    }

    internal fun put(owner: UUID, raw: List<String>) {
        if (raw.isEmpty()) return
        raw.forEach { Parcel(next.incrementAndGet(), owner, it).let { p -> parcels[p.id] = p } }
    }

    internal fun take(owner: UUID): List<String> {
        val mine = parcels.values.filter { it.owner == owner }.sortedBy { it.id }
        if (mine.isEmpty()) return emptyList()
        mine.forEach { parcels.remove(it.id) }
        return mine.map { it.item }
    }
}
