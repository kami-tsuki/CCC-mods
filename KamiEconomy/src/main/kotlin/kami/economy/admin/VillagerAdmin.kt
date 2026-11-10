package kami.economy.admin

import kami.economy.Config
import kami.economy.villager.Quotes
import kami.economy.villager.VillagerCourses
import kami.libs.admin.Admin
import kami.libs.admin.AdminCtx
import kami.libs.admin.text
import kami.libs.db.Db
import kami.libs.mc.ItemSpec
import kami.libs.economy.Flow
import kami.libs.economy.ServerBank
import kami.libs.util.Time.DAY_MS
import kotlinx.serialization.Serializable
import net.minecraft.world.entity.npc.AbstractVillager

@Serializable
class VillagerRow(val spec: String, val course: Double, val floor: Double, val state: String, val fills: Long, val last: Long)

@Serializable
class VillagerOffer(val spec: String, val side: Int, val price: Long, val unit: Double, val spread: Double, val demand: Double, val uses: Int, val max: Int)

@Serializable
class NearVillager(val name: String, val epoch: Int, val offers: List<VillagerOffer>)

@Serializable
class VillagerOverview(val enabled: Boolean, val emerald: Double, val volume: Long, val rows: List<VillagerRow>, val near: NearVillager?)

object VillagerAdmin {
    const val VIEW = "market.villagers"
    const val SET = "market.villager_set"
    const val UNLOCK = "market.villager_unlock"
    const val RESET = "market.villager_reset"
    private const val NEAR = 16.0

    fun register() {
        Admin.view(VIEW) { ctx, _ -> Db.encode(VillagerOverview.serializer(), overview(ctx)) }
        Admin.act(SET) { ctx, a ->
            val key = a.text(0)?.takeIf(::settable) ?: ctx.fail("kami_economy.admin.unknown_good", a.text(0).orEmpty())
            val course = a.text(1)?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 && it <= Config.s.maxPrice } ?: ctx.fail("kami_economy.admin.invalid_good")
            val before = VillagerCourses.all()[key]?.course
            VillagerCourses.set(key, course, a.text(2) == "1")
            Admin.done(key, audit = mapOf("before" to before.toString(), "after" to course.toString()))
        }
        Admin.act(UNLOCK) { ctx, a ->
            val key = known(ctx, a)
            VillagerCourses.unlock(key)
            Admin.done(key)
        }
        Admin.act(RESET) { ctx, a ->
            val key = known(ctx, a)
            VillagerCourses.reset(key)
            Admin.done(key)
        }
    }

    private fun settable(key: String) = key in VillagerCourses.all() || !ItemSpec.stack(key).isEmpty

    private fun known(ctx: AdminCtx, a: List<String>) = a.text(0)?.takeIf { it in VillagerCourses.all() } ?: ctx.fail("kami_economy.admin.unknown_good", a.text(0).orEmpty())

    private fun overview(ctx: AdminCtx): VillagerOverview {
        val today = ServerBank.days(System.currentTimeMillis() / DAY_MS)
        val volume = today.filter { it.flow == Flow.VILLAGER }.sumOf { it.inflow + it.outflow }
        val rows = VillagerCourses.all().map { (k, v) -> VillagerRow(k, v.course, v.floor, v.state.name.lowercase(), v.fills, v.last) }.sortedByDescending { it.last }
        return VillagerOverview(Config.s.villagers.enabled, VillagerCourses.emeraldUnit() ?: 0.0, volume, rows, near(ctx))
    }

    private fun near(ctx: AdminCtx): NearVillager? {
        val p = ctx.player
        val v = p.level().getEntitiesOfClass(AbstractVillager::class.java, p.boundingBox.inflate(NEAR)).minByOrNull { it.distanceToSqr(p) } ?: return null
        val q = Quotes.peek(v) ?: return NearVillager(v.name.string, -1, emptyList())
        val offers = v.offers.zip(q.lines) { o, l -> VillagerOffer(l.spec, l.side, l.price, l.unit, l.spread, l.demand, o.uses, o.maxUses) }
        return NearVillager(v.name.string, q.epoch, offers)
    }
}
