package kami.claims.client.ui

import kami.claims.Rank
import kami.claims.client.ClientHooks
import kami.claims.client.ui.module.ClaimsModule
import kami.claims.client.ui.module.JobsModule
import kami.claims.client.ui.module.MapModule
import kami.claims.client.ui.module.MembersModule
import kami.claims.client.ui.module.OverviewModule
import kami.claims.client.ui.module.PlayersModule
import kami.claims.client.ui.module.PlotsModule
import kami.claims.client.ui.module.ProvinceModule
import kami.claims.client.ui.module.RanksModule
import kami.claims.client.ui.module.RulesModule
import kami.claims.net.Snap
import kami.libs.ui.KamiScreen
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import kotlin.math.max
import kotlin.math.min

abstract class Module(protected val s: ClaimsScreen) {
    open fun init() {}
    open fun draw(g: GuiGraphics, mx: Int, my: Int) {}
    open fun click(mx: Double, my: Double, button: Int) = false
    open fun release(mx: Double, my: Double, button: Int) = false
    open fun drag(mx: Double, my: Double, button: Int, dx: Double, dy: Double) = false
    open fun scroll(mx: Double, my: Double, dy: Double) = false
    open fun key(key: Int) = false
    open fun snapshot() {}
    open fun claimsChanged() {}
}

class ClaimsScreen(first: Snap) : KamiScreen(Component.literal("Country")) {
    enum class Tab(val label: String, val needsCountry: Boolean) {
        OVERVIEW("Overview", false), MAP("Map", false), CLAIMS("Claims", true), PLOTS("Plots", true),
        MEMBERS("Members", true), JOBS("Jobs", true), PROVINCES("Provinces", true), PLAYERS("Players", false),
        RANKS("Ranks", true), RULES("Rules", true)
    }

    var snap = first
        private set
    var left = 0
        private set
    var top = 0
        private set
    var pw = 0
        private set
    var ph = 0
        private set
    var tab = Tab.OVERVIEW
        private set
    private var messageAt = System.currentTimeMillis()
    private val modules: Map<Tab, Module> = mapOf(
        Tab.OVERVIEW to OverviewModule(this), Tab.MAP to MapModule(this), Tab.CLAIMS to ClaimsModule(this), Tab.PLOTS to PlotsModule(this),
        Tab.MEMBERS to MembersModule(this), Tab.JOBS to JobsModule(this), Tab.PROVINCES to ProvinceModule(this), Tab.PLAYERS to PlayersModule(this),
        Tab.RANKS to RanksModule(this), Tab.RULES to RulesModule(this)
    )
    private val delegableCaps = setOf("claim", "capital", "tax", "rules", "jobs")
    private val bannerH get() = if (info?.delegated == true) 16 else 0

    val ax get() = left + 8
    val ay get() = top + 30 + bannerH
    val aw get() = pw - 16
    val ah get() = ph - 30 - 16 - bannerH
    val info get() = snap.info
    val rank: Rank get() = info?.let { Rank.valueOf(it.rank.uppercase()) } ?: Rank.BANISHED
    val text get() = font

    fun can(cap: String) = info != null && rank >= Rank.valueOf((snap.caps[cap] ?: "president").uppercase()) && (info?.delegated != true || cap in delegableCaps)

    fun update(next: Snap) {
        snap = next
        if (next.msg.isNotEmpty()) messageAt = System.currentTimeMillis()
        if (info == null && tab.needsCountry) tab = Tab.OVERVIEW
        modules.values.forEach { it.snapshot() }
        rebuildWidgets()
    }

    fun claimsChanged() = modules.values.forEach { it.claimsChanged() }

    fun act(name: String, vararg args: String) = ClientHooks.request(name, *args)

    fun openMap(x: Int, z: Int) {
        (modules.getValue(Tab.MAP) as MapModule).jump(x, z)
        show(Tab.MAP)
    }

    fun show(t: Tab) {
        tab = t
        rebuildWidgets()
    }

    fun btn(label: String, x: Int, y: Int, w: Int, enabled: Boolean = true, tip: String? = null, h: Int = 16, press: () -> Unit): Button =
        addRenderableWidget(Button.builder(Component.literal(label)) { press() }.bounds(x, y, w, h).build().also {
            it.active = enabled
            if (tip != null) it.setTooltip(Tooltip.create(Component.literal(tip)))
        })

