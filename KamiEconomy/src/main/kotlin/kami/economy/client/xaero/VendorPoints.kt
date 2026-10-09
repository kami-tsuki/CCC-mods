package kami.economy.client.xaero

import kami.economy.client.MarketPrefs
import kami.economy.client.ui.MarketApp
import kami.economy.net.VendorLine
import kami.libs.xaero.MapAction
import kami.libs.xaero.MapPoint
import kami.libs.xaero.MapToggle
import kami.libs.xaero.PointSource
import kami.libs.xaero.XaeroMaps
import net.minecraft.network.chat.Component

private const val MAX_VENDORS = 50
private const val BUY_COLOR = 0x55FF55
private const val SELL_COLOR = 0xFFAA00

object VendorPoints : PointSource {
    override val id = "vendors"
    override val toggle = "vendors"

    @Volatile
    private var lines = emptyList<VendorLine>()
    private var rev = 0

    override fun rev() = rev

    fun update(next: List<VendorLine>) {
        lines = next.take(MAX_VENDORS)
        rev++
        XaeroMaps.refresh()
    }

    fun clear() {
        lines = emptyList()
        rev++
    }

    override fun points(dim: String): List<MapPoint> = build(lines, dim, ::open)

    fun build(lines: List<VendorLine>, dim: String, open: (String) -> Unit): List<MapPoint> = lines.filter { it.dim == dim }.map { v ->
        lateinit var self: MapPoint
        val label = v.item.substringAfter(':').replace('_', ' ')
        self = MapPoint(
            "vendor:${v.dim}:${v.x}:${v.y}:${v.z}:${v.item}", v.dim, v.x, v.y, v.z, label, label.take(2).uppercase(), if (v.sell) SELL_COLOR else BUY_COLOR,
            listOf(Component.literal(label), Component.translatable("kami_economy.xaero.vendor.price", v.price, v.country)),
            listOf(
                MapAction(Component.translatable("kami_economy.xaero.vendor.open")) { open(v.item) },
                MapAction(Component.translatable("kami_economy.xaero.vendor.waypoint")) { XaeroMaps.tempWaypoint(self) }
            )
        )
        self
    }

    private fun open(item: String) {
        MarketApp.instance.openItem(item)
        MarketApp.open()
    }

    fun init() {
        XaeroMaps.waypoints(this)
        XaeroMaps.markers(this)
        XaeroMaps.toggle(
            MapToggle(
                "vendors", "kami_economy.xaero.group", "kami_economy.xaero.vendors", "kami_economy.xaero.vendors.tip",
                { MarketPrefs.prefs.xaeroVendors }, { MarketPrefs.prefs.xaeroVendors = it; MarketPrefs.save(); XaeroMaps.refresh() }
            )
        )
    }
}
