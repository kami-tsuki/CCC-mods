package kami.economy.world

import net.minecraft.world.level.block.entity.BaseContainerBlockEntity
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.SignBlock
import net.minecraft.world.level.block.BellBlock
import com.mojang.brigadier.tree.CommandNode
import dev.ithundxr.createnumismatics.content.coins.CoinItem
import kami.economy.LOG
import kami.economy.mixin.coins.GridGateAccessor
import kami.libs.chat.Chat
import kami.libs.chat.Tone
import kami.libs.chat.bar
import kami.libs.db.Db
import kami.libs.db.Rows
import kami.libs.db.SavePoint
import kami.libs.economy.CoinReturn
import kami.libs.economy.Numismatics
import kami.libs.text.Phrase
import net.minecraft.commands.CommandSourceStack
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.neoforged.bus.api.EventPriority
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.neoforged.neoforge.event.CommandEvent
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.item.ItemTossEvent
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS
import java.util.UUID
import java.util.function.Predicate

private const val TRADE_MENU = "kami.essentials.trade.TradeMenu"
private val COIN_SLOTS = setOf("dev.ithundxr.createnumismatics.content.coins.SlotInputMergingCoinBag", "dev.ithundxr.createnumismatics.content.coins.SlotDiscreteCoinBag")

internal fun acceptsCoins(playerInventory: Boolean, slot: String, menu: String?) = playerInventory || slot in COIN_SLOTS || menu == TRADE_MENU

internal fun coinUseAllowed(block: String, hasBlockEntity: Boolean, harmless: Boolean = false) =
    !hasBlockEntity || harmless || block.startsWith("numismatics:") || block == "create:stock_ticker"

object CoinRules {
    private val ROOTS = setOf("numismatics", "nm")
    private const val DELIVER_TICKS = 100
    private const val NOTICE_MS = 1500L
    private val denied = ThreadLocal.withInitial { false }
    private val notices = HashMap<Pair<UUID, String>, Long>()
    private val requirement by lazy { runCatching { CommandNode::class.java.getDeclaredField("requirement").apply { isAccessible = true } }.getOrNull() }

    fun register() {
        CoinLoss.register()
        CoinReturn.register { owner, stack -> isCoin(stack).also { if (it) keep(owner, stack.copy()) } }
        FORGE_BUS.addListener<ServerStartedEvent> { KeptCoins.load() }
        FORGE_BUS.addListener<ServerStoppedEvent>(EventPriority.LOWEST) { KeptCoins.unload() }
        FORGE_BUS.addListener<RegisterCommandsEvent>(EventPriority.LOWEST) { restrict(it) }
        FORGE_BUS.addListener<CommandEvent> { command(it) }
        FORGE_BUS.addListener<ItemTossEvent> { toss(it) }
        FORGE_BUS.addListener<LivingDropsEvent>(EventPriority.HIGHEST) { death(it) }
        FORGE_BUS.addListener<PlayerInteractEvent.EntityInteract> { if (holder(it, it.target)) { it.isCanceled = true; it.cancellationResult = InteractionResult.FAIL } }
        FORGE_BUS.addListener<PlayerInteractEvent.EntityInteractSpecific> { if (holder(it, it.target)) { it.isCanceled = true; it.cancellationResult = InteractionResult.FAIL } }
        FORGE_BUS.addListener<PlayerInteractEvent.RightClickBlock> { e ->
            val state = e.level.getBlockState(e.pos)
            val harmless = e.level.getBlockEntity(e.pos) is BaseContainerBlockEntity || state.block is BedBlock || state.block is SignBlock || state.block is BellBlock
            if ((isCoin(e.entity.mainHandItem) || isCoin(e.entity.offhandItem)) && !coinUseAllowed(BuiltInRegistries.BLOCK.getKey(state.block).toString(), state.hasBlockEntity(), harmless)) {
                e.isCanceled = true
                e.cancellationResult = InteractionResult.FAIL
                (e.entity as? ServerPlayer)?.let { notify(it, "store") }
            }
        }
        FORGE_BUS.addListener<PlayerEvent.PlayerRespawnEvent> { (it.entity as? ServerPlayer)?.let(::deliver) }
        FORGE_BUS.addListener<PlayerEvent.PlayerLoggedInEvent> { (it.entity as? ServerPlayer)?.let(::deliver) }
        FORGE_BUS.addListener<PlayerEvent.PlayerLoggedOutEvent> { e -> notices.keys.removeIf { it.first == e.entity.uuid } }
        FORGE_BUS.addListener<ServerTickEvent.Post> { e ->
            if (e.server.tickCount % DELIVER_TICKS == 0 && KeptCoins.any()) e.server.playerList.players.forEach { if (!it.isCreative) deliver(it) }
        }
    }

