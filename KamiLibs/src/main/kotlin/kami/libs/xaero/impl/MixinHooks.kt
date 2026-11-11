package kami.libs.xaero.impl

import java.util.UUID
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

object MixinHooks {
    private val radar = Guard("radar hook")
    private val cave = Guard("cave hook")
    private val shared = Guard("mixin")

    @JvmStatic
    fun fail(t: Throwable) = shared.report(t)

    @JvmStatic
    fun radarHidden(id: UUID): Boolean = radar.call(false) { (Registry.fairPlayActive() || Registry.radarHidden(id)).also { Registry.radarHooked = true } }

    @JvmStatic
    fun caveBlocked(): Boolean = cave.call(false) { Registry.fairPlayActive().also { Registry.caveHooked = true } }

    @JvmStatic
    fun fairCave(cir: CallbackInfoReturnable<Boolean>) {
        try {
            if (caveBlocked()) cir.setReturnValue(false)
        } catch (t: Throwable) {
            fail(t)
        }
    }

    @JvmStatic
    fun attach(target: Any) = WorldMapAdapter.attach(target)

    @JvmStatic
    fun rightClick(gui: Any, start: Int): List<Any> = WorldMapAdapter.rightClick(gui, start)

    @JvmStatic
    fun display(): Any? = MinimapAdapter.display()
}
