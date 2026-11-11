package kami.claims.world

import kami.libs.chat.Chat
import kami.libs.chat.Msg
import kami.libs.chat.Theme
import kami.libs.chat.Tone
import kami.libs.chat.bar
import kami.claims.*
import kami.claims.net.Denied
import kami.claims.net.Net
import kami.claims.research.Gate
import kami.claims.research.RecipeFilter
import kami.claims.service.Credit
import kami.claims.service.Housing
import kami.claims.service.Scope
import kami.claims.service.View
import kami.claims.service.Work
import kami.libs.text.Phrase
import kami.claims.gov.Perm
import kami.claims.gov.Permissions
import kami.claims.gov.Standing
import kami.claims.social.Perms
import kami.claims.war.Bill
import kami.claims.war.Reputation
import kami.claims.war.WarGuard
import kami.claims.war.Wars

import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.alchemy.PotionContents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.TagKey
import net.minecraft.world.Container
import net.minecraft.world.InteractionResult
import net.minecraft.world.MenuProvider
import net.minecraft.core.Direction
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.HasCustomInventoryScreen
import net.minecraft.world.entity.OwnableEntity
import net.minecraft.world.entity.NeutralMob
import net.minecraft.world.entity.animal.Animal
import net.minecraft.world.entity.npc.AbstractVillager
import net.minecraft.tags.DamageTypeTags
import net.minecraft.world.phys.EntityHitResult
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.item.PrimedTnt
import net.minecraft.world.entity.projectile.FishingHook
import net.minecraft.world.entity.projectile.Snowball
import net.minecraft.world.entity.projectile.ThrownEgg
import net.minecraft.world.entity.projectile.ThrownEnderpearl
import net.minecraft.world.entity.projectile.ThrownExperienceBottle
import net.minecraft.world.entity.projectile.ThrownPotion
import net.minecraft.world.entity.projectile.AbstractArrow
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.entity.vehicle.ContainerEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.HangingEntity
import net.minecraft.world.entity.vehicle.Boat
import net.minecraft.world.entity.vehicle.VehicleEntity
import net.minecraft.world.item.ArmorStandItem
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.BucketItem
import net.minecraft.world.item.HangingEntityItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.FireChargeItem
import net.minecraft.world.item.FlintAndSteelItem
import net.minecraft.world.item.SolidBucketItem
import net.minecraft.world.item.SpawnEggItem
import net.minecraft.world.item.ShearsItem
import net.minecraft.world.item.LeadItem
import net.minecraft.world.item.HoneycombItem
import net.minecraft.world.item.DyeItem
import net.minecraft.world.item.DiggerItem
import net.minecraft.world.item.BrushItem
import net.minecraft.world.item.BoneMealItem
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.TntBlock
import net.minecraft.world.level.block.SaplingBlock
import net.minecraft.world.level.block.StemBlock
import net.minecraft.world.level.block.state.BlockState
import net.neoforged.neoforge.capabilities.Capabilities
import net.neoforged.neoforge.common.util.FakePlayer
import net.neoforged.neoforge.common.util.TriState
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.event.level.BlockEvent
import net.neoforged.neoforge.event.level.ExplosionEvent
import net.neoforged.neoforge.event.level.PistonEvent