    fun hideFromTabs() {
        MOD_BUS.addListener<BuildCreativeModeTabContentsEvent>(EventPriority.LOWEST) { e ->
            (e.parentEntries + e.searchEntries).filter(::isCoin).forEach { e.remove(it, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS) }
        }
    }

    @JvmStatic
    fun isCoin(stack: ItemStack?) = stack != null && stack.item is CoinItem

    @JvmStatic
    fun mayPlace(slot: Slot?, stack: ItemStack?): Boolean {
        if (slot == null || !isCoin(stack) || accepts(slot)) return true
        denied.set(true)
        return false
    }

    @JvmStatic
    fun clickStart() = denied.set(false)

    @JvmStatic
    fun clickEnd(player: Player?) {
        if (denied.get() && player is ServerPlayer) notify(player, "store")
        denied.set(false)
    }

    @JvmStatic
    fun refuse(source: CommandSourceStack?, item: Item?): Boolean {
        if (item !is CoinItem) return false
        source?.sendFailure(Phrase.of("kami_economy.coins.give").component())
        return true
    }

    @JvmStatic
    fun withoutCoins(stacks: List<ItemStack>?): List<ItemStack>? = if (stacks == null || stacks.none(::isCoin)) stacks else stacks.filterNot(::isCoin)

    @JvmStatic
    fun creative(p: ServerPlayer?, slot: Int, stack: ItemStack?, dropBlocked: Boolean) {
        if (p == null || stack == null || !p.gameMode.isCreative || stack.count > stack.maxStackSize || !stack.isItemEnabled(p.level().enabledFeatures())) return
        val target = when {
            slot in 1..45 -> p.inventoryMenu.getSlot(slot)
            slot < 0 -> null
            else -> return
        }
        target?.item?.takeIf(::isCoin)?.let { KeptCoins.add(p.uuid, id(it), it.count) }
        if (!isCoin(stack)) return
        val allowed = if (target == null) !dropBlocked else accepts(target)
        val n = if (allowed) KeptCoins.take(p.uuid, id(stack), stack.count) else 0
        if (n < stack.count) notify(p, "creative")
        stack.count = n
    }

    private fun accepts(slot: Slot) = acceptsCoins(slot.container is Inventory, slot.javaClass.name, (slot as? GridGateAccessor)?.kamiMenu()?.javaClass?.name)

    private fun id(stack: ItemStack) = BuiltInRegistries.ITEM.getKey(stack.item).toString()

    private fun restrict(e: RegisterCommandsEvent) = ROOTS.forEach { root ->
        val node = e.dispatcher.root.getChild(root) ?: return@forEach
        val old = node.requirement
        runCatching { checkNotNull(requirement).set(node, Predicate<CommandSourceStack> { it.hasPermission(2) && old.test(it) }) }
            .onFailure { LOG.warn("Could not hide /{} from non-operators", root, it) }
    }

    private fun command(e: CommandEvent) {
        val source = e.parseResults.context.source
        if (e.parseResults.reader.string.removePrefix("/").substringBefore(' ') !in ROOTS || source.hasPermission(2)) return
        e.isCanceled = true
        source.sendFailure(Phrase.of("kami_economy.coins.command").component())
    }

    private fun toss(e: ItemTossEvent) {
        val p = e.player as? ServerPlayer ?: return
        val stack = e.entity.item
        if (!isCoin(stack)) return
        e.isCanceled = true
        notify(p, "drop")
        keep(p, stack.copy())
    }

