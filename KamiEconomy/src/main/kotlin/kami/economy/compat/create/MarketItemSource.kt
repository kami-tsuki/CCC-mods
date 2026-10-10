package kami.economy.compat.create

import com.simibubi.create.api.behaviour.display.DisplaySource
import com.simibubi.create.content.logistics.depot.DepotBehaviour
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext
import com.simibubi.create.content.redstone.displayLink.source.SingleLineDisplaySource
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats
import com.simibubi.create.content.trains.display.FlapDisplayBlockEntity
import com.simibubi.create.content.trains.display.FlapDisplaySection
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour
import com.simibubi.create.foundation.gui.ModularGuiLineBuilder
import kami.economy.KamiEconomy
import kami.economy.economy.Blacklist
import kami.economy.economy.Classification
import kami.economy.economy.Range
import kami.economy.net.Sync
import kami.libs.create.KamiDisplay
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.ResourceLocation
import net.neoforged.bus.api.IEventBus

object MarketDisplay {
    private val display = KamiDisplay(KamiEconomy.ID)
    private val marketItem = display.source("market_item") { MarketItemSource() }

    fun register(bus: IEventBus) = display.register(bus)

    fun bind() = display.bind(marketItem, ResourceLocation.fromNamespaceAndPath("create", "depot"))
}

class MarketItemSource : SingleLineDisplaySource() {
    override fun provideLine(context: DisplayLinkContext, stats: DisplayTargetStats): MutableComponent = Component.literal(text(context))

    override fun allowsLabeling(context: DisplayLinkContext) = false

    override fun getFlapDisplayLayoutName(context: DisplayLinkContext) = "MarketValue"

    override fun createSectionForValue(context: DisplayLinkContext, size: Int) = FlapDisplaySection(size * 7f, "instant", false, false)

    override fun initConfigurationWidgets(context: DisplayLinkContext, builder: ModularGuiLineBuilder, isFirstLine: Boolean) {
        if (isFirstLine) select(builder, 0, 137, KEY_METRIC, "metric", Metric.entries.map { it.key })
        else {
            select(builder, 0, 68, KEY_BASIS, "basis", BASIS)
            select(builder, 69, 68, KEY_FORMAT, "format", FORMAT)
        }
    }

    private fun select(builder: ModularGuiLineBuilder, x: Int, width: Int, key: String, group: String, options: List<String>) {
        builder.addSelectionScrollInput(x, width, { input, _ ->
            input.forOptions(options.map { Component.translatable("kami_economy.display.$group.$it") }).titled(Component.translatable("kami_economy.display_source.market_item"))
        }, key)
    }

    private fun text(context: DisplayLinkContext): String {
        val ascii = context.targetBlockEntity is FlapDisplayBlockEntity
        val stack = context.sourceBlockEntity?.let { BlockEntityBehaviour.get(it, DepotBehaviour.TYPE) }?.heldItemStack ?: return DisplayFormat.empty(ascii)
        if (stack.isEmpty || Blacklist.classify(stack) != Classification.ALLOWED) return DisplayFormat.empty(ascii)
        val id = Blacklist.itemId(stack)
        val figures = Sync.figures(id) ?: return DisplayFormat.empty(ascii)
        val config = context.sourceConfig()
        val metric = Metric.entries.getOrElse(config.getInt(KEY_METRIC)) { Metric.PRICE }
        val range = when (metric) {
            Metric.WEEK -> Range.WEEK
            Metric.MONTH -> Range.MONTH
            Metric.ALL -> Range.ALL
            else -> null
        }
        return DisplayFormat.render(metric, figures, range?.let { Sync.trend(id, it) }, config.getInt(KEY_BASIS) == 1, config.getInt(KEY_FORMAT) == 0, ascii)
    }

    private companion object {
        const val KEY_METRIC = "Metric"
        const val KEY_BASIS = "Basis"
        const val KEY_FORMAT = "Format"
        val BASIS = listOf("unit", "lot")
        val FORMAT = listOf("compact", "full")
    }
}
