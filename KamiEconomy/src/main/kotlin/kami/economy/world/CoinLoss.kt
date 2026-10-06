package kami.economy.world

import com.simibubi.create.Create
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour
import dev.ithundxr.createnumismatics.content.coins.CoinItem
import kami.economy.mixin.coins.BlazeOwnerAccessor
import kami.economy.mixin.coins.ItemOwnerAccessor
import kami.economy.mixin.numismatics.CoinBlockAccessor
import kami.libs.economy.CoinSource
import kami.libs.economy.Coins
import kami.libs.economy.Flow
import kami.libs.economy.Money
import kami.libs.economy.Numismatics
import kami.libs.economy.Party
import kami.libs.economy.PlayerParty
import kami.libs.economy.ServerBank
import kami.libs.economy.ServerParty
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.neoforged.bus.api.EventPriority
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import java.util.UUID

internal fun claimant(block: UUID?, target: UUID?, thrower: UUID?) = block ?: target ?: thrower

object CoinLoss {
    private val cards = listOf("cards", "authorized_cards").map { TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("numismatics", it)) }
    private val dropOwner = ThreadLocal<UUID?>()
    private val payer = ThreadLocal<UUID?>()

    fun register() {
        FORGE_BUS.addListener<EntityJoinLevelEvent>(EventPriority.HIGHEST) { joined(it) }
        FORGE_BUS.addListener<PlayerEvent.ItemCraftedEvent> { crafted(it) }
    }

    @JvmStatic
    fun dropping(be: BlockEntity?): UUID? = dropOwner.get().also { dropOwner.set(owner(be)) }

    @JvmStatic
    fun dropped(prev: UUID?) = dropOwner.set(prev)

    @JvmStatic
    fun shopEnter(player: Player, level: Level, pos: BlockPos): Boolean {
        if (level.isClientSide || player !is ServerPlayer) return true
        val ticker = level.getBlockEntity(pos)
        if (ticker != null && BlockEntityBehaviour.get(ticker, LogisticallyLinkedBehaviour.TYPE) != null && shopOwner(ticker) == null && paysWithCoins(player)) {
            CoinRules.notify(player, "shop")
            return false
        }
        payer.set(player.uuid)
        return true
    }

    @JvmStatic
    fun shopExit() = payer.remove()

    @JvmStatic
    fun shopPaid(ticker: Any, stack: ItemStack): ItemStack {
        ticker as BlockEntity
        val spurs = value(stack)
        val where = at(ticker.blockPos)
        val owner = shopOwner(ticker)
        val from = payer.get()
        when {
            owner != null && Money.spurs(spurs)?.let { Numismatics.deposit(owner, it) } == true -> Coins.burned(CoinSource.STOCK_TICKER, PlayerParty(owner), spurs, where)
            from != null -> CoinRules.keep(from, stack.copy())
            else -> burn(null, spurs, "stock ticker $where")
        }
        return ItemStack.EMPTY
    }

    internal fun burn(from: Party?, spurs: Long, note: String) {
        if (spurs <= 0) return
        Coins.burned(CoinSource.KAMI, null, spurs)
        ServerBank.collect(from ?: ServerParty, spurs, Flow.RECOVERY, note)
    }

    private fun joined(e: EntityJoinLevelEvent) {
        val entity = e.entity as? ItemEntity ?: return
        if (e.level.isClientSide || !CoinRules.isCoin(entity.item)) return
        e.isCanceled = true
        val stack = entity.item.copy()
        val owner = claimant(dropOwner.get(), entity.target, (entity as ItemOwnerAccessor).kamiThrower())
        entity.discard()
        if (owner != null) CoinRules.keep(owner, stack) else burn(null, value(stack), "lost coins ${at(entity.blockPosition())}")
    }

    private fun crafted(e: PlayerEvent.ItemCraftedEvent) {
        val p = e.entity as? ServerPlayer ?: return
        val grid = e.inventory
        val spurs = (0 until grid.containerSize).sumOf { (grid.getItem(it).item as? CoinItem)?.coin?.value?.toLong() ?: 0L }
        burn(PlayerParty(p.uuid), spurs, "crafted ${BuiltInRegistries.ITEM.getKey(e.crafting.item)}")
    }

    private fun paysWithCoins(p: ServerPlayer) = p.inventory.items.any(CoinRules::isCoin) || cards.any { p.offhandItem.`is`(it) }

    private fun shopOwner(ticker: BlockEntity): UUID? =
        BlockEntityBehaviour.get(ticker, LogisticallyLinkedBehaviour.TYPE)?.freqId?.let { Create.LOGISTICS.logisticsNetworks[it]?.owner }

    private fun owner(be: BlockEntity?): UUID? = when (be) {
        is CoinBlockAccessor -> be.kamiOwner()
        is BlazeOwnerAccessor -> be.kamiBankerOwner()
        else -> null
    }

    private fun value(stack: ItemStack): Long = (stack.item as? CoinItem)?.let { it.coin.value.toLong() * stack.count } ?: 0L

    private fun at(pos: BlockPos) = "${pos.x},${pos.y},${pos.z}"
}
