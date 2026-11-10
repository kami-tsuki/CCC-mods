package kami.economy.villager

import kami.libs.mc.ItemSpec
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.npc.AbstractVillager
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.trading.ItemCost
import net.minecraft.world.item.trading.MerchantOffer
import net.minecraft.world.item.trading.MerchantOffers
import java.util.UUID

class VillagerMenu(id: Int, inv: Inventory, val trader: AbstractVillager?) : AbstractContainerMenu(VillagerTrade.MENU.get(), id) {
    constructor(id: Int, inv: Inventory) : this(id, inv, null)

    val payments = SimpleContainer(2)
    private val output = SimpleContainer(1)
    var offers = MerchantOffers()
    var lines = emptyList<LineView>()
    var card: CardView? = null
    var level = 0
    var xp = 0
    var progress = false
    var restock = false
    var tax = 0
    var selected = 0
    var cardSlot: Int? = null
    var cardAccount: UUID? = null

    init {
        addSlot(Slot(payments, 0, 136, 37))
        addSlot(Slot(payments, 1, 162, 37))
        addSlot(object : Slot(output, 0, 220, 37) {
            override fun mayPlace(stack: ItemStack) = false
            override fun mayPickup(player: Player) = false
        })
        for (r in 0..2) for (c in 0..8) addSlot(Slot(inv, c + r * 9 + 9, 108 + c * 18, 84 + r * 18))
        for (c in 0..8) addSlot(Slot(inv, c, 108 + c * 18, 142))
        payments.addListener { slotsChanged(it) }
    }

    override fun stillValid(player: Player) = trader?.let { it.tradingPlayer == player && it.isAlive } ?: true

    override fun slotsChanged(container: Container) {
        if (trader != null) showResult()
        super.slotsChanged(container)
    }

    fun showResult() {
        val v = trader ?: return
        val o = v.offers.getOrNull(selected)
        val line = Quotes.of(v).lines.getOrNull(selected)
        val show = o != null && line != null && !o.isOutOfStock && when {
            line.barter -> o.satisfiedBy(payments.getItem(0), payments.getItem(1)) || o.satisfiedBy(payments.getItem(1), payments.getItem(0))
            line.side == Side.SELL.ordinal -> need(o)?.let { held(it) != null } ?: true
            else -> false
        }
        output.setItem(0, if (show) o!!.result.copy() else ItemStack.EMPTY)
    }

    fun need(o: MerchantOffer): ItemCost? {
        val m = Quotes.order(o) as? Mapped.Order ?: return null
        return when {
            m.side == Side.BUY -> o.itemCostA
            m.extra == null -> null
            Specs.emeralds(ItemSpec.id(o.baseCostA), 1) == null -> o.itemCostA
            else -> o.itemCostB.orElse(null)
        }
    }

    fun held(cost: ItemCost) = (0..1).firstOrNull { payments.getItem(it).let { s -> cost.test(s) && s.count >= cost.count } }

    override fun clicked(slotId: Int, button: Int, type: ClickType, player: Player) {
        if (slotId == RESULT) {
            if (player is ServerPlayer && (type == ClickType.PICKUP || type == ClickType.QUICK_MOVE)) VillagerTrade.trade(this, player, if (type == ClickType.QUICK_MOVE) REPEAT else 1)
            return
        }
        val slot = slots.getOrNull(slotId)
        if (slot != null && slotId > RESULT && type == ClickType.PICKUP && carried.isEmpty && Cards.isCard(slot.item)) {
            if (player is ServerPlayer) VillagerTrade.pick(this, player, slot.containerSlot)
            return
        }
        super.clicked(slotId, button, type, player)
    }

    override fun clickMenuButton(player: Player, id: Int): Boolean {
        val p = player as? ServerPlayer ?: return false
        when (id) {
            CYCLE -> VillagerTrade.cycle(this, p)
            CLEAR -> VillagerTrade.pick(this, p, null)
            in trader?.offers?.indices ?: IntRange.EMPTY -> select(id)
            else -> return false
        }
        return true
    }

    private fun select(index: Int) {
        val o = trader?.offers?.getOrNull(index) ?: return
        selected = index
        for (i in 0..1) {
            val s = payments.getItem(i)
            if (!s.isEmpty && !moveItemStackTo(s, FIRST, slots.size, true)) return showResult()
            payments.setItem(i, s)
        }
        val line = Quotes.of(trader).lines.getOrNull(index)
        if (line?.barter == true) {
            fill(0, o.itemCostA)
            o.itemCostB.ifPresent { fill(1, it) }
        } else need(o)?.let { fill(0, it) }
        showResult()
        trader.notifyTradeUpdated(output.getItem(0))
    }

    private fun fill(slot: Int, cost: ItemCost) {
        for (i in FIRST until slots.size) {
            val s = slots[i].item
            if (s.isEmpty || !cost.test(s)) continue
            val have = payments.getItem(slot)
            if (!have.isEmpty && !ItemStack.isSameItemSameComponents(s, have)) continue
            val k = minOf(s.maxStackSize - have.count, s.count)
            payments.setItem(slot, s.copyWithCount(have.count + k))
            s.shrink(k)
            if (have.count + k >= s.maxStackSize) break
        }
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val slot = slots[index]
        if (!slot.hasItem() || index == RESULT) return ItemStack.EMPTY
        val stack = slot.item
        val copy = stack.copy()
        val moved = when (index) {
            0, 1 -> moveItemStackTo(stack, FIRST, slots.size, false)
            in FIRST until HOTBAR -> moveItemStackTo(stack, HOTBAR, slots.size, false)
            else -> moveItemStackTo(stack, FIRST, HOTBAR, false)
        }
        if (!moved) return ItemStack.EMPTY
        if (stack.isEmpty) slot.setByPlayer(ItemStack.EMPTY) else slot.setChanged()
        return copy
    }

    override fun canTakeItemForPickAll(stack: ItemStack, slot: Slot) = slot.index != RESULT

    override fun removed(player: Player) {
        super.removed(player)
        val v = trader ?: return
        if (v.tradingPlayer == player) v.tradingPlayer = null
        clearContainer(player, payments)
    }

    fun apply(s: VillagerSync) {
        offers = s.offers
        lines = s.lines
        card = s.card
        level = s.level
        xp = s.xp
        progress = s.progress
        restock = s.restock
        tax = s.tax
    }

    companion object {
        const val RESULT = 2
        const val FIRST = 3
        const val HOTBAR = 30
        const val CYCLE = 1000
        const val CLEAR = 1001
        const val REPEAT = 64
    }
}
