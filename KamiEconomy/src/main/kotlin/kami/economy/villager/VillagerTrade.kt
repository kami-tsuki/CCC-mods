package kami.economy.villager

import kami.economy.Config
import kami.economy.KamiEconomy
import kami.economy.LOG
import kami.economy.economy.Gate
import kami.economy.economy.TradeStatus
import kami.economy.economy.Variants
import kami.libs.chat.Chat
import kami.libs.chat.Tone
import kami.libs.economy.Flow
import kami.libs.economy.Numismatics
import kami.libs.economy.PlayerParty
import kami.libs.economy.ServerBank
import kami.libs.economy.ServerParty
import kami.libs.economy.Statement
import kami.libs.mc.ItemSpec
import kami.libs.text.Phrase
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.Stats
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.npc.AbstractVillager
import net.minecraft.world.entity.npc.Villager
import net.minecraft.world.entity.player.Player
import net.minecraft.world.flag.FeatureFlags
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.MerchantMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.trading.MerchantOffer
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.registries.DeferredRegister
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import java.util.UUID
import java.util.function.Supplier

object VillagerTrade {
    private val menus = DeferredRegister.create(Registries.MENU, KamiEconomy.ID)
    val MENU = menus.register("villager", Supplier { MenuType({ id, inv -> VillagerMenu(id, inv) }, FeatureFlags.VANILLA_SET) })
    private var warned = false

    fun register(bus: IEventBus) {
        menus.register(bus)
        Quotes.register(bus)
        FORGE_BUS.addListener<PlayerContainerEvent.Open> { guard(it) }
    }

    @JvmStatic
    fun open(v: AbstractVillager, player: Player, title: Component): Boolean {
        val p = player as? ServerPlayer ?: return false
        if (!Config.s.villagers.enabled) return false
        Quotes.of(v, retry = true)
        if (p.openMenu(SimpleMenuProvider({ id, inv, _ -> VillagerMenu(id, inv, v) }, title)).isEmpty) {
            v.tradingPlayer = null
            return true
        }
        val m = p.containerMenu as? VillagerMenu ?: return true
        pick(m, p, cards(p).firstOrNull(), quiet = true)
        m.showResult()
        sync(m, p)
        return true
    }

    fun refresh(v: AbstractVillager) {
        val p = v.tradingPlayer as? ServerPlayer ?: return
        val m = (p.containerMenu as? VillagerMenu)?.takeIf { it.trader === v } ?: return
        m.showResult()
        sync(m, p)
    }

    fun sync(m: VillagerMenu, p: ServerPlayer) {
        val v = m.trader ?: return
        val pct = Config.s.taxPct
        val lines = Quotes.of(v).lines.map { l ->
            val net = if (l.side == Side.BUY.ordinal && l.price > 0) l.price - VillagerMath.tax(l.price, pct) else l.price
            LineView(l.side, l.price, net, l.unit, l.spread, l.demand)
        }
        val card = cardSlot(m, p)?.let { i -> p.inventory.getItem(i).let { CardView(i, Cards.label(it).take(VillagerSync.LABEL), Cards.balance(p, it)) } }
        val level = (v as? Villager)?.villagerData?.level ?: 0
        PacketDistributor.sendToPlayer(p, VillagerSync(m.containerId, v.offers, level, v.villagerXp, v.showProgressBar(), v.canRestock(), lines, card, pct))
    }

    private fun cards(p: ServerPlayer) = p.inventory.items.indices.filter { Cards.usable(p, p.inventory.getItem(it)) }

    fun pick(m: VillagerMenu, p: ServerPlayer, slot: Int?, quiet: Boolean = false) {
        val s = slot?.let(p.inventory::getItem) ?: ItemStack.EMPTY
        if (slot != null && !Cards.usable(p, s)) {
            if (!quiet) deny(p, "card_unusable")
        } else {
            m.cardSlot = slot
            m.cardAccount = Cards.account(s)
        }
        if (!quiet) sync(m, p)
    }

    fun cycle(m: VillagerMenu, p: ServerPlayer) {
        val usable = cards(p)
        if (usable.isEmpty()) deny(p, "no_card") else pick(m, p, m.cardSlot?.let { c -> usable.firstOrNull { it > c } } ?: usable.first())
    }

    private fun cardSlot(m: VillagerMenu, p: ServerPlayer): Int? {
        val account = m.cardAccount ?: return null
        val inv = p.inventory
        fun ok(i: Int) = i in inv.items.indices && Cards.account(inv.getItem(i)) == account && Cards.usable(p, inv.getItem(i))
        return (m.cardSlot?.takeIf(::ok) ?: inv.items.indices.firstOrNull(::ok))?.also { m.cardSlot = it }
    }

    fun trade(m: VillagerMenu, p: ServerPlayer, times: Int) {
        val v = m.trader ?: return
        var done = 0
        while (done < times && once(m, p, v, done == 0)) done++
        m.showResult()
        m.broadcastChanges()
        sync(m, p)
    }

