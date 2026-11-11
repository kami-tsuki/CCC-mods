package kami.essentials.client

import kami.essentials.client.admin.AdminApp
import kami.essentials.net.Net
import kami.essentials.net.Snap
import kami.libs.admin.Admin
import kami.libs.admin.AdminPerms
import kami.libs.net.SnapshotPayload
import kami.libs.net.decode
import kami.libs.text.Text
import kami.libs.ui.app.AdminSections
import kami.libs.ui.app.AppModule
import kami.libs.ui.app.AppScreen
import kami.libs.ui.app.Modules
import kami.libs.ui.app.OpenWait
import kami.libs.ui.app.registerOpenKey
import kami.libs.ui.app.Personal
import kami.libs.ui.app.Route
import kami.libs.ui.hud.Anchor
import kami.libs.ui.hud.BarColor
import kami.libs.ui.hud.BarPrefs
import kami.libs.ui.hud.HudBar
import kami.libs.ui.hud.HudBars
import kami.libs.ui.hud.HudPos
import kami.libs.ui.style.Icons
import kami.libs.ui.text.tr
import kami.essentials.skills.SkillClient
import net.minecraft.client.Minecraft
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import org.lwjgl.glfw.GLFW
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS

object ClientEssentials {
    private const val XP_BAR = "kami_essentials:xp"
    private const val XP_REFRESH_TICKS = 300
    private var primed = false
    private var xpTicks = 0
    private val wait = OpenWait({ tr("kami_essentials.module") }, { tr("kami_essentials.module") }, { tr("kami_essentials.personal.timeout") })

    fun init() {
        Modules.register(AppModule(Personal.MODULE, "kami_essentials.module", Icons.PERSON, 0) { open() })
        Personal.register(bankSection)
        Personal.register(skillsSection)
        Personal.opener = { open(it) }
        Modules.register(AppModule(AdminSections.MODULE, "kami_essentials.admin.module", Icons.SHIELD, 30, visible = { Admin.level >= AdminPerms.Level.VIEW }) { AdminApp.open() })
        AdminSections.opener = { AdminApp.open(it) }
        registerOpenKey("kami_essentials", GLFW.GLFW_KEY_O) { open() }
        wait.register("kami_essentials")
        FORGE_BUS.addListener<ClientTickEvent.Post> {
            prime()
            refreshXp()
            MotdRefresh.tick(Minecraft.getInstance())
        }
        BonusClient.register()
        HudBars.register(HudBar(XP_BAR, { tr("kami_essentials.hud.xp") }, HudPos(Anchor.BOTTOM, 0, -48), BarPrefs(color = BarColor.GREEN), SkillClient::bar))
        FORGE_BUS.addListener<ClientPlayerNetworkEvent.LoggingOut> {
            primed = false
            SkillClient.update(null)
            PersonalApp.instance.snap = null
            wait.done()
            Personal.account = null
            Personal.subject = null
            AdminApp.reset()
        }
    }

    private fun open(route: Route? = null) {
        val mc = Minecraft.getInstance()
        val app = PersonalApp.instance
        if ((mc.screen as? AppScreen)?.app !== app) mc.setScreen(AppScreen(app, Text.msg("kami_essentials.module")))
        route?.let { app.navigate(it) }
        Personal.sections.forEach { it.refresh("") }
        if (!app.ready) wait.start()
        request("open", Personal.subjectArg)
    }

    private fun refreshXp() {
        val mc = Minecraft.getInstance()
        if (primed && mc.screen == null && mc.player != null && ++xpTicks % XP_REFRESH_TICKS == 0 && HudBars.visible(XP_BAR)) request("open", Personal.subjectArg)
    }

    private fun prime() {
        val mc = Minecraft.getInstance()
        if (primed || mc.player == null || mc.connection?.hasChannel(Net.actType) != true) return
        primed = true
        request("open", Personal.subjectArg)
    }

    fun request(name: String, vararg args: String) {
        if (Minecraft.getInstance().connection?.hasChannel(Net.actType) == true) PacketDistributor.sendToServer(Net.act(name, *args))
    }

    fun receive(s: SnapshotPayload) {
        val snap = s.decode<Snap>() ?: return
        if (snap.viewing == null || snap.viewing == Minecraft.getInstance().player?.stringUUID) SkillClient.update(snap.skills)
        if (snap.viewing.orEmpty() != Personal.subjectArg) {
            if (Personal.viewing && snap.viewing == Minecraft.getInstance().player?.stringUUID) {
                Personal.subject = null
                request("open", "")
            }
            return
        }
        PersonalApp.instance.snap = snap
        Personal.account = snap.account
        wait.done()
    }
}
