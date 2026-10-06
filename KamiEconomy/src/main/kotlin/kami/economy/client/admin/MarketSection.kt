package kami.economy.client.admin

import kami.economy.client.PriceCache
import kami.economy.client.ui.SearchBox
import kami.economy.client.ui.marketStack
import kami.libs.mc.ItemSpec
import kami.libs.ui.app.AdminSection
import kami.libs.ui.app.AdminSections
import kami.libs.ui.app.NavItem
import kami.libs.ui.core.Tip
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.Align
import kami.libs.ui.widget.Column
import kami.libs.ui.widget.specCell
import kami.libs.ui.widget.tagMembers
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack

object MarketSection {
    private const val MAX_IDS = 7000
    private val names = HashMap<String, String>()
    private val stacks = HashMap<String, ItemStack>()

    fun register() = AdminSections.register(AdminSection(
        "market", 30, { "kami_economy.admin.nav.group" },
        {
            listOf(
                NavItem("market.orders", tr("kami_economy.admin.nav.orders"), Icons.COIN),
                NavItem("market.starter", tr("kami_economy.admin.nav.starter"), Icons.WHEAT),
                NavItem("market.status", tr("kami_economy.admin.nav.status"), Icons.LOCK),
                NavItem("market.frozen", tr("kami_economy.admin.nav.frozen"), Icons.WARNING)
            )
        },
        { app, id ->
            when (id) {
                "market.starter" -> StarterPage(app)
                "market.status" -> StatusPage(app)
                "market.frozen" -> FrozenPage(app)
                else -> OrdersPage(app)
            }
        }
    ))

    fun itemName(id: String): String = names.getOrPut(id) {
        if (id.startsWith("#")) id else ItemSpec.stack(id).takeUnless { it.isEmpty }?.hoverName?.string ?: id
    }

    fun stack(id: String): ItemStack = if (id.startsWith("#")) tagMembers(id).firstOrNull() ?: marketStack(id) else stacks.getOrPut(id) { marketStack(id) }

    fun valid(item: String): Boolean = if (item.startsWith("#")) ResourceLocation.tryParse(item.drop(1)) != null else !ItemSpec.stack(item).isEmpty

    internal fun searchBox() = SearchBox(::stack)

    internal fun ids(box: SearchBox) = box.ids({ PriceCache.items }, MAX_IDS)

    fun clear() {
        names.clear()
        stacks.clear()
    }
}

internal fun <T> itemColumn(title: String, id: (T) -> String, label: (T) -> String = { "" }) =
    Column<T>(title, -1, sort = compareBy<T> { MarketSection.itemName(id(it)).lowercase() }) { _, r, row ->
        val item = id(row)
        if (item.isEmpty()) return@Column
        specCell(r, item, MarketSection.stack(item), label(row).ifEmpty { MarketSection.itemName(item) }, "admin-item:${r.y}:$item")
    }

internal fun <T> tipColumn(title: String, width: Int, sort: Comparator<T>, hint: (T) -> Tip?, align: Align = Align.LEFT, color: (T) -> Int = { Palette.text }, text: (T) -> String) =
    Column<T>(title, width, align, sort) { _, r, row ->
        val s = Draw.fit(text(row), r.w)
        Draw.text(g, s, if (align == Align.RIGHT) r.right - Draw.width(s) else r.x, r.y + (r.h - 8) / 2, color(row))
        tooltip("tip-cell:${r.x},${r.y}", r) { hint(row) }
    }