    private fun once(m: VillagerMenu, p: ServerPlayer, v: AbstractVillager, loud: Boolean): Boolean {
        val o = v.offers.getOrNull(m.selected) ?: return false
        val line = Quotes.of(v).lines.getOrNull(m.selected) ?: return false
        if (!o.isOutOfStock && line.priced && !line.barter) lock(p, o)?.let {
            if (loud) p.displayClientMessage(Chat.bar(Tone.BAD, it), true)
            return false
        }
        val why = when {
            o.isOutOfStock -> "out_of_stock"
            !line.priced -> "no_price"
            line.barter -> barter(m, p, o)
            else -> money(m, p, o, line)
        }
        if (why != null) {
            if (loud) deny(p, why)
            return false
        }
        v.notifyTrade(o)
        p.awardStat(Stats.TRADED_WITH_VILLAGER)
        return true
    }

    private fun lock(p: ServerPlayer, o: MerchantOffer): Phrase? {
        val order = Quotes.order(o) as? Mapped.Order ?: return null
        val action = if (order.side == Side.SELL) "buy" else "sell_market"
        Gate.denial(p.uuid, action)?.let { return it }
        val id = ItemSpec.id(order.goods.stack)
        return TradeStatus.denial(action, id) ?: Variants.core(id).takeIf { it != id }?.let { TradeStatus.denial(action, it) }
    }

    private fun barter(m: VillagerMenu, p: ServerPlayer, o: MerchantOffer): String? {
        val out = o.assemble()
        if (!fits(p, out)) return "full"
        val a = m.payments.getItem(0)
        val b = m.payments.getItem(1)
        if (!o.take(a, b) && !o.take(b, a)) return "missing"
        m.payments.setChanged()
        p.inventory.add(out)
        return null
    }

    private fun money(m: VillagerMenu, p: ServerPlayer, o: MerchantOffer, line: Line): String? {
        val order = Quotes.order(o) as? Mapped.Order ?: return "missing"
        val take = m.need(o)?.let { c -> (m.held(c) ?: return "missing") to c.count }
        val card = cardSlot(m, p)?.let(p.inventory::getItem) ?: return "no_card"
        val account = Cards.account(card) ?: return "no_card"
        if (line.price > Int.MAX_VALUE) return "funds"
        val tax = VillagerMath.tax(line.price, Config.s.taxPct)
        if (order.side == Side.SELL) {
            val out = o.assemble()
            if (!fits(p, out)) return "full"
            if (!Cards.charge(p, card, line.price)) return "funds"
            p.inventory.add(out)
        } else if (!Numismatics.deposit(account, (line.price - tax).toInt())) return "deposit"
        take?.let { (slot, n) -> m.payments.removeItem(slot, n) }
        book(account, order, line, tax)
        return null
    }

    private fun book(account: UUID, order: Mapped.Order<ItemStack>, line: Line, tax: Long) {
        val n = order.goods.count
        val id = ItemSpec.id(order.goods.stack)
        val party = PlayerParty(account)
        val note = Statement.note("kami_libs.note.trade", n, id)
        if (order.side == Side.SELL) {
            ServerBank.collect(party, line.price - tax, Flow.VILLAGER, note)
            Statement.record(account, -line.price, Flow.VILLAGER, ServerParty.key, id, n)
        } else {
            ServerBank.pay(party, line.price, Flow.VILLAGER, note)
            Statement.record(account, line.price - tax, Flow.VILLAGER, ServerParty.key, id, n)
        }
        ServerBank.collect(party, tax, Flow.TAX, note)
        VillagerCourses.fill(Specs.of(order.goods.stack), (line.price + line.extra) / n, n, order.side)
    }

    private fun fits(p: ServerPlayer, out: ItemStack): Boolean {
        val room = p.inventory.items.sumOf { s ->
            when {
                s.isEmpty -> out.maxStackSize
                ItemStack.isSameItemSameComponents(s, out) -> s.maxStackSize - s.count
                else -> 0
            }.toLong()
        }
        return room >= out.count
    }

    private fun deny(p: ServerPlayer, why: String) = p.displayClientMessage(Chat.bar(Tone.BAD, Phrase.of("kami_economy.villager.deny.$why")), true)

    private fun guard(e: PlayerContainerEvent.Open) {
        val m = e.container as? MerchantMenu ?: return
        val p = e.entity as? ServerPlayer ?: return
        if (!Config.s.villagers.enabled || m.javaClass != MerchantMenu::class.java || m.offers.none { Quotes.order(it) is Mapped.Order }) return
        p.closeContainer()
        deny(p, "unsupported")
        if (!warned) {
            warned = true
            LOG.warn("Closed a trading screen that bypasses the villager money trade; its trader is not supported")
        }
    }
}
