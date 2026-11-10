package kami.essentials.skills

import com.mojang.serialization.Codec
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import kami.essentials.KamiEssentials
import kami.libs.skills.SkillClass
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.TickTask
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.tags.DamageTypeTags
import net.minecraft.tags.ItemTags
import net.minecraft.tags.TagKey
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.animal.Animal
import net.minecraft.world.entity.animal.horse.AbstractHorse
import net.minecraft.world.entity.TamableAnimal
import net.minecraft.world.entity.Saddleable
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.Container
import net.minecraft.world.inventory.AnvilMenu
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.FishingRodItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.PotionItem
import net.minecraft.world.item.ShearsItem
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.item.enchantment.ItemEnchantments
import net.minecraft.world.level.block.AttachedStemBlock
import net.minecraft.world.level.block.BambooStalkBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.BonemealableBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CocoaBlock
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.FarmBlock
import net.minecraft.world.level.block.GrowingPlantBlock
import net.minecraft.world.level.block.GrowingPlantHeadBlock
import net.minecraft.world.level.block.NetherWartBlock
import net.minecraft.world.level.block.SaplingBlock
import net.minecraft.world.level.block.StemBlock
import net.minecraft.world.level.block.SweetBerryBushBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.neoforged.neoforge.attachment.AttachmentType
import net.neoforged.bus.api.EventPriority
import net.neoforged.neoforge.common.ItemAbilities
import net.neoforged.neoforge.common.Tags
import net.neoforged.neoforge.common.util.BlockSnapshot
import net.neoforged.neoforge.event.EventHooks
import net.neoforged.neoforge.event.AnvilUpdateEvent
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent
import net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent
import net.neoforged.neoforge.event.entity.player.AnvilRepairEvent
import net.neoforged.neoforge.event.entity.player.BonemealEvent
import net.neoforged.neoforge.event.entity.player.CriticalHitEvent
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent
import net.neoforged.neoforge.event.entity.player.PlayerEnchantItemEvent
import net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.event.entity.player.PlayerXpEvent
import net.neoforged.neoforge.event.level.BlockDropsEvent
import net.neoforged.neoforge.event.level.BlockEvent
import net.neoforged.neoforge.event.level.ExplosionEvent
import net.neoforged.neoforge.event.level.PistonEvent
import net.neoforged.neoforge.registries.DeferredRegister
import net.neoforged.neoforge.registries.NeoForgeRegistries
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS
import java.util.UUID
import java.util.function.Supplier
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object SkillEvents {
    private val attachments = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, KamiEssentials.ID)
    val codec: Codec<LongOpenHashSet> = Codec.LONG_STREAM.xmap({ LongOpenHashSet(it.toArray()) }, { it.longStream() })
    private val placed = attachments.register("placed", Supplier { AttachmentType.builder(Supplier { LongOpenHashSet() }).serialize(codec) { it.isNotEmpty() }.build() })
    private val machines = setOf("create", "tfmg", "vintage")
    private val marked = setOf(Kind.ORE, Kind.LOG, Kind.STONE)
    private val sword by lazy { ItemStack(Items.NETHERITE_SWORD) }
    private val windUsed = HashMap<UUID, Long>()
    private const val WIND_COOLDOWN_MS = 150_000L
    private const val BRANCH_CLASS = "com.dtteam.dynamictrees.block.branch.BranchBlock"
    private const val SOIL_CLASS = "com.dtteam.dynamictrees.block.soil.SoilBlock"
    private const val TREE_SCAN = 96
    private const val TREE_XP_CAP = 8
    private const val DT_AXE_CAP = 1.6
    private val branchTag by lazy { TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("dynamictrees", "branches")) }
    private val soilTag by lazy { TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("dynamictrees", "rooty_soil")) }
    private val branches = lineage(BRANCH_CLASS)
    private val soils = lineage(SOIL_CLASS)
    private val knifeTag by lazy { itemTag("tools/knife") }
    private val vegetableTags by lazy { listOf(Tags.Items.FOODS_VEGETABLE, itemTag("vegetables")) }
    private val fishTags by lazy { listOf(Tags.Items.FOODS_RAW_FISH, Tags.Items.FOODS_COOKED_FISH, itemTag("raw_fishes"), itemTag("cooked_fishes"), itemTag("raw_seafood"), itemTag("cooked_seafood")) }
    private const val DEEP_Y = 50
    private const val MAX_SPEED = 3.0
    private const val MAX_SAVE = 0.9
    private const val BULK_DURABILITY = 64
    private const val CALM_PAUSE = 200
    private const val MAX_KILL_SCALE = 40.0
    private const val SMELT_XP_CAP = 64
    private const val TRADE_ORB = 4.5
    private var veining = false
    private var reflecting = false
    val spawned = setOf(MobSpawnType.NATURAL, MobSpawnType.CHUNK_GENERATION, MobSpawnType.STRUCTURE, MobSpawnType.PATROL, MobSpawnType.EVENT)

    private enum class Kind(val source: String?, val cls: SkillClass?) {
        ORE("ore", SkillClass.MINER), STONE("stone", SkillClass.MINER), LOG("log", SkillClass.LUMBER), LEAF(null, null), CROP("crop", SkillClass.FARMER)
    }

    fun register() {
        attachments.register(MOD_BUS)
        FORGE_BUS.addListener<BlockEvent.EntityPlaceEvent>(EventPriority.LOWEST) { e ->
            val level = e.level as? ServerLevel ?: return@addListener
            if (e.entity is ServerPlayer && kind(e.placedBlock) in marked) mark(level, e.pos, true)
        }
        FORGE_BUS.addListener<PistonEvent.Pre>(EventPriority.LOWEST) { pushed(it) }
        FORGE_BUS.addListener<ExplosionEvent.Detonate> { e -> (e.level as? ServerLevel)?.let { level -> e.affectedBlocks.forEach { mark(level, it, false) } } }
        FORGE_BUS.addListener<BlockEvent.BreakEvent>(EventPriority.LOWEST) { broken(it) }
        FORGE_BUS.addListener<BlockDropsEvent>(::drops)
        FORGE_BUS.addListener<PlayerEvent.BreakSpeed>(::speed)
        FORGE_BUS.addListener<PlayerInteractEvent.RightClickBlock>(EventPriority.LOWEST) { harvested(it) }
        FORGE_BUS.addListener<LivingIncomingDamageEvent>(::damage)
        FORGE_BUS.addListener<CriticalHitEvent> { e -> (e.entity as? ServerPlayer)?.let { p -> v(p, Effects.CRIT_DAMAGE).takeIf { e.isCriticalHit && it > 0 }?.let { e.damageMultiplier *= (1 + it).toFloat() } } }
        FORGE_BUS.addListener<LivingShieldBlockEvent>(::shield)
        FORGE_BUS.addListener<LivingDamageEvent.Post>(::secondWind)
        FORGE_BUS.addListener<LivingChangeTargetEvent>(::target)
        FORGE_BUS.addListener<LivingEntityUseItemEvent.Finish>(::ate)
        FORGE_BUS.addListener<PlayerInteractEvent.RightClickItem>(EventPriority.LOWEST) { paper(it) }
        FORGE_BUS.addListener<TradeWithVillagerEvent>(::traded)
        FORGE_BUS.addListener<PlayerXpEvent.PickupXp>(::orbs)
        FORGE_BUS.addListener<ItemFishedEvent>(::fished)
        FORGE_BUS.addListener<LivingDeathEvent>(::killed)
        FORGE_BUS.addListener<LivingDropsEvent>(::butcher)
        FORGE_BUS.addListener<BabyEntitySpawnEvent>(::bred)
        FORGE_BUS.addListener<BonemealEvent>(::bonemeal)
        FORGE_BUS.addListener<LivingEntityUseItemEvent.Start>(::bite)
        FORGE_BUS.addListener<LivingExperienceDropEvent> { e -> (e.attackingPlayer as? ServerPlayer)?.let { p -> e.droppedExperience += roll(p, e.droppedExperience * v(p, Effects.KILL_XP)) } }
        FORGE_BUS.addListener<PlayerEnchantItemEvent> { e ->
            val p = e.entity as? ServerPlayer ?: return@addListener
            if (p.random.nextDouble() < v(p, Effects.ENCHANT_REFUND)) p.giveExperienceLevels(1)
        }
        FORGE_BUS.addListener<PlayerEvent.ItemSmeltedEvent> { e ->
            val p = e.entity as? ServerPlayer ?: return@addListener
            if (e.smelting.`is`(Tags.Items.INGOTS)) repeat(e.smelting.count.coerceAtMost(SMELT_XP_CAP)) { xp(p, SkillClass.WORKER, "smelt", "smelt:${chunk(p.blockPosition())}") }
        }
        FORGE_BUS.addListener<AnvilRepairEvent> { e -> (e.entity as? ServerPlayer)?.let { p -> e.breakChance *= (1 - v(p, Effects.ANVIL_SAVE)).coerceIn(0.0, 1.0).toFloat() } }
        FORGE_BUS.addListener<AnvilUpdateEvent>(::anvil)
        FORGE_BUS.addListener<PlayerEvent.ItemCraftedEvent> { e ->
            val p = e.entity as? ServerPlayer ?: return@addListener
            if (e.crafting.isEmpty || !SkillServer.enabled) return@addListener
            if (!SkillServer.staff(p) && mixed(e.inventory) && SkillServer.crafted(p, BuiltInRegistries.ITEM.getKey(e.crafting.item).toString())) xp(p, SkillClass.WORKER, "craft", "craft")
            val press = when {
                e.crafting.`is`(ItemTags.PLANKS) -> v(p, Effects.SAWMILL)
                e.crafting.`is`(Items.PAPER) -> v(p, Effects.PAPER_PRESS)
                else -> 0.0
            }
            if (press > 0) roll(p, press).takeIf { it > 0 }?.let { n -> e.crafting.copyWithCount(n).let { if (!p.addItem(it)) p.drop(it, false) } }
        }
        Bonus.register()
    }

    private fun repair(stack: ItemStack) = stack.getOrDefault(DataComponents.REPAIR_COST, 0)

    private fun lineage(name: String) = object : ClassValue<Boolean>() {
        override fun computeValue(type: Class<*>): Boolean = generateSequence(type) { it.superclass }.any { it.name == name }
    }

    private fun itemTag(path: String) = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("c", path))

    private fun food(stack: ItemStack, tags: List<TagKey<Item>>) = stack.has(DataComponents.FOOD) && tags.any { stack.`is`(it) }

    private fun isPickaxe(s: ItemStack) = s.`is`(ItemTags.PICKAXES) || s.canPerformAction(ItemAbilities.PICKAXE_DIG)

    private fun isAxe(s: ItemStack) = s.`is`(ItemTags.AXES) || s.canPerformAction(ItemAbilities.AXE_DIG)

    private fun isHoe(s: ItemStack) = s.`is`(ItemTags.HOES) || s.canPerformAction(ItemAbilities.HOE_DIG)

    private fun isSword(s: ItemStack) = s.`is`(ItemTags.SWORDS) || s.canPerformAction(ItemAbilities.SWORD_SWEEP) || s.`is`(Tags.Items.MELEE_WEAPON_TOOLS) || s.`is`(knifeTag)

    fun underground(p: Player) = p.blockY < DEEP_Y && !p.level().canSeeSky(p.blockPosition())

    private fun mixed(grid: Container) = (0 until grid.containerSize).map { grid.getItem(it) }.filter { !it.isEmpty }.map { it.item }.toSet().size >= 2

    private fun v(p: ServerPlayer, key: String) = SkillServer.value(p, key)

    private fun chunk(pos: BlockPos) = "${pos.x shr 4},${pos.z shr 4}"

    private fun later(p: ServerPlayer, run: () -> Unit) = p.server.tell(TickTask(p.server.tickCount, run))

    private fun roll(p: ServerPlayer, chance: Double) = floor(chance).toInt() + if (p.random.nextDouble() < chance % 1.0) 1 else 0

    private fun effect(p: ServerPlayer, e: Holder<MobEffect>, ticks: Int, amp: Int = 0) = p.addEffect(MobEffectInstance(e, ticks, amp, true, false, true))

    private fun xp(p: ServerPlayer, cls: SkillClass, source: String, bucket: String, scale: Double = 1.0) {
        if (!SkillServer.staff(p)) SkillServer.grant(p.uuid, cls, source, scale, bucket)
    }

    private fun branch(state: BlockState) = state.`is`(branchTag) || branches.get(state.block.javaClass)

    private fun rooty(state: BlockState) = state.`is`(soilTag) || soils.get(state.block.javaClass)

    fun log(state: BlockState) = state.`is`(BlockTags.LOGS) || branch(state)

    private fun ageOf(state: BlockState) = state.properties.firstOrNull { it is IntegerProperty && it.name == "age" } as IntegerProperty?

    private fun crop(state: BlockState): Boolean {
        val b = state.block
        if (b is StemBlock || b is AttachedStemBlock || b is BambooStalkBlock) return false
        if (b is CropBlock || b is SweetBerryBushBlock || b is NetherWartBlock || b is CocoaBlock || state.`is`(BlockTags.CROPS)) return true
        return b is BonemealableBlock && b !is SaplingBlock && b !is GrowingPlantBlock && b !is GrowingPlantHeadBlock && ageOf(state) != null
    }

    private fun mature(state: BlockState): Boolean {
        (state.block as? CropBlock)?.let { return it.isMaxAge(state) }
        val age = ageOf(state) ?: return false
        return state.getValue(age) >= age.possibleValues.max()
    }

    private fun kind(state: BlockState): Kind? = when {
        state.`is`(Tags.Blocks.ORES) -> Kind.ORE
        log(state) -> Kind.LOG
        crop(state) -> Kind.CROP
        state.`is`(BlockTags.LEAVES) -> Kind.LEAF
        state.`is`(BlockTags.BASE_STONE_OVERWORLD) || state.`is`(BlockTags.BASE_STONE_NETHER) -> Kind.STONE
        else -> null
    }

    private fun treeSize(level: ServerLevel, pos: BlockPos): Int {
        val seen = hashSetOf(pos.asLong())
        val queue = ArrayDeque(listOf(pos))
        while (queue.isNotEmpty() && seen.size < TREE_SCAN) {
            val at = queue.removeFirst()
            for (d in Direction.entries) {
                val next = at.relative(d)
                if (next.y >= pos.y && seen.size < TREE_SCAN && !seen.contains(next.asLong()) && branch(level.getBlockState(next))) { seen += next.asLong(); queue += next }
            }
        }
        return seen.size
    }

    private fun isPlaced(level: ServerLevel, pos: BlockPos) = level.getChunkAt(pos).getExistingDataOrNull(placed)?.contains(pos.asLong()) == true

    private fun mark(level: ServerLevel, pos: BlockPos, on: Boolean) {
        val chunk = level.getChunkAt(pos)
        val set = if (on) chunk.getData(placed) else chunk.getExistingDataOrNull(placed) ?: return
        if (if (on) set.add(pos.asLong()) else set.remove(pos.asLong())) chunk.setUnsaved(true)
    }

    private fun broken(e: BlockEvent.BreakEvent) {
        val p = e.player as? ServerPlayer ?: return
        val level = e.level as? ServerLevel ?: return
        val kind = kind(e.state) ?: return
        val source = kind.source ?: return
        val cls = kind.cls ?: return
        if (kind == Kind.CROP) { if (mature(e.state)) xp(p, cls, source, "crop:${chunk(e.pos)}"); return }
        if (isPlaced(level, e.pos)) {
            if (p.isCreative) later(p) { mark(level, e.pos, false) }
            return
        }
        if (kind == Kind.LOG && branch(e.state)) return felled(p, level, e)
        xp(p, cls, source, "$source:${chunk(e.pos)}")
    }

    private fun harvested(e: PlayerInteractEvent.RightClickBlock) {
        val p = e.entity as? ServerPlayer ?: return
        val level = e.level as? ServerLevel ?: return
        val pos = e.pos
        val before = level.getBlockState(pos)
        if (!crop(before) || !mature(before)) return
        later(p) {
            val now = level.getBlockState(pos)
            if (now.block === before.block && !mature(now)) xp(p, SkillClass.FARMER, "crop", "crop:${chunk(pos)}")
        }
    }

    private fun felled(p: ServerPlayer, level: ServerLevel, e: BlockEvent.BreakEvent) {
        val size = treeSize(level, e.pos).coerceAtMost(TREE_XP_CAP)
        repeat(size) { xp(p, SkillClass.LUMBER, "log", "log:${chunk(e.pos)}") }
        if (!SkillServer.enabled) return
        val chance = v(p, Effects.LOG_YIELD)
        if (chance <= 0) return
        val extra = (0 until size).sumOf { roll(p, chance) }
        val log = Block.getDrops(e.state, level, e.pos, null, p, p.mainHandItem).firstOrNull { !it.isEmpty } ?: return
        if (extra > 0) Block.popResource(level, e.pos, log.copyWithCount(extra.coerceAtMost(log.maxStackSize)))
    }

    private fun drops(e: BlockDropsEvent) {
        val kind = kind(e.state) ?: return
        val p = e.breaker as? ServerPlayer
        val wasPlaced = kind != Kind.CROP && isPlaced(e.level, e.pos)
        if (wasPlaced) mark(e.level, e.pos, false)
        if (p == null || wasPlaced || kind == Kind.STONE || !SkillServer.enabled) return
        if (kind == Kind.LOG && branch(e.state)) return
        val silk = EnchantmentHelper.getItemEnchantmentLevel(e.level.registryAccess().holderOrThrow(Enchantments.SILK_TOUCH), e.tool) > 0
        val key = when (kind) {
            Kind.ORE -> Effects.ORE_FORTUNE
            Kind.LOG -> Effects.LOG_YIELD
            Kind.LEAF -> Effects.LEAF_DROPS
            else -> Effects.CROP_FORTUNE
        }
        val chance = when (kind) {
            Kind.LEAF -> if (silk || e.tool.item is ShearsItem) 0.0 else v(p, key)
            Kind.CROP -> if (mature(e.state)) v(p, key) else 0.0
            else -> if (silk) 0.0 else v(p, key)
        }
        if (chance > 0) {
            val block = e.state.block.asItem()
            e.drops.addAll(e.drops.filter { kind != Kind.ORE || it.item.item != block }.flatMap { d -> List(roll(p, chance)) { ItemEntity(e.level, d.x, d.y, d.z, d.item.copy()) } })
            SkillServer.used(p, key)
        }
        if (kind == Kind.CROP && mature(e.state) && v(p, Effects.REPLANT) > 0) replant(p, e)
        if (kind == Kind.ORE && !silk) v(p, Effects.ORE_XP).takeIf { it > 0 && p.random.nextDouble() < it }?.let { e.droppedExperience += 3 }
        if (kind == Kind.ORE && !silk && !veining && p.random.nextDouble() < v(p, Effects.VEIN)) vein(p, e.level, e.pos, e.state)
    }

    private fun replant(p: ServerPlayer, e: BlockDropsEvent) {
        val state = e.state
        val age = ageOf(state) ?: return
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) return
        val seed = e.drops.firstOrNull { (it.item.item as? BlockItem)?.block === state.block } ?: return
        val next = state.setValue(age, age.possibleValues.min())
        if (!e.level.getBlockState(e.pos).canBeReplaced() || !next.canSurvive(e.level, e.pos)) return
        val snapshot = BlockSnapshot.create(e.level.dimension(), e.level, e.pos)
        e.level.setBlockAndUpdate(e.pos, next)
        if (EventHooks.onBlockPlace(p, snapshot, Direction.UP)) {
            snapshot.restore(Block.UPDATE_ALL)
            return
        }
        seed.item.shrink(1)
        if (seed.item.isEmpty) e.drops.remove(seed)
        SkillServer.used(p, Effects.REPLANT)
    }

    private fun vein(p: ServerPlayer, level: ServerLevel, pos: BlockPos, state: BlockState) {
        val next = BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1)).map { it.immutable() }
            .firstOrNull { it != pos && level.getBlockState(it).`is`(state.block) && !isPlaced(level, it) } ?: return
        SkillServer.used(p, Effects.VEIN)
        later(p) {
            veining = true
            try { if (!p.hasDisconnected()) p.gameMode.destroyBlock(next) } finally { veining = false }
        }
    }

    private fun speed(e: PlayerEvent.BreakSpeed) {
        val p = e.entity
        val state = e.state
        val hand = p.mainHandItem
        val id = BuiltInRegistries.BLOCK.getKey(state.block)
        fun value(key: String) = SkillSide.value(p, key)
        var mult = 1.0
        if (value(Effects.DEEPSLATE) > 0) {
            if (state.`is`(Blocks.DEEPSLATE)) mult *= 2.0
            else if (state.`is`(Tags.Blocks.ORES) && id.path.startsWith("deepslate_")) mult *= 1.5
        }
        if (log(state) && isAxe(hand)) mult *= (1 + value(Effects.AXE_SPEED)).let { if (branch(state)) min(it, DT_AXE_CAP) else it }
        if (state.`is`(BlockTags.LEAVES) && value(Effects.LEAF_CUTTER) > 0) mult *= 3.0
        if (id.namespace in machines && kind(state) == null) mult *= 1 + value(Effects.MACHINE_SPEED)
        if (underground(p)) mult *= 1 + value(Effects.DEEP_SPEED)
        if (!p.isInWater) mult *= 1 + value(Effects.LAND_MINING)
        if (isPickaxe(hand)) mult *= 1 + value(Effects.PICKAXE_SPEED)
        if (mult != 1.0) e.newSpeed = (e.newSpeed * mult.coerceIn(0.1, MAX_SPEED)).toFloat()
    }

    private fun pushed(e: PistonEvent.Pre) {
        val level = e.level as? ServerLevel ?: return
        val resolver = e.structureHelper?.takeIf { it.resolve() } ?: return
        val dir = if (e.pistonMoveType.isExtend) e.direction else e.direction.opposite
        val moving = resolver.toPush.filter { isPlaced(level, it) }
        resolver.toDestroy.forEach { mark(level, it, false) }
        moving.forEach { mark(level, it, false) }
        moving.forEach { mark(level, it.relative(dir), true) }
    }

    private fun damage(e: LivingIncomingDamageEvent) {
        val p = e.source.entity as? ServerPlayer ?: return
        val direct = e.source.directEntity
        val melee = direct === p
        if (e.entity === p || e.source.`is`(DamageTypes.THORNS) || !(melee || direct is Projectile && direct.owner === p || e.source.`is`(DamageTypeTags.IS_EXPLOSION))) return
        var amount = e.amount.toDouble()
        var mult = 1 + v(p, Effects.DAMAGE_DEALT)
        if (melee) {
            val hand = p.mainHandItem
            amount += maxOf(
                if (isPickaxe(hand)) v(p, Effects.PICKAXE_DAMAGE) else 0.0,
                if (isHoe(hand)) v(p, Effects.HOE_DAMAGE) else 0.0,
                if (isAxe(hand)) v(p, Effects.AXE_DAMAGE) else 0.0,
                if (isSword(hand)) v(p, Effects.SWORD_DAMAGE) else 0.0
            )
            if (p.health < p.maxHealth * 0.4) mult += v(p, Effects.BERSERK)
        }
        amount *= max(0.1, mult)
        if (amount != e.amount.toDouble()) e.amount = amount.toFloat()
    }

    private fun shield(e: LivingShieldBlockEvent) {
        val p = e.entity as? ServerPlayer ?: return
        val attacker = e.damageSource.entity as? LivingEntity ?: return
        val reflect = v(p, Effects.BULWARK)
        if (reflecting || !e.blocked || reflect <= 0 || attacker === p) return
        reflecting = true
        try { attacker.hurt(p.damageSources().thorns(p), (e.blockedDamage * reflect).toFloat()) } finally { reflecting = false }
    }

    private fun secondWind(e: LivingDamageEvent.Post) {
        val p = e.entity as? ServerPlayer ?: return
        val seconds = v(p, Effects.SECOND_WIND)
        if (seconds <= 0 || !p.isAlive || p.health >= p.maxHealth * 0.3f) return
        val now = System.currentTimeMillis()
        if (now - (windUsed[p.uuid] ?: 0L) < WIND_COOLDOWN_MS) return
        if (windUsed.size > 64) windUsed.values.removeIf { now - it >= WIND_COOLDOWN_MS }
        windUsed[p.uuid] = now
        p.addEffect(MobEffectInstance(MobEffects.REGENERATION, (seconds * 20).toInt(), 1))
    }

    private fun target(e: LivingChangeTargetEvent) {
        val p = e.newAboutToBeSetTarget as? ServerPlayer ?: return
        val mob = e.entity
        if (mob.type.`is`(Tags.EntityTypes.BOSSES) || mob.lastHurtByMob === p || p.tickCount - p.lastHurtMobTimestamp < CALM_PAUSE || v(p, Effects.FARMLAND_CALM) <= 0) return
        if (p.level().getBlockState(p.onPos).block is FarmBlock || p.level().getBlockState(p.blockPosition()).block is FarmBlock) e.isCanceled = true
    }

    private fun ate(e: LivingEntityUseItemEvent.Finish) {
        val p = e.entity as? ServerPlayer ?: return
        val item = e.item
        if (food(item, vegetableTags)) v(p, Effects.VEGGIE_FOOD).toInt().takeIf { it > 0 }?.let { p.foodData.eat(it, 0.1f) }
        if (food(item, fishTags)) {
            v(p, Effects.FISH_BREATH).takeIf { it > 0 }?.let { p.addEffect(MobEffectInstance(MobEffects.WATER_BREATHING, (it * 20).toInt())) }
            v(p, Effects.FISH_FOOD).toInt().takeIf { it > 0 }?.let { p.foodData.eat(it, 0.2f) }
        }
        val brew = v(p, Effects.BREW_DURATION)
        if (brew > 0 && item.item is PotionItem) item.get(DataComponents.POTION_CONTENTS)?.forEachEffect { fx ->
            if (!fx.effect.value().isInstantenous) p.addEffect(MobEffectInstance(fx.effect, (fx.duration * (1 + brew)).toInt(), fx.amplifier, fx.isAmbient, fx.isVisible, fx.showIcon()))
        }
    }

    private fun bite(e: LivingEntityUseItemEvent.Start) {
        val p = e.entity as? ServerPlayer ?: return
        val cut = v(p, Effects.QUICK_BITE)
        if (cut <= 0 || !e.item.has(DataComponents.FOOD) || e.item.`is`(Tags.Items.FOODS_GOLDEN)) return
        e.duration = max(4, (e.duration * (1 - cut)).toInt())
    }

    private fun paper(e: PlayerInteractEvent.RightClickItem) {
        val p = e.entity as? ServerPlayer ?: return
        if (SkillServer.staff(p) || !e.itemStack.`is`(Items.PAPER) || !p.canEat(false) || v(p, Effects.EAT_PAPER) <= 0) return
        e.itemStack.shrink(1)
        p.foodData.eat(1, 0.1f)
        p.level().playSound(null, p.blockPosition(), SoundEvents.GENERIC_EAT, SoundSource.PLAYERS, 0.6f, 1.2f)
        e.isCanceled = true
    }

    private fun traded(e: TradeWithVillagerEvent) {
        val p = e.entity as? ServerPlayer ?: return
        val bonus = v(p, Effects.TRADE_XP)
        if (bonus <= 0 || !e.merchantOffer.shouldRewardExp()) return
        roll(p, TRADE_ORB * bonus).takeIf { it > 0 }?.let { p.giveExperiencePoints(it) }
        SkillServer.used(p, Effects.TRADE_XP)
    }

    private fun orbs(e: PlayerXpEvent.PickupXp) {
        val p = e.entity as? ServerPlayer ?: return
        val bonus = v(p, Effects.ORB_XP)
        if (bonus > 0) roll(p, e.orb.value * bonus).takeIf { it > 0 }?.let { p.giveExperiencePoints(it) }
    }

    private fun fished(e: ItemFishedEvent) {
        val p = e.hookEntity.playerOwner as? ServerPlayer ?: return
        xp(p, SkillClass.FISHER, "fish", "fish:${chunk(e.hookEntity.blockPosition())}")
        if (p.random.nextDouble() < v(p, Effects.FISH_XP)) p.giveExperiencePoints(5)
        if (p.random.nextDouble() < v(p, Effects.DOUBLE_CATCH)) {
            e.drops.forEach { stack -> stack.copy().let { if (!p.addItem(it)) p.drop(it, false) } }
            SkillServer.used(p, Effects.DOUBLE_CATCH)
        }
        Bonus.bucket(p, e.drops)
    }

    private fun killed(e: LivingDeathEvent) {
        val p = e.source.entity as? ServerPlayer ?: return
        val mob = e.entity as? Mob ?: return
        if (mob !is Enemy || mob.spawnType !in spawned) return
        v(p, Effects.BLOODLUST).takeIf { it > 0 }?.let { p.heal(it.toFloat()) }
        xp(p, SkillClass.SOLDIER, "kill", "kill:${chunk(mob.blockPosition())}", mob.maxHealth.toDouble().coerceAtMost(MAX_KILL_SCALE))
    }

    private fun butcher(e: LivingDropsEvent) {
        val p = e.source.entity as? ServerPlayer ?: return
        val animal = e.entity as? Animal ?: return
        if (animal is AbstractHorse || animal is TamableAnimal || animal is Saddleable || EquipmentSlot.entries.any { !animal.getItemBySlot(it).isEmpty }) return
        val chance = v(p, Effects.BUTCHER)
        if (chance > 0) e.drops.addAll(e.drops.flatMap { d -> List(roll(p, chance)) { ItemEntity(d.level(), d.x, d.y, d.z, d.item.copy()) } })
    }

    private fun bred(e: BabyEntitySpawnEvent) {
        val p = e.causedByPlayer as? ServerPlayer ?: return
        xp(p, SkillClass.FARMER, "breed", "breed:${chunk(p.blockPosition())}")
        val cut = v(p, Effects.BREED)
        val growth = v(p, Effects.CALF_GROWTH)
        if (cut <= 0 && growth <= 0) return
        val parents = listOfNotNull(e.parentA as? Animal, e.parentB as? Animal)
        val child = e.child
        later(p) {
            parents.forEach { if (it.isAlive && it.age > 0) it.age = (it.age * (1 - cut)).toInt() }
            if (child != null && child.isAlive && child.age < 0) child.age = (child.age * (1 - growth)).toInt()
        }
    }

    private fun bonemeal(e: BonemealEvent) {
        val p = e.player as? ServerPlayer ?: return
        if (e.isValidBonemealTarget && e.stack.`is`(Items.BONE_MEAL) && p.random.nextDouble() < v(p, Effects.BONEMEAL_SAVE)) later(p) { ItemStack(Items.BONE_MEAL).let { if (!p.addItem(it)) p.drop(it, false) } }
    }

    private fun anvil(e: AnvilUpdateEvent) {
        val p = e.player as? ServerPlayer ?: return
        val left = e.left
        val book = e.right.takeIf { it.`is`(Items.ENCHANTED_BOOK) }?.get(DataComponents.STORED_ENCHANTMENTS) ?: return
        val flag = when {
            isPickaxe(left) -> Effects.TOOL_PICKAXE
            isHoe(left) -> Effects.TOOL_HOE
            isAxe(left) -> Effects.TOOL_AXE
            else -> return
        }
        if (v(p, flag) <= 0 || book.keySet().any { it.value().canEnchant(left) }) return
        val current = EnchantmentHelper.getEnchantmentsForCrafting(left)
        val next = ItemEnchantments.Mutable(current)
        var cost = repair(left) + repair(e.right)
        book.entrySet().forEach { entry ->
            val ench = entry.key
            if (!ench.value().canEnchant(sword) || current.keySet().any { it != ench && !Enchantment.areCompatible(it, ench) }) return@forEach
            val now = next.getLevel(ench)
            val level = if (now == entry.intValue) min(now + 1, ench.value().maxLevel) else max(now, entry.intValue)
            next.set(ench, level)
            cost += ench.value().anvilCost * level
        }
        val result = next.toImmutable()
        if (result == current) return
        val out = left.copy()
        EnchantmentHelper.setEnchantments(out, result)
        val name = e.name
        if (!name.isNullOrBlank()) {
            if (name != left.hoverName.string) { out.set(DataComponents.CUSTOM_NAME, Component.literal(name)); cost++ }
        } else if (left.has(DataComponents.CUSTOM_NAME)) { out.remove(DataComponents.CUSTOM_NAME); cost++ }
        if (cost >= 40 && !p.isCreative) return
        out.set(DataComponents.REPAIR_COST, AnvilMenu.calculateIncreasedRepairCost(max(repair(left), repair(e.right))))
        e.output = out
        e.cost = cost.toLong()
        e.materialCost = 1
    }

    @JvmStatic
    fun durability(stack: ItemStack, p: ServerPlayer, amount: Int): Int {
        if (amount <= 0 || p.hasInfiniteMaterials()) return amount
        val chance = (v(p, Effects.DURABILITY_SAVE) + if (stack.item is FishingRodItem) v(p, Effects.ROD_SAVE) else 0.0).coerceAtMost(MAX_SAVE)
        if (chance <= 0) return amount
        val saved = if (amount > BULK_DURABILITY) (amount * chance).roundToInt() else (0 until amount).count { p.random.nextDouble() < chance }
        if (saved > 0) SkillServer.used(p, Effects.DURABILITY_SAVE)
        return amount - saved
    }

    fun pulse(p: ServerPlayer) {
        if (!SkillServer.enabled) return
        if (v(p, Effects.DARK_SIGHT) > 0 && underground(p)) effect(p, MobEffects.NIGHT_VISION, 300)
        if (p.isInWater) {
            if (v(p, Effects.WATER_SIGHT) > 0) effect(p, MobEffects.NIGHT_VISION, 300)
            if (v(p, Effects.SEA_GRACE) > 0) effect(p, MobEffects.DOLPHINS_GRACE, 60)
            if (v(p, Effects.SEA_CHILD) > 0) effect(p, MobEffects.WATER_BREATHING, 60)
        }
        aura(p, v(p, Effects.CROP_AURA).toInt()) { crop(it) }
        aura(p, v(p, Effects.SAPLING_AURA).toInt()) { it.`is`(BlockTags.SAPLINGS) || rooty(it) }
    }

    private fun aura(p: ServerPlayer, power: Int, test: (BlockState) -> Boolean) {
        if (power <= 0) return
        val level = p.serverLevel()
        val base = p.blockPosition()
        repeat(power * 2) {
            val pos = base.offset(p.random.nextInt(11) - 5, p.random.nextInt(5) - 2, p.random.nextInt(11) - 5)
            val state = level.getBlockState(pos)
            if (test(state)) state.randomTick(level, pos, level.random)
        }
    }

    fun reset() {
        windUsed.clear()
    }

    fun forget(p: ServerPlayer) {
        windUsed.remove(p.uuid)
        Bonus.forget(p)
    }
}
