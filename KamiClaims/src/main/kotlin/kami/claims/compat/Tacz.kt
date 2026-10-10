package kami.claims.compat

import kami.claims.KamiClaims
import kami.claims.world.Guard
import kami.libs.compat.Mods
import kami.libs.compat.Refl
import kami.libs.compat.Soft
import kami.libs.util.RateLimit
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.neoforged.bus.api.EventPriority
import net.neoforged.bus.api.ICancellableEvent
import net.neoforged.neoforge.common.util.FakePlayer

object Tacz {
    const val GUN_SMITH_TABLE = "com.tacz.guns.inventory.GunSmithTableMenu"
    private val errors = RateLimit<Unit>(1, 60_000L)

    fun register() {
        Soft.event(Mods.TACZ, "com.tacz.guns.api.event.common.EntityHurtByGunEvent\$Pre", EventPriority.HIGH) { ev ->
            var victim: Player? = null
            var shooter: Player? = null
            try {
                val hit = Refl.call(ev, "getHurtEntity") as? Player
                victim = hit
                if (hit == null || hit.level().isClientSide) return@event
                val by = Guard.culprit(Refl.call(ev, "getAttacker") as? Entity)?.takeUnless { it is FakePlayer || it === hit } ?: return@event
                shooter = by
                if (!Guard.pvpAllowed(by, hit)) (ev as ICancellableEvent).isCanceled = true
            } catch (t: Throwable) {
                if (errors.allow(Unit)) KamiClaims.LOG.error("TACZ pvp check failed, hits inside claims are blocked", t)
                val v = victim
                if (v == null) {
                    val attacker = runCatching { Guard.culprit(Refl.call(ev, "getAttacker") as? Entity) }.getOrNull()
                    if (attacker is Player && attacker !is FakePlayer) (ev as ICancellableEvent).isCanceled = true
                } else if (!v.level().isClientSide && shooter !== v && Guard.claimedNoPvp(v, shooter)) (ev as ICancellableEvent).isCanceled = true
            }
        }
        Soft.expect(Mods.TACZ, GUN_SMITH_TABLE, "doCraft")
    }
}