object Guard {
    private val last = HashMap<String, Key?>()
    private val lastDenied = HashMap<String, Pair<Pair<Action, Key>, Long>>()
    private val nodeCache = HashMap<java.util.UUID, HashMap<Perm, Pair<Long, Boolean>>>()
    private const val NODE_CACHE_MS = 5000L
    private val hierarchy = object : ClassValue<Set<String>>() {
        override fun computeValue(type: Class<*>) = generateSequence(type) { it.superclass }.mapTo(HashSet()) { it.name }
    }
    private val typeNames = object : ClassValue<Set<String>>() {
        override fun computeValue(type: Class<*>): Set<String> {
            val out = HashSet<String>()
            fun walk(c: Class<*>?) { if (c != null && out.add(c.name)) { walk(c.superclass); c.interfaces.forEach(::walk) } }
            walk(type)
            return out
        }
    }
    private val trainingTargets: TagKey<EntityType<*>> by lazy { TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath("kami_claims", "training_target")) }
    private val breakToEmpty: TagKey<Block> by lazy { TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("kami_claims", "break_to_empty")) }
    private val farmingPlants: TagKey<Block> by lazy { TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("kami_claims", "farming_plants")) }
    private val forestryPlants: TagKey<Block> by lazy { TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("kami_claims", "forestry_plants")) }
    private val wildBlocks: TagKey<Block> by lazy { TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("kami_claims", "wild_blocked")) }
    private val wildEntities: TagKey<EntityType<*>> by lazy { TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath("kami_claims", "wild_blocked")) }
    private const val BOAT_OWNER = "kami_claims_owner"
    private const val SPLASH_REACH = 4.0
    private const val SPLASH_HEIGHT = 2.0
    private const val DT_SAPLING = "com.dtteam.dynamictrees.block.sapling.DynamicSaplingBlock"
    private const val DT_SEED = "com.dtteam.dynamictrees.item.Seed"

    fun reset() {
        last.clear()
        lastDenied.clear()
        nodeCache.clear()
    }

    private val dims = java.util.concurrent.ConcurrentHashMap<net.minecraft.resources.ResourceKey<Level>, String>()
    private fun dim(level: LevelAccessor): String? {
        val key = (level as? Level)?.dimension() ?: return null
        return dims.getOrPut(key) { key.location().toString() }
    }
    private fun key(dim: String, pos: BlockPos) = Key(dim, pos.x shr 4, pos.z shr 4)
    private fun id(block: Block) = BuiltInRegistries.BLOCK.getKey(block).toString()

    private fun access(c: Country, cl: Claim, a: Action) = c.rules[cl.type]?.get(a)?.takeUnless { ruleLocked(cl.type, a) } ?: cl.def?.rule?.access?.get(a) ?: Access.NONE

    private val usePerm = mapOf(Action.BREAK to Perm.BREAK, Action.PLACE to Perm.PLACE, Action.INTERACT to Perm.INTERACT, Action.CONTAINER to Perm.CONTAINERS)

    private fun nodeAllowed(p: Player, perm: Perm): Boolean {
        if (p !is ServerPlayer) return true
        val now = System.currentTimeMillis()
        val cached = nodeCache.getOrPut(p.uuid) { HashMap() }
        cached[perm]?.takeIf { now - it.first < NODE_CACHE_MS }?.let { return it.second }
        return Perms.permitted(p, perm).also { cached[perm] = now to it }
    }

    private fun granted(a: Access, c: Country, cl: Claim, p: Player, action: Action): Boolean {
        if (a == Access.NONE) return false
        val who = p.stringUUID
        val enemy = Realm.of(who)?.let { Wars.enemies(c.id, it.id) } == true
        if (enemy || who !in c.members) return a == Access.ANY || (a == Access.ALLIED && !enemy && usePerm.getValue(action) in Permissions.outsiderPerms(c, who))
        if (a == Access.ANY) return true
        val perm = usePerm.getValue(action)
        val use = (Permissions.holds(c, who, perm) && nodeAllowed(p, perm)) || Permissions.holds(c, who, Perm.BYPASS_RULES)
        return use && when (a) {
            Access.OFFICER -> Permissions.holds(c, who, Perm.CHUNK_RULES)
            Access.JOB, Access.WORKER -> Work.covers(c, cl, who, action)
            Access.ASSIGNED -> who in cl.workers && Work.covers(c, cl, who, action)
            else -> true
        }
    }

    fun allowed(level: LevelAccessor, pos: BlockPos, who: Entity?, action: Action, block: Block? = null, raid: Boolean = true): Boolean {
        val dim = dim(level)?.takeIf { it in Config.s.dimensionSet } ?: return true
        val cl = Realm.index[key(dim, pos)]
        val free = block != null && action != Action.INTERACT && id(block) in Config.s.freeBlockSet
        val c = cl?.let { Realm.data.countries[it.country] }
        val hostile = cl?.tenant != null || c != null && (who as? Player)?.let { Wars.enemy(it.stringUUID, c) } == true
        if (free && (cl == null || cl.type == "infrastructure" || action == Action.PLACE && !hostile)) return true
        if (cl == null || c == null) {
            val state = block?.defaultBlockState()
            return (action in Config.s.nomanslandAllowSet && state?.`is`(wildBlocks) != true) || (action == Action.PLACE && cl == null && state != null && TempBlocks.fits(state))
        }
        val p = who as? Player
        if (p == null || p is FakePlayer) return cl.def?.rule?.machines == true && machineOrigin(level, dim, pos, cl, who)
        val raidable = raid && WarGuard.build(p, cl, action) &&
            if (action == Action.PLACE) block?.defaultBlockState()?.hasBlockEntity() != true else block == null || !container(level, pos)
        if (raidable) return true
        val me = p.stringUUID
        val banned = c.outsiders[me] == Standing.BANISHED
        if (cl.tenant != null) return !Wars.enemy(me, c) && Housing.plotAccess(cl, me, action, banned)
        if (banned) return false
        return granted(access(c, cl, action), c, cl, p, action)
    }

    private fun machineOrigin(level: LevelAccessor, dim: String, pos: BlockPos, cl: Claim, machine: Entity?): Boolean {
        if (machine != null) return transferAllowed(level, machine.blockPosition(), pos)
        val cx = pos.x shr 4
        val cz = pos.z shr 4
        return listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1).all { (dx, dz) -> Realm.index[Key(dim, cx + dx, cz + dz)].let { it?.country == cl.country && it?.tenant == cl.tenant } }
    }

    fun carried(carrier: Entity, pickup: BlockPos): Boolean =
        if (carrier is Player) !blocked(carrier, pickup, Action.INTERACT) else transferAllowed(carrier.level(), carrier.blockPosition(), pickup)

    private fun container(level: LevelAccessor, pos: BlockPos): Boolean {
        val be = level.getBlockEntity(pos) ?: return false
        return be is Container || (level as? Level)?.getCapability(Capabilities.ItemHandler.BLOCK, pos, be.blockState, be, null) != null
    }

    private fun stash(e: Entity) = e is ContainerEntity || e is ItemFrame || e is ArmorStand || e is HasCustomInventoryScreen

    private fun unclaimed(level: LevelAccessor, pos: BlockPos) = dim(level)?.let { Realm.index[key(it, pos)] } == null

    private fun check(level: LevelAccessor, pos: BlockPos, who: Entity?, action: Action, block: Block?, raid: Boolean = true): Boolean {
        if (allowed(level, pos, who, action, block, raid)) return true
        val p = who as? ServerPlayer ?: return false
        if (Perms.has(p, Perms.BYPASS)) return true
        explain(p, level, pos, action)
        return false
    }

    fun reaches(level: LevelAccessor, pos: BlockPos, who: Player, action: Action) = allowed(level, pos, who, action) || who is ServerPlayer && Perms.has(who, Perms.BYPASS)

    fun blocked(who: Entity, pos: BlockPos, action: Action) = who is ServerPlayer && who !is FakePlayer && !reaches(who.level(), pos, who, action)

    private fun verb(a: Action) = Phrase.of("kami_claims.guard.verb.${a.name.lowercase()}")

    private fun knows(p: Player, c: Country) = Scope.of(p.stringUUID, p is ServerPlayer && Perms.has(p, Perms.ADMIN))(c.id)

    fun reason(level: LevelAccessor, pos: BlockPos, p: Player, action: Action): Phrase {
        val dim = dim(level) ?: return Phrase.of("kami_claims.guard.denied.here")
        val cl = Realm.index[key(dim, pos)]
        val c = cl?.let { Realm.data.countries[it.country] } ?: return Phrase.of("kami_claims.guard.denied.nomansland", verb(action))
        val me = p.stringUUID
        val banned = c.outsiders[me] == Standing.BANISHED
        if (cl.tenant != null) {
            val member = me == cl.owner || cl.roles[me] == Role.HOUSEHOLD
            return when {
                cl.state == Tenancy.MOVING_OUT && member -> Phrase.of("kami_claims.guard.denied.plot_no_place")
                banned && me != cl.owner -> Phrase.of("kami_claims.error.you_banished", c.name)
                !knows(p, c) -> Phrase.of("kami_claims.guard.denied.here")
                cl.state == Tenancy.MOVING_OUT -> Phrase.of("kami_claims.guard.denied.plot_moving_out", Names.of(p.server, cl.owner!!))
                else -> Phrase.of("kami_claims.guard.denied.plot", Names.of(p.server, cl.owner!!))
            }
        }
        if (banned) return Phrase.of("kami_claims.error.you_banished", c.name)
        if (!knows(p, c)) return Phrase.of("kami_claims.guard.denied.here")
        val type = Phrase.or("kami_claims.chunk_type.${cl.type}", cl.type)
        val access = access(c, cl, action)
        val unassigned = access == Access.ASSIGNED && me in c.members && Work.covers(c, cl, me, action) && me !in cl.workers
        return Phrase.of(if (unassigned) "kami_claims.guard.denied.job_unassigned" else "kami_claims.guard.denied.access.${access.name.lowercase()}", c.name, verb(action), type)
    }

    private fun borderDistance(dim: String, pos: BlockPos, standing: BlockPos): Int {
        val here = Realm.index[key(dim, standing)]?.country
        val target = Realm.index[key(dim, pos)]?.country
        if (here == target) return -1
        val cx = pos.x shr 4
        val cz = pos.z shr 4
        val lx = pos.x and 15
        val lz = pos.z and 15
        return listOf(
            Key(dim, cx - 1, cz) to lx + 1, Key(dim, cx + 1, cz) to 16 - lx,
            Key(dim, cx, cz - 1) to lz + 1, Key(dim, cx, cz + 1) to 16 - lz
        ).filter { (k, _) -> Realm.index[k]?.country == here }.minOfOrNull { it.second } ?: -1
    }

    fun explain(p: ServerPlayer, level: LevelAccessor, pos: BlockPos, action: Action) {
        val dim = dim(level) ?: return
        val spot = action to key(dim, pos)
        val now = System.currentTimeMillis()
        val previous = lastDenied[p.stringUUID]
        if (previous != null && previous.first == spot && now - previous.second < 2000) return
        lastDenied[p.stringUUID] = spot to now
        val text = reason(level, pos, p, action)
        val cl = Realm.index[key(dim, pos)]
        val c = cl?.let { Realm.data.countries[it.country] }
        val distance = borderDistance(dim, pos, p.blockPosition())
        if (Net.canOpen(p)) Net.deny(p, Denied(action.name.lowercase(), pos.x, pos.y, pos.z, c?.name ?: "", c?.let { View.color(it) } ?: -1, cl?.takeIf { c != null && knows(p, c) }?.type ?: "", text.json(), distance))
        else p.bar(Chat.bar(Tone.BAD, (if (distance > 0) Phrase.of("kami_claims.guard.denied.border", text, Phrase.plural("kami_claims.unit.block", distance.toLong())) else text).component()))
    }

    fun onBreak(e: BlockEvent.BreakEvent) {
        if (!check(e.level, e.pos, e.player, Action.BREAK, e.state.block)) e.isCanceled = true
    }

    fun onBreakCredit(e: BlockEvent.BreakEvent) {
        val p = e.player as? ServerPlayer ?: return
        if (p is FakePlayer) return
        Credit.block(p, e.pos, Action.BREAK, e.state)
        if (!Wars.fighting()) return
        val dim = dim(e.level)?.takeIf { it in Config.s.dimensionSet } ?: return
        val cl = Realm.index[key(dim, e.pos)] ?: return
        val own = Placed.takeBy(dim, e.pos, p.stringUUID)
        if (!own && e.state.block !is TntBlock && WarGuard.build(p, cl, Action.BREAK) && !container(e.level, e.pos)) Bill.block(p, cl)
    }

    fun onPlace(e: BlockEvent.EntityPlaceEvent) {
        val player = (e.entity as? ServerPlayer)?.takeUnless { it is FakePlayer }
        val block = e.placedBlock.block
        if (!check(e.level, e.pos, e.entity, Action.PLACE, block) || !plantAllowed(e.level, e.pos, e.entity, plantType(block)) ||
            researchLocked(e.entity, e.level, e.pos, block)) {
            e.isCanceled = true
            player?.let { resync(it, e.pos) }
        } else if (!temporary(e.level, e.pos, e.placedBlock, e.blockSnapshot.state, player)) {
            e.isCanceled = true
            player?.let { resync(it, e.pos) }
        } else player?.let { Credit.block(it, e.pos, Action.PLACE, e.placedBlock) }
    }

    private fun temporary(level: LevelAccessor, pos: BlockPos, state: BlockState, replaced: BlockState, player: ServerPlayer?): Boolean {
        val world = level as? ServerLevel ?: return true
        if (!TempBlocks.fits(state) || Action.PLACE in Config.s.nomanslandAllowSet || dim(level) !in Config.s.dimensionSet || !unclaimed(level, pos)) return true
        if (player != null && Perms.has(player, Perms.BYPASS)) return true
        val placed = TempBlocks.place(world, pos, state, replaced)
        val text = if (placed) Phrase.of("kami_claims.guard.temp", "%.0f".format(TempBlocks.seconds(world, pos, state))) else Phrase.of("kami_claims.guard.temp.full")
        player?.bar(Chat.bar(if (placed) Tone.WARN else Tone.BAD, text.component()))
        return placed
    }

    private fun extends(type: Class<*>, name: String): Boolean = name in hierarchy.get(type)

    private fun plantType(block: Block): String? {
        val state = block.defaultBlockState()
        return when {
            state.`is`(farmingPlants) || block is CropBlock || block is StemBlock -> "farming"
            state.`is`(forestryPlants) || block is SaplingBlock || extends(block.javaClass, DT_SAPLING) -> "forestry"
            else -> null
        }
    }

    private fun plantType(item: Item): String? =
        if (item is BlockItem) plantType(item.block) else if (extends(item.javaClass, DT_SEED)) "forestry" else null

    private fun plantAllowed(level: LevelAccessor, pos: BlockPos, who: Entity?, type: String?): Boolean {
        if (type == null) return true
        val dim = dim(level)?.takeIf { it in Config.s.dimensionSet } ?: return true
        if (Realm.index[key(dim, pos)]?.type == type) return true
        val p = (who as? ServerPlayer)?.takeUnless { it is FakePlayer } ?: return false
        if (Perms.has(p, Perms.BYPASS)) return true
        p.bar(Chat.bar(Tone.BAD, Phrase.of("kami_claims.guard.denied.plant", Phrase.or("kami_claims.chunk_type.$type", type)).component()))
        return false
    }

    private fun researchLocked(who: Entity?, level: LevelAccessor, pos: BlockPos, block: Block): Boolean {
        if (who != null && who !is ServerPlayer) return false
        val player = (who as ServerPlayer?)?.takeUnless { it is FakePlayer }
        if (player != null && (RecipeFilter.bypassed(player) || player.isCreative && player.hasPermissions(2))) return false
        val country = if (player != null) Realm.of(player.stringUUID)
        else dim(level)?.let { Realm.index[key(it, pos)] }?.let { Realm.data.countries[it.country] }
        if (Gate.block(country, BuiltInRegistries.BLOCK.getKey(block))) return false
        player?.bar(Chat.bar(Tone.BAD, Phrase.of("kami_claims.research.locked.block", block.name.string).component()))
        return true
    }

    private fun altersBlocks(item: Item) = item is DiggerItem || item is ShearsItem || item is BoneMealItem || item is SpawnEggItem || item is HoneycombItem || item is BrushItem || item is DyeItem

    private fun entityAction(target: Entity, item: Item) = when {
        stash(target) -> Action.CONTAINER
        item is ShearsItem || item is LeadItem || item is DyeItem || item is SpawnEggItem -> Action.BREAK
        else -> Action.INTERACT
    }

    fun onUse(e: PlayerInteractEvent.RightClickBlock) {
        val level = e.level
        if (level.isClientSide) return
        val be = level.getBlockEntity(e.pos)
        val action = if (be is MenuProvider || container(level, e.pos)) Action.CONTAINER else Action.INTERACT
        val state = level.getBlockState(e.pos)
        if (!check(level, e.pos, e.entity, action, state.block)) return run { e.isCanceled = true }
        if (state.`is`(breakToEmpty) && !e.itemStack.isEmpty && !check(level, e.pos, e.entity, Action.BREAK, state.block)) {
            e.isCanceled = true
            (e.entity as? ServerPlayer)?.let { resync(it, e.pos) }
            return
        }
        val item = e.itemStack.item
        if (altersBlocks(item) && !check(level, e.pos, e.entity, Action.BREAK, state.block)) {
            e.isCanceled = true
            (e.entity as? ServerPlayer)?.let { resync(it, e.pos) }
            return
        }
        if (item is BlockItem || item is HangingEntityItem || item is ArmorStandItem || plantType(item) != null) return guardPlacing(e, state)
        val empty = item is BucketItem && item.content == Fluids.EMPTY
        val ignites = item is FlintAndSteelItem || item is FireChargeItem
        if (item !is BucketItem && item !is SolidBucketItem && !ignites) return
        val at = if (empty) e.pos else e.pos.relative(e.face ?: Direction.UP)
        if (check(level, at, e.entity, if (empty) Action.BREAK else Action.PLACE, null)) return run { if (ignites || item is BucketItem && item.content == Fluids.LAVA) burned(e.entity, level, at) }
        e.isCanceled = true
        (e.entity as? ServerPlayer)?.let { resync(it, at) }
    }

    private fun guardPlacing(e: PlayerInteractEvent.RightClickBlock, clicked: BlockState) {
        val player = e.entity as? ServerPlayer ?: return
        val at = if (clicked.canBeReplaced()) e.pos else e.pos.relative(e.face ?: Direction.UP)
        val item = e.itemStack.item
        if (check(e.level, at, player, Action.PLACE, (item as? BlockItem)?.block) && plantAllowed(e.level, at, player, plantType(item))) return
        e.useItem = TriState.FALSE
        resync(player, at)
    }

    fun onUseItem(e: PlayerInteractEvent.RightClickItem) {
        val player = e.entity as? ServerPlayer ?: return
        val item = e.itemStack.item
        if (item !is BucketItem && item !is SolidBucketItem) return
        val empty = item is BucketItem && item.content == Fluids.EMPTY
        val hit = player.pick(player.blockInteractionRange(), 1f, empty) as? BlockHitResult ?: return
        if (hit.type != HitResult.Type.BLOCK) return
        val targets = if (empty) listOf(hit.blockPos) else listOf(hit.blockPos, hit.blockPos.relative(hit.direction))
        if (targets.all { check(player.level(), it, player, if (empty) Action.BREAK else Action.PLACE, null) }) return
        e.isCanceled = true
        e.cancellationResult = InteractionResult.FAIL
        targets.forEach { resync(player, it) }
    }

    private fun burned(who: Entity?, level: LevelAccessor, pos: BlockPos) {
        if (who is Player && Wars.fighting()) dim(level)?.let { Realm.index[key(it, pos)] }?.let { Reputation.material(who, it.country) }
    }

    private fun resync(player: ServerPlayer, at: BlockPos) {
        player.containerMenu.sendAllDataToRemote()
        (listOf(at) + Direction.entries.map(at::relative)).forEach { player.connection.send(ClientboundBlockUpdatePacket(player.level(), it)) }
    }

    fun onEntityUse(e: PlayerInteractEvent) {
        val target = when (e) {
            is PlayerInteractEvent.EntityInteract -> e.target
            is PlayerInteractEvent.EntityInteractSpecific -> e.target
            else -> return
        }
        if (e.level.isClientSide || target is Player) return
        if (wildFrame(target)) {
            val ok = FrameRule.allows(FrameRule.Kind.USE, lootr(target), !(target as ItemFrame).item.isEmpty, e.itemStack.isEmpty)
            val player = e.entity as? ServerPlayer
            if (!ok && (player == null || !Perms.has(player, Perms.BYPASS))) {
                e.isCanceled = true
                player?.bar(Chat.bar(Tone.BAD, Phrase.of("kami_claims.guard.denied.frame").component()))
            }
            return
        }
        val wild = target.type.`is`(wildEntities) && unclaimed(e.level, target.blockPosition())
        if (wild || !check(e.level, target.blockPosition(), e.entity, entityAction(target, e.itemStack.item), null, !stash(target))) e.isCanceled = true
    }

    @JvmStatic
    fun ownBoat(boat: Boat, player: Player) = boat.persistentData.putString(BOAT_OWNER, player.stringUUID)

    private fun breakDenied(attacker: Entity, target: Entity, projectile: Boolean = false): Boolean {
        if (attacker.level().isClientSide || !(target is HangingEntity || target is ArmorStand || target is VehicleEntity) || projectile && trainingTarget(target)) return false
        if (target is Boat && (unclaimed(target.level(), target.blockPosition()) || target.persistentData.getString(BOAT_OWNER) == attacker.stringUUID)) return false
        return !check(attacker.level(), target.blockPosition(), attacker, Action.BREAK, null, !stash(target))
    }

    private fun trainingTarget(target: Entity) = PvpRules.trainingTarget(typeNames.get(target.javaClass), target.type.`is`(trainingTargets))

    fun wildFrame(target: Entity) = target is ItemFrame && Config.s.nomanslandFrameTakeOnly &&
        dim(target.level())?.let { it in Config.s.dimensionSet } == true && unclaimed(target.level(), target.blockPosition())

    private fun lootr(e: Entity) = BuiltInRegistries.ENTITY_TYPE.getKey(e.type).namespace == "lootr" || e.javaClass.name.contains("LootrItemFrame")

    fun onAttack(e: AttackEntityEvent) {
        val frame = e.target
        if (!e.entity.level().isClientSide && wildFrame(frame) && FrameRule.allows(FrameRule.Kind.ATTACK, lootr(frame), !(frame as ItemFrame).item.isEmpty, true)) return
        if (breakDenied(e.entity, e.target)) e.isCanceled = true
    }

    private fun splashHarmsProtected(potion: ThrownPotion): Boolean {
        val thrower = culprit(potion)?.takeUnless { it is FakePlayer } ?: return false
        val harmful = potion.item.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).allEffects.any { !it.effect.value().isBeneficial }
        return harmful && potion.level().getEntitiesOfClass(Player::class.java, potion.boundingBox.inflate(SPLASH_REACH, SPLASH_HEIGHT, SPLASH_REACH)).any { it !== thrower && !pvpAllowed(thrower, it) }
    }

    fun onProjectile(e: ProjectileImpactEvent) {
        val potion = e.projectile as? ThrownPotion
        if (potion != null && !potion.level().isClientSide && splashHarmsProtected(potion)) {
            e.isCanceled = true
            potion.discard()
            return
        }
        val target = (e.rayTraceResult as? EntityHitResult)?.entity ?: return
        if (target is Player) {
            if (target.level().isClientSide) return
            if (harmless(e.projectile)) return
            val shooter = culprit(e.projectile)?.takeUnless { it is FakePlayer || it === target } ?: return
            if (!pvpAllowed(shooter, target)) e.isCanceled = true
            return
        }
        if (e.projectile !is AbstractArrow && e.projectile !is AbstractHurtingProjectile) return
        val shooter = culprit(e.projectile) ?: return
        if (breakDenied(shooter, target, true)) e.isCanceled = true
    }

    private fun harmless(p: Entity) = p is ThrownPotion || p is ThrownEnderpearl || p is ThrownExperienceBottle || p is FishingHook || p is Snowball || p is ThrownEgg ||
        p.javaClass.simpleName.contains("WindCharge")

    fun onTrample(e: BlockEvent.FarmlandTrampleEvent) {
        val p = e.entity as? ServerPlayer
        e.isCanceled = p == null || p is FakePlayer || !allowed(e.level, e.pos, p, Action.BREAK, e.state.block, false)
    }

    fun onGrief(e: EntityMobGriefingEvent) {
        if (Config.s.mobGriefing || dim(e.entity.level())?.let { it in Config.s.dimensionSet } != true) return
        e.setCanGrief(false)
    }

    fun onExplosion(e: ExplosionEvent.Detonate) {
        val level = e.level
        val dim = dim(level)?.takeIf { it in Config.s.dimensionSet } ?: return
        val raider = culprit(e.explosion.indirectSourceEntity)?.takeUnless { it is FakePlayer }
        val raided = HashMap<Claim, Boolean>()
        fun protected(pos: BlockPos, entity: Entity?): Boolean {
            val cl = Realm.index[key(dim, pos)] ?: return !Config.s.nomanslandExplosions
            val raid = raider != null && raided.getOrPut(cl) { WarGuard.build(raider, cl, Action.BREAK) }
            if (raid && entity != null) return entity !is Player
            if (raid && !container(level, pos)) return false
            return cl.def?.rule?.explosions != true
        }
        e.affectedBlocks.removeAll { protected(it, null) }
        e.affectedEntities.removeAll { protected(it.blockPosition(), it) }
        if (raider == null || true !in raided.values) return
        fun billed(pos: BlockPos) = level.getBlockState(pos).let { !it.isAir && it.block !is TntBlock } && !Placed.takeBy(dim, pos, raider.stringUUID)
        val blocks = e.affectedBlocks.mapNotNull { pos -> Realm.index[key(dim, pos)]?.takeIf { raided[it] == true && billed(pos) }?.country }.groupingBy { it }.eachCount()
        raided.filterValues { it }.keys.distinctBy { it.country }.forEach { cl -> blocks[cl.country]?.let { Bill.explosion(raider, cl, it) }; Reputation.material(raider, cl.country) }
    }

    fun onPiston(e: PistonEvent.Pre) {
        val level = e.level
        val dim = dim(level)?.takeIf { it in Config.s.dimensionSet } ?: return
        val helper = e.structureHelper ?: return
        val push = helper.toPush
        val destroy = helper.toDestroy
        if (push.any { TempBlocks.at(dim, it) } || destroy.any { TempBlocks.at(dim, it) }) return run { e.isCanceled = true }
        val war = Wars.fighting()
        if (!Config.s.pistonProtection && !war) return
        val origin = Realm.index[key(dim, e.pos)]
        val extend = e.pistonMoveType.isExtend
        fun moved(test: (BlockPos) -> Boolean) = push.any { test(it) || test(it.relative(helper.pushDirection)) } || extend && (destroy.any(test) || test(e.faceOffsetPos))
        val crossesShield = war && WarGuard.shielded(origin).let { s -> moved { WarGuard.shielded(Realm.index[key(dim, it)]) != s } }
        if (crossesShield || Config.s.pistonProtection && moved { val target = Realm.index[key(dim, it)]; target?.country != origin?.country || target?.tenant != origin?.tenant }) e.isCanceled = true
    }

    fun fireAllowed(level: LevelAccessor, pos: BlockPos): Boolean {
        val dim = dim(level)?.takeIf { it in Config.s.dimensionSet } ?: return true
        val cl = Realm.index[key(dim, pos)]
        val c = cl?.let { Realm.data.countries[it.country] }
        if (cl == null || c == null) return Config.s.nomanslandFire
        if (WarGuard.shielded(cl)) return false
        return c.fire[cl.type] ?: cl.def?.rule?.fire == true
    }

    fun igniteAllowed(level: LevelAccessor, pos: BlockPos, owner: Entity?): Boolean =
        if (owner is ServerPlayer && owner !is FakePlayer) allowed(level, pos, owner, Action.PLACE) || Perms.has(owner, Perms.BYPASS)
        else fireAllowed(level, pos)

    fun transferAllowed(level: LevelAccessor, from: BlockPos, to: BlockPos): Boolean {
        if (from.x shr 4 == to.x shr 4 && from.z shr 4 == to.z shr 4) return true
        val dim = dim(level)?.takeIf { it in Config.s.dimensionSet } ?: return true
        val a = Realm.index[key(dim, from)]
        val b = Realm.index[key(dim, to)]
        return a?.country == b?.country && a?.tenant == b?.tenant
    }

    fun fluidAllowed(level: LevelAccessor, from: BlockPos, to: BlockPos): Boolean {
        val dim = dim(level)?.takeIf { it in Config.s.dimensionSet } ?: return true
        return Housing.fluidFlows(Realm.index[key(dim, from)], Realm.index[key(dim, to)], Config.s.nomanslandFluid)
    }

    private fun livestock(v: Entity) = v is AbstractVillager || v is Animal && v !is Enemy && v !is NeutralMob || (v as? OwnableEntity)?.ownerUUID != null

    internal fun pvpAllowed(attacker: Player, victim: Player): Boolean {
        val dim = dim(victim.level())?.takeIf { it in Config.s.dimensionSet } ?: return true
        val cl = Realm.index[key(dim, victim.blockPosition())]
        return PvpRules.allowed(WarGuard.pvp(attacker, victim, cl), cl != null, cl?.def?.rule?.pvp == true, Config.s.nomanslandPvp)
    }

    internal fun claimedNoPvp(victim: Player, attacker: Player? = null): Boolean {
        val dim = dim(victim.level())?.takeIf { it in Config.s.dimensionSet } ?: return false
        val cl = Realm.index[key(dim, victim.blockPosition())] ?: return false
        if (attacker != null && WarGuard.pvp(attacker, victim, cl) == true) return false
        return cl.def?.rule?.pvp != true
    }

    fun onDamage(e: LivingIncomingDamageEvent) {
        val source = e.source.entity ?: e.source.directEntity
        val attacker = culprit(source) ?: return
        val mob = e.entity
        if (mob !is Player) {
            if (attacker is FakePlayer || !livestock(mob) || e.source.`is`(DamageTypeTags.IS_EXPLOSION) || (mob as? OwnableEntity)?.ownerUUID == attacker.uuid) return
            val mobDim = dim(mob.level())?.takeIf { it in Config.s.dimensionSet } ?: return
            if (Realm.index[key(mobDim, mob.blockPosition())] == null) return
            if (!check(mob.level(), mob.blockPosition(), attacker, Action.BREAK, null)) e.isCanceled = true
            return
        }
        if (!pvpAllowed(attacker, mob)) e.isCanceled = true
    }

    fun onHurt(e: LivingDamageEvent.Post) {
        if (e.newDamage <= 0 || !Wars.fighting()) return
        val victim = e.entity as? Player ?: return
        val attacker = culprit(e.source.entity ?: e.source.directEntity)?.takeUnless { it is FakePlayer || it === victim } ?: return
        val dim = dim(victim.level())?.takeIf { it in Config.s.dimensionSet } ?: return
        Bill.hit(attacker, victim, Realm.index[key(dim, victim.blockPosition())])
        Reputation.hit(attacker, victim, e.source.directEntity)
    }

    fun onDeath(e: LivingDeathEvent) {
        if (!Wars.fighting()) return
        val victim = e.entity
        val killer = (culprit(e.source.entity ?: e.source.directEntity) ?: victim.killCredit as? Player)?.takeUnless { it is FakePlayer || it === victim } ?: return
        val dim = dim(victim.level())?.takeIf { it in Config.s.dimensionSet } ?: return
        val cl = Realm.index[key(dim, victim.blockPosition())]
        when {
            victim is Player -> Bill.kill(killer, victim, cl)
            cl != null && livestock(victim) && victim !is AbstractVillager -> Bill.animal(killer, cl)
        }
    }

    internal fun culprit(e: Entity?): Player? = when (e) {
        is Player -> e
        is Projectile -> culprit(e.owner)
        is OwnableEntity -> culprit(e.owner) ?: e.ownerUUID?.let { (e as Entity).level().server?.playerList?.getPlayer(it) }?.takeIf { it.level() == (e as Entity).level() }
        is PrimedTnt -> culprit(e.owner)
        else -> null
    }

    fun announce(p: ServerPlayer) {
        if (!Config.s.notify) return
        val dim = dim(p.level()) ?: return
        val k = if (dim in Config.s.dimensionSet) key(dim, p.blockPosition()) else null
        val id = p.stringUUID
        if (last.containsKey(id) && last[id] == k) return
        val before = last.put(id, k)
        val from = before?.let { Realm.index[it] }
        val to = k?.let { Realm.index[it] }
        if (k == null || Net.canOpen(p)) return
        val name = to?.let { Realm.data.countries[it.country]?.name }
        val visible = to?.takeIf { cl -> Realm.data.countries[cl.country]?.let { knows(p, it) } == true }
        val detail = visible?.let { cl ->
            val type = Phrase.or("kami_claims.chunk_type.${cl.type}", cl.type)
            cl.owner?.let { o -> Phrase.of("kami_libs.format.dot", type, Names.of(p.server, o)) } ?: type
        }?.component() ?: if (to != null) Component.empty() else Phrase.of("kami_claims.guard.no_building").component()
        val nomansland = Phrase.of("kami_claims.help.term.nomansland").component()
        if (Config.s.titles && from?.country != to?.country) {
            p.connection.send(ClientboundSetTitlesAnimationPacket(8, 40, 12))
            p.connection.send(ClientboundSetSubtitleTextPacket(detail.copy().withColor(Theme.MUTED)))
            p.connection.send(ClientboundSetTitleTextPacket((name?.let { Component.literal(it) } ?: nomansland).withColor(if (name == null) Theme.MUTED else Theme.ACCENT)))
        } else p.bar(Msg().apply { if (name == null) add(nomansland, Theme.MUTED) else { text(name, Theme.ACCENT); muted("  "); add(detail, Theme.MUTED) } }.out)
    }

    fun forget(p: ServerPlayer) { last.remove(p.stringUUID); lastDenied.remove(p.stringUUID); nodeCache.remove(p.uuid); Effects.forget(p) }
}
