package kami.claims.world

import kami.libs.chat.Chat
import kami.libs.chat.Msg
import kami.libs.chat.Theme
import kami.libs.chat.Tone
import kami.libs.chat.bar
import kami.claims.*
import kami.claims.social.Perms

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.TagKey
import net.minecraft.world.Container
import net.minecraft.world.MenuProvider
import net.minecraft.core.Direction
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.HangingEntity
import net.minecraft.world.entity.vehicle.VehicleEntity
import net.minecraft.world.item.BucketItem
import net.minecraft.world.item.SolidBucketItem
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.state.BlockState
import net.neoforged.neoforge.common.util.FakePlayer
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.event.level.BlockEvent
import net.neoforged.neoforge.event.level.ExplosionEvent
import net.neoforged.neoforge.event.level.PistonEvent

object Guard {
    private val tags = HashMap<String, TagKey<Block>>()
    private val last = HashMap<String, Key?>()

    private fun dim(level: LevelAccessor) = (level as? Level)?.dimension()?.location()?.toString()
    private fun key(dim: String, pos: BlockPos) = Key(dim, pos.x shr 4, pos.z shr 4)
    private fun id(block: Block) = BuiltInRegistries.BLOCK.getKey(block).toString()

    private fun access(c: Country, cl: Claim, a: Action) = c.rules[cl.type]?.get(a) ?: cl.def?.rule?.access?.get(a) ?: Access.NONE

    private fun granted(a: Access, c: Country, cl: Claim, who: String): Boolean {
        val rank = c.rank(who) ?: return a == Access.ANY
        val job = c.members[who]?.job
        return when (a) {
            Access.NONE -> false
            Access.ANY -> true
            Access.ALLIED -> rank >= Rank.ALLIED
            Access.CITIZEN -> rank >= Rank.CITIZEN
            Access.WORKER -> rank >= Rank.OFFICER || (rank >= Rank.CITIZEN && job != null)
            Access.JOB -> rank >= Rank.OFFICER || (rank >= Rank.CITIZEN && job != null && (cl.def?.job == null || cl.def?.job == job))
            Access.OFFICER -> rank >= Rank.OFFICER
        }
    }

    fun plotOpen(cl: Claim, c: Country) = cl.lapse < c.shutdown

    fun allowed(level: LevelAccessor, pos: BlockPos, who: Entity?, action: Action, block: Block? = null): Boolean {
        val dim = dim(level)?.takeIf { it in Config.s.dimensionSet } ?: return true
        val cl = Realm.index[key(dim, pos)]
        val free = block != null && action != Action.INTERACT && id(block) in Config.s.freeBlockSet
        val c = cl?.let { Realm.data.countries[it.country] }
        if (free && (action == Action.PLACE || cl == null || cl.type == "infrastructure")) return true
        if (cl == null || c == null) return action in Config.s.nomanslandAllowSet
        val p = who as? Player
        if (p == null || p is FakePlayer) return c.machines[cl.type] ?: cl.def?.rule?.machines ?: false
        val me = p.stringUUID
        if (c.outsiders[me] == Rank.BANISHED) return false
        if (cl.type == "residential" && cl.owner != null) {
            if (!plotOpen(cl, c)) return false
            return when (if (me == cl.owner) Role.OWNER else cl.roles[me]) {
                Role.OWNER, Role.HOUSEHOLD -> true
                Role.ALLIED -> action in Config.s.plotAlliedSet
                else -> false
            }
        }
        return granted(access(c, cl, action), c, cl, me)
    }

    private fun check(level: LevelAccessor, pos: BlockPos, who: Entity?, action: Action, block: Block?): Boolean {
        if (allowed(level, pos, who, action, block)) return true
        val p = who as? ServerPlayer ?: return false
        if (Perms.has(p, Perms.BYPASS)) return true
        p.bar(Chat.bar(Tone.BAD, "You can't do that here"))
        return false
    }

