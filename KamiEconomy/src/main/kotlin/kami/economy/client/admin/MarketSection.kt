package kami.economy.client.admin

import kami.economy.client.PriceCache
import kami.economy.client.ui.SearchBox
import kami.economy.client.ui.marketStack
import kami.libs.mc.ItemSpec
import kami.libs.ui.app.AdminSection
import kami.libs.ui.app.AdminSections
import kami.libs.ui.app.NavItem
import kami.libs.ui.core.Tip
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.cellText
import kami.libs.ui.widget.Align
import kami.libs.ui.widget.Column
import kami.libs.ui.widget.textOrder
import kami.libs.ui.widget.specCell
import kami.libs.ui.widget.tagMembers
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack

object MarketSection {
    private const val MAX_IDS = 7000
    private val names = HashMap<String, String>()
    private val stacks = HashMap<String, ItemStack>()

    fun register() = AdminSections.register(AdminSection(
        "market", 30, { "kami_libs.common.market" },
        {
            listOf(
                NavItem("market.orders", tr("kami_economy.admin.nav.orders"), Icons.COIN),
                NavItem("market.starter", tr("kami_economy.admin.nav.starter"), Icons.WHEAT),
                NavItem("market.status", tr("kami_economy.admin.nav.status"), Icons.LOCK),
                NavItem("market.frozen", tr("kami_economy.admin.nav.frozen"), Icons.WARNING),
                NavItem("market.villagers", tr("kami_economy.admin.nav.villagers"), Icons.PEOPLE),
                NavItem("market.stats", tr("kami_libs.common.statistics"), Icons.STATS),
                NavItem("market.quests", tr("kami_libs.flow.quest"), Icons.STAR),
                NavItem("market.quest_spawn", q("nav.spawn"), Icons.ADD),
                NavItem("market.quest_preview", q("nav.preview"), Icons.SEARCH),
                NavItem("market.quest_settings", q("nav.settings"), Icons.SETTINGS),
                NavItem("market.quest_dishes", q("nav.dishes"), Icons.LAYERS),
                NavItem("market.quest_stats", q("nav.stats"), Icons.STATS)
            )
        },
        { app, id ->
            when (id) {
                "market.starter" -> StarterPage(app)
                "market.status" -> StatusPage(app)
                "market.frozen" -> FrozenPage(app)
                "market.villagers" -> VillagerPage(app)
                "market.stats" -> MarketStatsPage()
                "market.quests" -> QuestAdminListPage(app)
                "market.quest_spawn" -> QuestSpawnPage(app)
                "market.quest_preview" -> QuestPreviewPage(app)
                "market.quest_settings" -> QuestSettingsPage(app)
                "market.quest_dishes" -> QuestDishesPage()
                "market.quest_stats" -> QuestStatsPage()
                else -> OrdersPage(app)
            }
        }
    ))

    fun itemName(id: String): String = names.getOrPut(id) {
        if (id.startsWith("#")) id else ItemSpec.stack(id).takeUnless { it.isEmpty }?.hoverName?.string ?: id
    }

    val cachedIds: Set<String> get() = stacks.keys

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
    Column<T>(title, -1, sort = textOrder<T> { MarketSection.itemName(id(it)) }) { _, r, row ->
        val item = id(row)
        if (item.isEmpty()) return@Column
        specCell(r, item, MarketSection.stack(item), label(row).ifEmpty { MarketSection.itemName(item) }, "admin-item:$item")
    }

internal fun <T> tipColumn(title: String, width: Int, sort: Comparator<T>, hint: (T) -> Tip?, align: Align = Align.LEFT, color: (T) -> Int = { Palette.text }, text: (T) -> String) =
    Column<T>(title, width, align, sort) { _, r, row ->
        cellText(r, text(row), color(row), align, "tc:${r.x},${r.y}")
        tooltip("tip-cell:${r.x},${r.y}", r) { hint(row) }
    }