    private fun death(e: LivingDropsEvent) {
        val p = e.entity as? ServerPlayer ?: return
        e.drops.removeIf { d -> isCoin(d.item).also { if (it) KeptCoins.add(p.uuid, id(d.item), d.item.count) } }
    }

    private fun holder(e: PlayerInteractEvent, target: Entity): Boolean {
        if (!isCoin(e.itemStack) || target is Player) return false
        (e.entity as? ServerPlayer)?.let { notify(it, "store") }
        return true
    }

    private fun stow(p: ServerPlayer, stack: ItemStack): ItemStack {
        if (!p.isAlive || p.hasDisconnected()) return stack
        val inv = p.inventory
        while (!stack.isEmpty) {
            val at = inv.getSlotWithRemainingSpace(stack).takeIf { it >= 0 } ?: inv.freeSlot.takeIf { it >= 0 } ?: break
            val cur = inv.getItem(at)
            if (cur.isEmpty) inv.setItem(at, stack.split(inv.getMaxStackSize(stack)))
            else (inv.getMaxStackSize(cur) - cur.count).coerceAtMost(stack.count).let { cur.grow(it); stack.shrink(it) }
        }
        return stack
    }

    internal fun keep(owner: UUID, stack: ItemStack) {
        if (stack.isEmpty) return
        Numismatics.online(owner)?.let { return keep(it, stack) }
        KeptCoins.add(owner, id(stack), stack.count)
    }

    private fun keep(p: ServerPlayer, stack: ItemStack) {
        val rest = stow(p, stack)
        if (rest.isEmpty) return
        KeptCoins.add(p.uuid, id(rest), rest.count)
        notify(p, "owed")
    }

    private fun deliver(p: ServerPlayer) {
        if (!p.isAlive || p.hasDisconnected()) return
        var moved = false
        KeptCoins.of(p.uuid).forEach { (key, n) ->
            val item = ResourceLocation.tryParse(key)?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null) } ?: return@forEach
            var left = KeptCoins.take(p.uuid, key, n)
            val total = left
            while (left > 0) {
                val size = minOf(left, item.defaultMaxStackSize.coerceAtLeast(1))
                val rest = stow(p, ItemStack(item, size))
                left -= size - rest.count
                if (!rest.isEmpty) break
            }
            KeptCoins.add(p.uuid, key, left)
            if (left < total) moved = true
        }
        if (moved) notify(p, "returned")
    }

    internal fun notify(p: ServerPlayer, key: String) {
        val now = System.currentTimeMillis()
        val k = p.uuid to key
        if (now - (notices[k] ?: 0L) < NOTICE_MS) return
        notices[k] = now
        p.bar(Chat.bar(Tone.BAD, Phrase.of("kami_economy.coins.$key")))
    }
}

object KeptCoins {
    private val owed = LinkedHashMap<Pair<String, String>, Int>()
    private val rows = Rows<Pair<String, String>>("eco_kept_coins", listOf("uuid", "item"), listOf("count"))
    private var attached = false

    fun load() {
        owed.clear()
        Db.read { query("SELECT * FROM eco_kept_coins ORDER BY rowid") { (it.string("uuid") to it.string("item")) to it.int("count") } }
            .forEach { (k, v) -> owed[k] = v }
        rows.prime(rowMap())
        attached = true
        SavePoint.register("economy.kept_coins") { if (attached) rows.diff(rowMap()) else null }
    }

    private fun rowMap() = owed.mapValues { (k, n) -> listOf<Any?>(k.first, k.second, n) }

    fun unload() {
        attached = false
    }

    fun any() = owed.isNotEmpty()

    fun of(player: UUID): Map<String, Int> = player.toString().let { id -> owed.filterKeys { it.first == id }.mapKeys { it.key.second } }

    fun add(player: UUID, item: String, n: Int) {
        if (n <= 0) return
        val key = player.toString() to item
        owed.merge(key, n, Int::plus)
    }

    fun take(player: UUID, item: String, n: Int): Int {
        val key = player.toString() to item
        val have = owed[key] ?: return 0
        val taken = n.coerceIn(0, have)
        if (taken == 0) return 0
        if (have > taken) owed[key] = have - taken else owed.remove(key)
        return taken
    }
}
