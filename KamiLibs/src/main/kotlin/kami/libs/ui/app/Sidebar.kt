package kami.libs.ui.app

import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Tip
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Metrics
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.Sprites
import kami.libs.ui.anim.anim
import kami.libs.ui.text.tr
import kami.libs.ui.widget.badge
import kami.libs.ui.widget.clickable
import kami.libs.ui.widget.fitText
import kami.libs.ui.widget.iconButton
import kami.libs.ui.widget.scroll
import kotlin.math.roundToInt

private val GROUP_H: Int get() = Metrics.groupH
private const val GROUP_GAP = 7
private val RAIL_TOGGLE_H: Int get() = Metrics.railToggleH
private val MODULES_H: Int get() = Metrics.modulesH

internal class Sidebar(private val app: KamiApp) {
    private val ui get() = app.ui
    private var revealPage: String? = null
    private val navY = HashMap<String, Int>()

    fun reveal(page: String) { revealPage = page }

    fun draw(r: Rect) {
        Draw.sprite(ui.g, Sprites.SIDEBAR, r)
        val groups = app.nav()
        val opens = groups.map { ui.anim(it.openKey, if (app.isCollapsed(it)) 0f else 1f, 14f) }
        val height = groups.withIndex().sumOf { (i, group) ->
            (if (!app.rail) GROUP_H else if (i > 0) 4 else 0) + group.items.sumOf { rowHeight(it, opens[i]) } + GROUP_GAP
        }
        val indicator = groups.firstNotNullOfOrNull { g -> g.items.firstOrNull { it.page == app.route.page } }?.let { navY[it.page] }
        val perRow = ((r.w - 4) / MODULES_H).coerceAtLeast(1)
        val modules = Modules.visible
        val stripH = if (app.module == null || modules.size < 2) 0 else (modules.size + perRow - 1) / perRow * MODULES_H
        if (stripH > 0) moduleStrip(Rect(r.x + 2, r.y + 3, r.w - 4, stripH), perRow, modules)
        val toggleH = if (app.collapsibleRail && !app.compact) RAIL_TOGGLE_H else 0
        val state = ui.scroll("sidebar", r.inset(0, 4 + stripH, 0, 2 + toggleH), height) { c ->
            indicator?.let { rel ->
                val top = c.y + ui.anim("nav:ind", rel.toFloat(), 20f).roundToInt()
                Draw.fill(ui.g, Rect(c.x, top, c.w - 1, NAV_ROW_H), Palette.selected)
                Draw.fill(ui.g, Rect(c.x, top, 2, NAV_ROW_H), Palette.brass)
            }
            var y = c.y
            groups.forEachIndexed { i, group ->
                if (!app.rail) {
                    groupHeader(Rect(c.x, y, c.w - 1, GROUP_H), group)
                    y += GROUP_H
                } else if (i > 0) {
                    Draw.hline(ui.g, c.x + 4, y + 1, c.w - 9, Palette.borderSubtle)
                    y += 4
                }
                group.items.forEach { item ->
                    val shown = rowHeight(item, opens[i])
                    navY[item.page] = y - c.y
                    if (shown >= NAV_ROW_H) navItem(Rect(c.x, y, c.w - 1, NAV_ROW_H), item)
                    else if (shown > 0) ui.clip(Rect(c.x, y, c.w - 1, shown)) { navItem(Rect(c.x, y, c.w - 1, NAV_ROW_H), item) }
                    y += shown
                }
                y += GROUP_GAP
            }
        }
        if (toggleH > 0) {
            val cell = Rect(r.x + 2, r.bottom - toggleH - 1, r.w - 4, toggleH)
            val tip = tr(if (app.railCollapsed) "kami_libs.nav.rail.expand" else "kami_libs.nav.rail.collapse")
            if (ui.iconButton(cell, if (app.railCollapsed) Icons.FORWARD else Icons.BACK, tip, key = "rail:toggle")) app.toggleRail()
        }
        revealPage?.let { page -> groups.firstNotNullOfOrNull { g -> g.items.firstOrNull { it.page == page } }?.let { navY[it.page] }?.let { state.scrollTo(it); revealPage = null } }
    }

    private fun moduleStrip(r: Rect, perRow: Int, modules: List<AppModule>) {
        ui.anchor("modules", r)
        modules.forEachIndexed { i, m ->
            val cell = Rect(r.x + i % perRow * MODULES_H, r.y + i / perRow * MODULES_H, MODULES_H - 2, MODULES_H - 2)
            val active = m.id == app.module
            if (ui.iconButton(cell, m.icon, tr(m.label), selected = active, key = "module:${m.id}") && !active) m.open()
        }
    }

