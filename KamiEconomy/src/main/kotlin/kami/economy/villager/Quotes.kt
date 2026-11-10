package kami.economy.villager

import com.mojang.serialization.Codec
import kami.economy.Config
import kami.economy.KamiEconomy
import kami.libs.db.Db
import kami.libs.mc.ItemSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import net.minecraft.world.entity.npc.AbstractVillager
import net.minecraft.world.entity.npc.Villager
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.trading.MerchantOffer
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.attachment.AttachmentType
import net.neoforged.neoforge.registries.DeferredRegister
import net.neoforged.neoforge.registries.NeoForgeRegistries
import java.util.function.Supplier

@Serializable
class Line(val key: String, val side: Int, val price: Long, val unit: Double = 0.0, val spread: Double = 0.0, val demand: Double = 1.0, val extra: Double = 0.0, val spec: String = "") {
    val barter get() = side == BARTER
    val priced get() = barter || price >= 0

    companion object {
        const val BARTER = 2
    }
}

@Serializable
class Quote(var epoch: Int = 0, var lines: List<Line> = emptyList())

object Quotes {
    private val attachments = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, KamiEconomy.ID)
    private val codec: Codec<Quote> = Codec.STRING.xmap({ runCatching { Db.json.decodeFromString<Quote>(it) }.getOrElse { Quote() } }, { Db.json.encodeToString(it) })
    private val type = attachments.register("villager_quote", Supplier { AttachmentType.builder(Supplier { Quote() }).serialize(codec).build() })

    fun register(bus: IEventBus) = attachments.register(bus)

    fun peek(v: AbstractVillager): Quote? = if (v.hasData(type)) v.getData(type) else null

    fun of(v: AbstractVillager, retry: Boolean = false): Quote {
        val q = v.getData(type)
        val offers = v.offers
        if (q.lines.size != offers.size || offers.indices.any { !q.lines[it].valid(offers[it], retry) })
            q.lines = offers.mapIndexed { i, o -> q.lines.getOrNull(i)?.takeIf { it.valid(o, retry) } ?: line(v, i, o, q.epoch) }
        return q
    }

    @JvmStatic
    fun restocked(v: Villager) {
        val q = peek(v) ?: return
        q.epoch++
        q.lines = v.offers.mapIndexed { i, o -> line(v, i, o, q.epoch) }
        VillagerTrade.refresh(v)
    }

    private fun spec(s: ItemStack) = if (s.isEmpty) "" else "${Specs.of(s).key}x${s.count}"

    private fun signature(o: MerchantOffer) = "${spec(o.baseCostA)}|${spec(o.costB)}|${spec(o.result)}"

    private fun Line.valid(o: MerchantOffer, retry: Boolean) = (priced || !retry) && key == signature(o)

    private fun emerald(o: MerchantOffer) = listOf(o.baseCostA, o.costB, o.result).any { !it.isEmpty && Specs.emeralds(ItemSpec.id(it), 1) != null }

    fun order(o: MerchantOffer): Mapped<ItemStack> {
        val b = o.costB.takeUnless { it.isEmpty }?.let { Leg(it, it.count) }
        return Offers.map(Leg(o.baseCostA, o.baseCostA.count), b, Leg(o.result, o.result.count)) { Specs.emeralds(ItemSpec.id(it.stack), it.count) }
    }

    private fun line(v: AbstractVillager, i: Int, o: MerchantOffer, epoch: Int): Line {
        val key = signature(o)
        val m = order(o) as? Mapped.Order ?: return if (emerald(o)) Line(key, Side.SELL.ordinal, -1) else Line(key, Line.BARTER, 0)
        val s = Config.s.villagers
        val spec = Specs.of(m.goods.stack)
        val n = m.goods.count
        val unit = VillagerCourses.unit(spec, m.side, m.emeralds.toDouble() / n) ?: return Line(key, m.side.ordinal, -1, spec = spec.key)
        val extra = m.extra?.let { e -> (VillagerCourses.marketUnit(ItemSpec.id(e.stack)) ?: 0.0) * e.count } ?: 0.0
        val spread = VillagerMath.spread(v.uuid, i, epoch, s.spreadPct)
        val demand = VillagerMath.demand(o.demand, o.priceMultiplier, s.demandCap)
        val price = VillagerMath.price((unit * n - extra).coerceAtLeast(0.0), m.side, spread, demand, s.minPrice)
        return Line(key, m.side.ordinal, price, unit, spread, demand, extra, spec.key)
    }
}