    private fun matches(state: BlockState, spec: String) =
        if (spec.startsWith("#")) state.`is`(tags.getOrPut(spec) { TagKey.create(Registries.BLOCK, ResourceLocation.parse(spec.drop(1))) })
        else id(state.block) == spec

    private fun credit(p: ServerPlayer, pos: BlockPos, action: Action, state: BlockState) {
        val cl = Realm.index[key(p.level().dimension().location().toString(), pos)] ?: return
        val me = p.stringUUID
        val m = Realm.data.countries[cl.country]?.members?.get(me) ?: return
        val cfg = Config.s.jobs[m.job] ?: return
        if (cl.type != cfg.type || action !in cfg.actions) return
        if (m.zone.isNotEmpty() && cl.key.toString() !in m.zone) return
        if (cfg.blocks.isNotEmpty() && cfg.blocks.none { matches(state, it) }) return
        val crop = state.block as? CropBlock
        if (crop != null && !crop.isMaxAge(state)) return
        m.progress++
        Realm.dirty = true
    }

    fun onBreak(e: BlockEvent.BreakEvent) {
        if (!check(e.level, e.pos, e.player, Action.BREAK, e.state.block)) e.isCanceled = true
        else (e.player as? ServerPlayer)?.let { if (it !is FakePlayer) credit(it, e.pos, Action.BREAK, e.state) }
    }

    fun onPlace(e: BlockEvent.EntityPlaceEvent) {
        if (!check(e.level, e.pos, e.entity, Action.PLACE, e.placedBlock.block)) e.isCanceled = true
        else (e.entity as? ServerPlayer)?.let { if (it !is FakePlayer) credit(it, e.pos, Action.PLACE, e.placedBlock) }
    }

    fun onUse(e: PlayerInteractEvent.RightClickBlock) {
        val level = e.level
        if (level.isClientSide) return
        val be = level.getBlockEntity(e.pos)
        val action = if (be is MenuProvider || be is Container) Action.CONTAINER else Action.INTERACT
        if (!check(level, e.pos, e.entity, action, level.getBlockState(e.pos).block)) return run { e.isCanceled = true }
        val item = e.itemStack.item
        val empty = item is BucketItem && item.content == Fluids.EMPTY
        if (item !is BucketItem && item !is SolidBucketItem) return
        val at = if (empty) e.pos else e.pos.relative(e.face ?: Direction.UP)
        if (!check(level, at, e.entity, if (empty) Action.BREAK else Action.PLACE, null)) e.isCanceled = true
    }

    fun onEntityUse(e: PlayerInteractEvent) {
        val target = when (e) {
            is PlayerInteractEvent.EntityInteract -> e.target
            is PlayerInteractEvent.EntityInteractSpecific -> e.target
            else -> return
        }
        if (e.level.isClientSide || target is Player) return
        if (!check(e.level, target.blockPosition(), e.entity, Action.INTERACT, null)) e.isCanceled = true
    }

    fun onAttack(e: AttackEntityEvent) {
        val target = e.target
        if (e.entity.level().isClientSide || !(target is HangingEntity || target is ArmorStand || target is VehicleEntity)) return
        if (!check(e.entity.level(), target.blockPosition(), e.entity, Action.BREAK, null)) e.isCanceled = true
    }

    fun onTrample(e: BlockEvent.FarmlandTrampleEvent) {
        val level = e.level
        val dim = dim(level)?.takeIf { it in Config.s.dimensionSet } ?: return
        val who = e.entity
        if (who is Player && allowed(level, e.pos, who, Action.BREAK)) return
        if (Realm.index[key(dim, e.pos)] != null || who !is Player) e.isCanceled = true
    }

    fun onGrief(e: EntityMobGriefingEvent) {
        if (Config.s.mobGriefing || dim(e.entity.level())?.let { it in Config.s.dimensionSet } != true) return
        e.setCanGrief(false)
    }

