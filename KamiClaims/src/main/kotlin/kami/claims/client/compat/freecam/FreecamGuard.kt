package kami.claims.client.compat.freecam

import kami.claims.client.ClientClaims
import kami.claims.service.View
import kami.claims.war.FreecamRule
import kami.libs.compat.Refl
import kami.libs.compat.Soft
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import kotlin.math.ceil

object FreecamGuard {
    private const val MOD = "freecam"
    private const val CLASS = "net.xolt.freecam.Freecam"
    private const val BUILD = "net.xolt.freecam.variant.impl.NormalBuild"
    private const val COLLISION = "net.xolt.freecam.config.CollisionBehavior"
    private const val TICK = "net.neoforged.neoforge.client.event.ClientTickEvent\$Post"
    private const val STEP = 8.0
    private const val MAX_STEPS = 512
    private const val NOTICE_GAP = 40L
    private val FRIENDLY = setOf(View.REL_MEMBER, View.REL_FAMILY, View.REL_ALLY)

    @Volatile
    private var state = 0

    private var good: Vec3? = null
    private var noticed = Long.MIN_VALUE

    private val freecam by lazy { Refl.cls(CLASS) }

    fun register() {
        Soft.expect(MOD, CLASS, "toggle", "toggleTripod", "isEnabled", "getFreeCamera")
        Soft.expect(MOD, BUILD, "cheatsPermitted")
        Soft.expect(MOD, COLLISION, "isIgnored")
        Soft.event(MOD, TICK) { tick() }
    }

    fun receive(next: Int) {
        state = next
    }

    fun clear() {
        state = 0
        good = null
    }

    private fun enabled() = Refl.staticCallOn(freecam, "isEnabled") == true

    private fun ready() = state and FreecamRule.ALLOWED != 0 && ClientClaims.rev >= 0

    fun permits(dim: String, x: Int, z: Int): Boolean {
        val e = ClientClaims.at(dim, x, z) ?: return state and FreecamRule.WARRING == 0
        if (e.flags and (View.OWN or View.ALLY or View.ALLIANCE) != 0) return true
        return ClientClaims.country(e)?.relation in FRIENDLY
    }

    private fun permits(dim: String, pos: Vec3) = permits(dim, Mth.floor(pos.x) shr 4, Mth.floor(pos.z) shr 4)

    private fun clear(dim: String, from: Vec3, to: Vec3): Boolean {
        val steps = ceil(from.distanceTo(to) / STEP).toInt().coerceAtLeast(1)
        if (steps > MAX_STEPS) return false
        return (1..steps).all { permits(dim, from.lerp(to, it.toDouble() / steps)) }
    }

    @JvmStatic
    fun blocked(): Boolean {
        val mc = Minecraft.getInstance()
        if (enabled()) return false
        val player = mc.player ?: return false
        val level = mc.level ?: return false
        if (ready() && permits(level.dimension().location().toString(), player.position())) return false
        say("kami_claims.freecam.blocked")
        return true
    }

    private fun tick() {
        val mc = Minecraft.getInstance()
        val player = mc.player
        val level = mc.level
        if (player == null || level == null || !enabled()) {
            good = null
            return
        }
        val dim = level.dimension().location().toString()
        val from = good ?: player.position()
        if (!ready() || !permits(dim, from)) return off("kami_claims.freecam.blocked")
        val cam = Refl.staticCallOn(freecam, "getFreeCamera") as? Entity ?: return
        val now = cam.position()
        if (clear(dim, from, now)) {
            good = now
            return
        }
        cam.setPos(from.x, from.y, from.z)
        cam.setOldPosAndRot()
        cam.deltaMovement = Vec3.ZERO
        good = from
        say("kami_claims.freecam.area")
    }

    private fun off(key: String) {
        good = null
        Refl.staticCallOn(freecam, "toggle")
        say(key)
    }

    private fun say(key: String) {
        val mc = Minecraft.getInstance()
        val tick = mc.level?.gameTime ?: return
        if (tick - noticed in 0 until NOTICE_GAP) return
        noticed = tick
        mc.player?.displayClientMessage(Component.translatable(key), true)
    }
}
