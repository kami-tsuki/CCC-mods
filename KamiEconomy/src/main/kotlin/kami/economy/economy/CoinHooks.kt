package kami.economy.economy

import com.mojang.brigadier.context.CommandContext
import dev.ithundxr.createnumismatics.Numismatics
import dev.ithundxr.createnumismatics.content.backend.BankAccount
import dev.ithundxr.createnumismatics.content.bank.BankMenu
import dev.ithundxr.createnumismatics.content.coins.CoinItem
import kami.economy.LOG
import kami.economy.mixin.numismatics.CoinBlockAccessor
import kami.economy.world.coinValue
import kami.libs.economy.CoinSource
import kami.libs.economy.CoinWatch
import kami.libs.economy.Coins
import kami.libs.economy.Flow
import kami.libs.economy.MoneyGuard
import kami.libs.economy.Party
import kami.libs.economy.PlayerParty
import kami.libs.economy.ServerBank
import kami.libs.economy.ServerParty
import kami.libs.economy.Statement
import net.minecraft.commands.CommandSourceStack
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

object CoinHooks {
    private val menu = ThreadLocal<BankMenu>()
    private val shop = ThreadLocal<String>()
    private val depth = ThreadLocal.withInitial { IntArray(1) }
    private val failures = AtomicInteger()
    @Volatile
    private var reported = 0L

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            failed(e)
        }
    }

    private fun failed(e: Throwable) {
        val now = System.currentTimeMillis()
        CoinWatch.detectorFailed(now)
        failures.incrementAndGet()
        if (now - reported < 60_000L) return
        reported = now
        LOG.error("Coin tracking failed {} times since the last report", failures.getAndSet(0), e)
    }

    @JvmStatic
    fun enterMenu(container: Any, player: Player): Boolean {
        if (container !is BankMenu || player !is ServerPlayer) return false
        menu.set(container)
        MoneyGuard.enter("terminal")
        return true
    }

    @JvmStatic
    fun exitMenu() {
        menu.remove()
        MoneyGuard.exit()
    }

    @JvmStatic
    fun enterShop(player: Player, pos: BlockPos): Boolean {
        if (player !is ServerPlayer) return false
        shop.set(at(pos))
        MoneyGuard.enter("stock ticker")
        return true
    }

    @JvmStatic
    fun exitShop() {
        shop.remove()
        MoneyGuard.exit()
    }

    @JvmStatic
    fun command(ctx: CommandContext<CommandSourceStack>): Boolean {
        val source = ctx.source
        if (!source.hasPermission(2)) return false
        MoneyGuard.admin(source.player?.let { PlayerParty(it.uuid) } ?: ServerParty, source.textName, "numismatics command", ctx.input)
        return true
    }

    @JvmStatic
    fun <T> explained(reason: String, call: Supplier<T>): T = MoneyGuard.explained(reason) { call.get() }

    @JvmStatic
    fun open() {
        depth.get()[0]++
    }

    @JvmStatic
    fun close(account: BankAccount, before: Long, after: Long, trust: List<UUID>?) {
        val d = depth.get()
        if (--d[0] > 0 || before == after) return
        guard { if (!account.isClientSide) MoneyGuard.changed(account.id, account.type == BankAccount.Type.PLAYER, before, after, trust?.firstOrNull()) }
    }

    @JvmStatic
    fun withdrawn(stack: ItemStack) = guard {
        val account = menu.get()?.contentHolder ?: return@guard
        Coins.minted(CoinSource.TERMINAL, party(account), stack.coinValue())
    }

    @JvmStatic
    fun deposited(spurs: Int) = guard {
        val account = menu.get()?.contentHolder ?: return@guard
        Coins.burned(CoinSource.TERMINAL, party(account), spurs.toLong())
    }

    @JvmStatic
    fun closed(m: BankMenu, player: Player) = guard {
        if (player is ServerPlayer) Coins.close(CoinSource.TERMINAL, party(m.contentHolder))
    }

    @JvmStatic
    fun balance(account: BankAccount, next: Int) = guard {
        val at = shop.get() ?: return@guard
        val spurs = account.balance.toLong() - next
        if (!account.isClientSide && spurs > 0) Coins.minted(CoinSource.STOCK_TICKER, party(account), spurs, at)
    }

    @JvmStatic
    fun paid(be: BlockEntity?, player: Player?, spurs: Int, card: Boolean, kept: Boolean) = guard {
        if (be == null || be.level?.isClientSide != false || spurs <= 0) return@guard
        val amount = spurs.toLong()
        val at = at(be.blockPos)
        val payer = player?.let { PlayerParty(it.uuid) }
        if (!kept) {
            payer?.let { ServerBank.collect(it, amount, Flow.SERVER_SALE, "creative vendor $at") }
            if (card) Coins.moved(CoinSource.CREATIVE_VENDOR, payer, -amount, at) else {
                Coins.burned(CoinSource.CREATIVE_VENDOR, null, amount, at)
                payer?.let { Statement.record(it.uuid, -amount, Flow.SERVER_SALE, "server") }
            }
            return@guard
        }
        val source = source(be)
        val target = deposit(be)
        Coins.moved(source, owner(be), amount, at)
        when {
            card && target == null -> Coins.minted(source, payer, amount, at)
            card -> Coins.moved(source, payer, -amount, at)
            target != null -> Coins.burned(source, null, amount, at)
        }
    }

    @JvmStatic
    fun drawn(be: BlockEntity?, spurs: Int) = guard {
        if (be != null && be.level?.isClientSide == false && spurs > 0) Coins.minted(source(be), null, spurs.toLong(), at(be.blockPos))
    }

    @JvmStatic
    fun payout(block: Any, creative: Boolean, player: Player, spurs: Int) = guard {
        val be = block as BlockEntity
        if (be.level?.isClientSide != false || spurs <= 0) return@guard
        val amount = spurs.toLong()
        val at = at(be.blockPos)
        if (creative) {
            ServerBank.pay(PlayerParty(player.uuid), amount, Flow.SERVER_PURCHASE, "creative vendor $at")
            Coins.minted(CoinSource.CREATIVE_VENDOR, null, amount, at)
            Statement.record(player.uuid, amount, Flow.SERVER_PURCHASE, "server")
        } else {
            Coins.moved(CoinSource.VENDOR, owner(be), -amount, at)
        }
    }

    @JvmStatic
    fun flushed(be: BlockEntity, spurs: Int) = guard {
        if (be.level?.isClientSide == false && spurs > 0) Coins.burned(source(be), deposit(be)?.let(::party), spurs.toLong(), at(be.blockPos))
    }

    @JvmStatic
    fun dropped(level: Level, pos: BlockPos, stacks: List<ItemStack>) = guard {
        if (!level.isClientSide) Coins.minted(CoinSource.BLAZE_BANKER, null, stacks.sumOf { it.coinValue() }, at(pos))
    }

    private fun party(account: BankAccount): Party? = if (account.type == BankAccount.Type.PLAYER) PlayerParty(account.id) else null

    private fun owner(be: BlockEntity): Party? = (be as? CoinBlockAccessor)?.kamiOwner()?.let(::PlayerParty)

    private fun at(pos: BlockPos) = "${pos.x},${pos.y},${pos.z}"

    private fun source(be: BlockEntity) = when (BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.type)?.path) {
        "vendor" -> CoinSource.VENDOR
        "salepoint" -> CoinSource.SALEPOINT
        else -> CoinSource.DEPOSITOR
    }

    private fun deposit(be: BlockEntity): BankAccount? = (be as? CoinBlockAccessor)?.kamiDepositAccount()?.let { Numismatics.BANK.getAccount(it) }
}