    fun onExplosion(e: ExplosionEvent.Detonate) {
        val level = e.level
        val dim = dim(level)?.takeIf { it in Config.s.dimensionSet } ?: return
        fun protected(pos: BlockPos): Boolean {
            val cl = Realm.index[key(dim, pos)]
            return if (cl == null) !Config.s.nomanslandExplosions else cl.def?.rule?.explosions != true
        }
        e.affectedBlocks.removeAll { protected(it) }
        e.affectedEntities.removeAll { protected(it.blockPosition()) }
    }

    fun onPiston(e: PistonEvent.Pre) {
        if (!Config.s.pistonProtection) return
        val level = e.level
        val dim = dim(level)?.takeIf { it in Config.s.dimensionSet } ?: return
        val origin = Realm.index[key(dim, e.pos)]?.country
        val helper = e.structureHelper ?: return
        val moved = helper.toPush + helper.toDestroy + e.faceOffsetPos
        if (moved.any { Realm.index[key(dim, it)]?.country != origin }) e.isCanceled = true
    }

    fun fireAllowed(level: LevelAccessor, pos: BlockPos): Boolean {
        val dim = dim(level)?.takeIf { it in Config.s.dimensionSet } ?: return true
        val cl = Realm.index[key(dim, pos)]
        val c = cl?.let { Realm.data.countries[it.country] }
        if (cl == null || c == null) return Config.s.nomanslandFire
        return c.fire[cl.type] ?: cl.def?.rule?.fire == true
    }

    fun fluidAllowed(level: LevelAccessor, pos: BlockPos): Boolean {
        val dim = dim(level)?.takeIf { it in Config.s.dimensionSet } ?: return true
        val cl = Realm.index[key(dim, pos)]
        val c = cl?.let { Realm.data.countries[it.country] }
        if (cl == null || c == null) return Config.s.nomanslandFluid
        return c.fluid[cl.type] ?: cl.def?.rule?.fluid == true
    }

    fun onDamage(e: LivingIncomingDamageEvent) {
        val victim = e.entity as? Player ?: return
        if (e.source.entity !is Player) return
        val dim = dim(victim.level())?.takeIf { it in Config.s.dimensionSet } ?: return
        val cl = Realm.index[key(dim, victim.blockPosition())]
        val pvp = if (cl == null) Config.s.nomanslandPvp else cl.def?.rule?.pvp == true
        if (!pvp) e.isCanceled = true
    }

    fun announce(p: ServerPlayer) {
        if (!Config.s.notify) return
        val dim = p.level().dimension().location().toString()
        val k = if (dim in Config.s.dimensionSet) key(dim, p.blockPosition()) else null
        val id = p.stringUUID
        if (last.containsKey(id) && last[id] == k) return
        val before = last.put(id, k)
        val from = before?.let { Realm.index[it] }
        val to = k?.let { Realm.index[it] }
        if (k == null) return
        val name = to?.let { Realm.data.countries[it.country]?.name }
        val detail = to?.let { "${it.type}${it.owner?.let { o -> " - ${Names.of(p.server, o)}" } ?: ""}" } ?: "nothing can be built here"
        if (Config.s.titles && from?.country != to?.country) {
            p.connection.send(ClientboundSetTitlesAnimationPacket(8, 40, 12))
            p.connection.send(ClientboundSetSubtitleTextPacket(Component.literal(detail).withColor(Theme.MUTED)))
            p.connection.send(ClientboundSetTitleTextPacket(Component.literal(name ?: "Nomansland").withColor(if (name == null) Theme.MUTED else Theme.ACCENT)))
        } else p.bar(Msg().apply { if (name == null) muted("Nomansland") else { text(name, Theme.ACCENT); muted("  $detail") } }.out)
    }

    fun forget(p: ServerPlayer) { last.remove(p.stringUUID); Effects.forget(p) }
}