    fun stepper(x: Int, y: Int, enabled: Boolean = true, w: Int = 16, gap: Int = 2, dec: () -> Unit, inc: () -> Unit) {
        btn(Ui.MINUS, x, y, w, enabled, h = 16) { dec() }
        btn(Ui.PLUS, x + w + gap, y, w, enabled, h = 16) { inc() }
    }

    val stepperWidth get() = 34

    fun edit(x: Int, y: Int, w: Int, value: String, limit: Int, hint: String = "", change: (String) -> Unit): EditBox =
        addRenderableWidget(EditBox(font, x, y, w, 16, Component.literal(hint)).also {
            it.setMaxLength(limit)
            it.value = value
            it.setHint(Component.literal(hint).withStyle { st -> st.withColor(0x777777) })
            it.setResponder(change)
        })

    fun label(g: GuiGraphics, value: String, x: Int, y: Int, color: Int = Ui.TEXT) = g.drawString(font, value, x, y, color, false)

    override fun init() {
        pw = (width * 0.85).toInt().coerceIn(360, 760).coerceAtMost(max(200, width - 12))
        ph = (height * 0.85).toInt().coerceIn(240, 520).coerceAtMost(max(150, height - 12))
        left = (width - pw) / 2
        top = (height - ph) / 2
        val tabs = Tab.values().filter { info != null || !it.needsCountry }
        val w = (pw - 16) / tabs.size
        tabs.forEachIndexed { i, t -> btn(t.label, left + 8 + i * w, top + 6, w - 2, t != tab, h = 18) { show(t) } }
        if (info?.delegated == true) btn("${Ui.CROSS} Exit province view", left + 8, top + 28, pw - 16, h = 14) { act("view", "") }
        modules.getValue(tab).init()
    }

    override fun removed() {
        act("close")
        super.removed()
    }

    override fun renderBackground(g: GuiGraphics, mx: Int, my: Int, delta: Float) {
        super.renderBackground(g, mx, my, delta)
        g.fill(left, top, left + pw, top + ph, Ui.BG)
        g.renderOutline(left, top, pw, ph, Ui.LINE)
    }

    override fun render(g: GuiGraphics, mx: Int, my: Int, delta: Float) {
        super.render(g, mx, my, delta)
        modules.getValue(tab).draw(g, mx, my)
        if (snap.msg.isNotEmpty() && System.currentTimeMillis() - messageAt < 7000) {
            g.drawString(font, Ui.fit(snap.msg, pw - 100), left + 8, top + ph - 12, if (snap.ok) Ui.GOOD else Ui.BAD, false)
        }
        g.drawString(font, "Funds ${snap.funds} spur", left + pw - 8 - font.width("Funds ${snap.funds} spur"), top + ph - 12, Ui.DIM, false)
    }

    override fun mouseClicked(mx: Double, my: Double, button: Int): Boolean =
        super.mouseClicked(mx, my, button) || modules.getValue(tab).click(mx, my, button)

    override fun mouseReleased(mx: Double, my: Double, button: Int): Boolean =
        modules.getValue(tab).release(mx, my, button) || super.mouseReleased(mx, my, button)

    override fun mouseDragged(mx: Double, my: Double, button: Int, dx: Double, dy: Double): Boolean =
        modules.getValue(tab).drag(mx, my, button, dx, dy) || super.mouseDragged(mx, my, button, dx, dy)

    override fun mouseScrolled(mx: Double, my: Double, sx: Double, sy: Double): Boolean =
        modules.getValue(tab).scroll(mx, my, sy) || super.mouseScrolled(mx, my, sx, sy)

    override fun keyPressed(key: Int, scan: Int, mods: Int): Boolean {
        if (focused is EditBox) return super.keyPressed(key, scan, mods)
        val digit = key - GLFW.GLFW_KEY_1
        val tabs = Tab.values().filter { info != null || !it.needsCountry }
        if (digit in tabs.indices) { show(tabs[digit]); return true }
        return modules.getValue(tab).key(key) || super.keyPressed(key, scan, mods)
    }
}
