package kami.economy.compat

import kami.libs.compat.Mods
import kami.libs.compat.Soft

object CoinProtection {
    private const val NUMISMATICS = "dev.ithundxr.createnumismatics"

    fun expect() {
        numismatics("content.coins.CoinItem", "extract")
        numismatics("content.backend.BankAccount", "setBalance", "deposit", "deduct")
        numismatics("content.backend.GlobalBankManager", "getOrCreateAccount")
        numismatics("content.bank.BankMenu", "removed")
        numismatics("content.bank.blaze_banker.BankAccountBehaviour", "destroy")
        numismatics("content.coins.SlotInputMergingCoinBag", "set")
        numismatics("content.coins.SlotOutputMergingCoinBag", "remove")
        numismatics("content.depositor.AndesiteDepositorBlock", "useItemOn")
        numismatics("content.depositor.AbstractDepositorBlock", "onRemove")
        numismatics("content.salepoint.SalepointBlock", "onRemove")
        numismatics("content.salepoint.SalepointBlockEntity", "attemptTransaction")
        numismatics("content.vendor.VendorBlock", "onRemove")
        numismatics("content.vendor.VendorBlockEntity", "tryBuyFrom", "lazyTick", "addCoin")
        numismatics("content.backend.behaviours.SliderStylePriceBehaviour", "deductFromSelf", "addCoinsToSelf")
        numismatics("registry.commands.DeductCommand", "execute")
        numismatics("registry.commands.PayCommand", "execute")
        numismatics("registry.commands.PayAllCommand", "execute")
        Soft.expect(Mods.CREATE, "com.simibubi.create.content.logistics.stockTicker.StockTickerInteractionHandler", "interactWithShop")
        Soft.expect("minecraft", "net.minecraft.world.inventory.AbstractContainerMenu", "doClick", "moveItemStackTo", "clicked")
        Soft.expect("minecraft", "net.minecraft.world.inventory.Slot", "safeInsert")
        Soft.expect("minecraft", "net.minecraft.server.commands.GiveCommand", "giveItem")
        Soft.expect("minecraft", "net.minecraft.server.commands.LootCommand", "playerGive")
        Soft.expect("minecraft", "net.minecraft.server.commands.ItemCommands", "setBlockItem", "setEntityItem")
        Soft.expect("minecraft", "net.minecraft.server.network.ServerGamePacketListenerImpl", "handleSetCreativeModeSlot")
    }

    private fun numismatics(path: String, vararg methods: String) = Soft.expect(Mods.NUMISMATICS, "$NUMISMATICS.$path", *methods)
}