    private fun rowHeight(item: NavItem, open: Float) = if (item.page == app.route.page) NAV_ROW_H else (NAV_ROW_H * open).roundToInt()

    private fun groupHeader(r: Rect, group: NavGroup) {
        val key = group.headerKey
        val collapsed = app.isCollapsed(group)
        val hit = ui.clickable(key, r)
        if (ui.hovering(r)) Draw.fill(ui.g, r, Palette.hover)
        val chevron = if (collapsed) Icons.CHEVRON_RIGHT else Icons.CHEVRON_DOWN
        Draw.tintedIcon(ui.g, chevron, r.x, r.y + (r.h - Draw.ICON) / 2, Draw.ICON, Palette.textMuted)
        val badge = if (collapsed) collapsedBadge(group) else null
        val label = badge?.let { badgeLabel(it.count) }
        if (badge != null && label != null) ui.badge(r.right - Draw.width(label) - 9, r.y + (r.h - 10) / 2, label, badge.severity)
        val room = r.w - 18 - (label?.let { Draw.width(it) + 14 } ?: 0)
        ui.fitText(r.x + 16, r.y + 3, room, group.label.uppercase(), Palette.textMuted)
        ui.focusRing(key, r)
        if (hit) app.toggle(group)
    }

    private fun badgeLabel(count: Int) = if (count > 99) "99+" else count.toString()

    private fun collapsedBadge(group: NavGroup): NavBadge? {
        var count = 0
        var severity: Severity? = null
        for (item in group.items) {
            if (item.lock() != null) continue
            val badge = item.badge() ?: continue
            if (badge.count <= 0) continue
            count += badge.count
            if (severity == null || badge.severity > severity) severity = badge.severity
        }
        return severity?.let { NavBadge(count, it) }
    }

    private fun navItem(r: Rect, item: NavItem) {
        val lock = item.lock()
        val teaser = item.teaser()
        val active = item.page == app.route.page
        val key = item.key
        val hover = ui.hover(key, r)
        ui.anchor(key, r)
        ui.focusable(key)
        if (hover) ui.cursor = Cursor.HAND
        if (hover && !active) Draw.fill(ui.g, r, Palette.hover)
        val iconX = if (app.rail) r.x + (r.w - Draw.ICON) / 2 else r.x + 4
        val iconY = r.y + (r.h - Draw.ICON) / 2
        if (lock != null) Draw.tintedIcon(ui.g, item.icon, iconX, iconY, Draw.ICON, Palette.iconOff) else Draw.icon(ui.g, item.icon, iconX, iconY)
        var shown = item.label
        if (!app.rail) {
            val color = when {
                lock != null -> Palette.textDisabled
                active -> Palette.text
                else -> Palette.textSecondary
            }
            shown = Draw.fit(item.label, r.w - 42)
            Draw.text(ui.g, shown, r.x + 21, Draw.textY(r), color)
            if (lock != null) Draw.tintedIcon(ui.g, Icons.LOCK, r.right - Draw.ICON - 1, iconY, Draw.ICON, Palette.textMuted)
            else if (teaser != null) Draw.tintedIcon(ui.g, Icons.LOCK, r.right - Draw.ICON - 1, iconY, Draw.ICON, Palette.warning)
        }
        item.badge()?.takeIf { it.count > 0 && lock == null }?.let { b ->
            val label = badgeLabel(b.count)
            if (app.rail) ui.badge(r.right - 10, r.y, label, b.severity) else ui.badge(r.right - Draw.width(label) - 9, r.y + (r.h - 10) / 2, label, b.severity)
        }
        ui.tooltip(key, r) {
            when {
                lock != null -> Tip(item.label, listOf(lock to Palette.warning), Severity.WARNING, Icons.LOCK)
                teaser != null -> Tip(item.label, listOf(teaser.reason to Palette.warning), Severity.WARNING, Icons.LOCK)
                app.rail -> Tip.text(app.groupOf(item.page)?.label ?: "", item.label)
                shown != item.label -> Tip.text(item.label)
                else -> null
            }
        }
        ui.focusRing(key, r)
        if (lock == null && (ui.pressed(r) != null || ui.activatedByKey(key))) app.navigate(Route(item.page))
    }
}
