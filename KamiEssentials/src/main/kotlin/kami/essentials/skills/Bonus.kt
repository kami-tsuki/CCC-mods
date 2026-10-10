package kami.essentials.skills

import kami.libs.claims.ClaimsApi
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.animal.Bee
import net.minecraft.world.entity.animal.Bucketable
import net.minecraft.world.entity.animal.horse.AbstractHorse
import net.minecraft.world.entity.animal.horse.Llama
import net.minecraft.world.entity.animal.horse.SkeletonHorse
import net.minecraft.world.entity.animal.horse.ZombieHorse
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.entity.monster.Drowned
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.inventory.CraftingMenu
import net.minecraft.world.inventory.MenuConstructor
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent
import net.neoforged.neoforge.event.entity.living.LivingFallEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.event.entity.player.PlayerSpawnPhantomsEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import java.util.UUID
import kotlin.math.min

object Bonus {
    private const val COMBAT_TICKS = 200
    private const val WARN_TICKS = 80
    private const val MAGNET_RANGE = 4.0
    private const val MAGNET_PULL = 0.25
    private const val CANARY_RANGE = 8.0
    private const val HEAD_CHANCE = 0.05
    private const val MAGNET_MAX = 32
    private val hurt = HashMap<UUID, Int>()
    private val warned = HashMap<UUID, Int>()
    private val heads = mapOf<EntityType<*>, Item>(EntityType.ZOMBIE to Items.ZOMBIE_HEAD, EntityType.SKELETON to Items.SKELETON_SKULL, EntityType.CREEPER to Items.CREEPER_HEAD, EntityType.PIGLIN to Items.PIGLIN_HEAD)
    private val fish = mapOf<Item, EntityType<*>>(Items.COD to EntityType.COD, Items.SALMON to EntityType.SALMON, Items.TROPICAL_FISH to EntityType.TROPICAL_FISH, Items.PUFFERFISH to EntityType.PUFFERFISH)

    private class Pocket(id: Int, inv: Inventory, access: ContainerLevelAccess) : CraftingMenu(id, inv, access) {
        override fun stillValid(player: Player) = true
    }

    private fun on(p: ServerPlayer, key: String) = !SkillServer.staff(p) && SkillServer.value(p, key) > 0

    fun reset() {
        hurt.clear()
        warned.clear()
    }

    fun forget(p: ServerPlayer) {
        hurt.remove(p.uuid)
        warned.remove(p.uuid)
    }

    private fun recent(p: ServerPlayer, now: Int) = hurt[p.uuid]?.let { now - it < COMBAT_TICKS } == true

    private fun open(p: ServerPlayer, stack: ItemStack) = when {
        stack.`is`(Items.CRAFTING_TABLE) && on(p, Effects.POCKET_CRAFT) -> { pocket(p); true }
        stack.`is`(Items.ENDER_CHEST) && on(p, Effects.ENDER_SATCHEL) -> { satchel(p); true }
        else -> false
    }

    fun register() {
        FORGE_BUS.addListener<PlayerInteractEvent.RightClickItem> { e ->
            val p = e.entity as? ServerPlayer ?: return@addListener
            if (open(p, e.itemStack)) {
                e.cancellationResult = InteractionResult.SUCCESS
                e.isCanceled = true
            }
        }
        FORGE_BUS.addListener<PlayerInteractEvent.RightClickBlock> { e ->
            val p = e.entity as? ServerPlayer ?: return@addListener
            if (p.isShiftKeyDown && open(p, e.itemStack)) {
                e.cancellationResult = InteractionResult.SUCCESS
                e.isCanceled = true
            }
        }
        FORGE_BUS.addListener<LivingDamageEvent.Post> { e -> (e.entity as? ServerPlayer)?.let { hurt[it.uuid] = it.server.tickCount } }
        FORGE_BUS.addListener<PlayerSpawnPhantomsEvent> { e -> (e.entity as? ServerPlayer)?.let { if (on(it, Effects.SLEEPLESS)) e.result = PlayerSpawnPhantomsEvent.Result.DENY } }
        FORGE_BUS.addListener<LivingChangeTargetEvent>(::target)
        FORGE_BUS.addListener<LivingFallEvent>(::fall)
        FORGE_BUS.addListener<PlayerInteractEvent.EntityInteract>(::whisper)
        FORGE_BUS.addListener<LivingDropsEvent>(::headhunt)
        FORGE_BUS.addListener<ServerTickEvent.Post>(::tick)
    }

    private fun pocket(p: ServerPlayer) {
        SkillServer.used(p, Effects.POCKET_CRAFT)
        p.openMenu(SimpleMenuProvider(MenuConstructor { id, inv, pl -> Pocket(id, inv, ContainerLevelAccess.create(pl.level(), pl.blockPosition())) }, Component.translatable("container.crafting")))
    }

    private fun satchel(p: ServerPlayer) {
        if (recent(p, p.server.tickCount)) return p.displayClientMessage(Component.translatable("kami_essentials.skill.satchel_combat"), true)
        SkillServer.used(p, Effects.ENDER_SATCHEL)
        val chest = p.enderChestInventory
        p.openMenu(SimpleMenuProvider(MenuConstructor { id, inv, _ -> chest.setActiveChest(null); ChestMenu.threeRows(id, inv, chest) }, Component.translatable("container.enderchest")))
    }

    private fun target(e: LivingChangeTargetEvent) {
        val p = e.newAboutToBeSetTarget as? ServerPlayer ?: return
        val mob = e.entity
        if ((mob is Bee && on(p, Effects.BEE_FRIEND)) || (mob is Drowned && mob.lastHurtByMob !== p && on(p, Effects.DROWNED_TRUCE))) e.isCanceled = true
    }

    private fun fall(e: LivingFallEvent) {
        val who = e.entity
        val rider = if (who is AbstractHorse) who.controllingPassenger as? ServerPlayer else null
        when {
            who is ServerPlayer -> if (on(who, Effects.LEAF_LANDING) && who.level().getBlockState(who.onPos).`is`(BlockTags.LEAVES)) e.isCanceled = true
            rider != null -> if (on(rider, Effects.HORSE_WHISPERER)) e.isCanceled = true
        }
    }

    private fun whisper(e: PlayerInteractEvent.EntityInteract) {
        val p = e.entity as? ServerPlayer ?: return
        val horse = e.target as? AbstractHorse ?: return
        if (e.hand != InteractionHand.MAIN_HAND || !p.mainHandItem.isEmpty || p.isSecondaryUseActive || horse.isTamed || horse is Llama || horse is SkeletonHorse || horse is ZombieHorse || !on(p, Effects.HORSE_WHISPERER)) return
        horse.tameWithName(p)
        SkillServer.used(p, Effects.HORSE_WHISPERER)
        e.cancellationResult = InteractionResult.SUCCESS
        e.isCanceled = true
    }

    fun bucket(p: ServerPlayer, drops: MutableList<ItemStack>) {
        val off = p.offhandItem
        if (!off.`is`(Items.BUCKET) || !on(p, Effects.BUCKET_FISHER)) return
        drops.forEach { drop ->
            val type = fish[drop.item] ?: return@forEach
            repeat(min(drop.count, off.count)) {
                val bucket = bucket(p, type) ?: return@forEach
                drop.shrink(1)
                off.shrink(1)
                if (!p.addItem(bucket)) p.drop(bucket, false)
                SkillServer.used(p, Effects.BUCKET_FISHER)
            }
        }
        drops.removeIf { it.isEmpty }
    }

    private fun bucket(p: ServerPlayer, type: EntityType<*>): ItemStack? {
        val level = p.serverLevel()
        val mob = type.create(level) as? Mob ?: return null
        val pail = mob as? Bucketable ?: return null
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(p.blockPosition()), MobSpawnType.BUCKET, null)
        pail.setFromBucket(true)
        return pail.bucketItemStack.also { pail.saveToBucketTag(it) }
    }

    private fun headhunt(e: LivingDropsEvent) {
        val p = e.source.entity as? ServerPlayer ?: return
        val mob = e.entity as? Mob ?: return
        val head = heads[mob.type] ?: return
        if (mob.spawnType !in SkillEvents.spawned || e.drops.any { it.item.`is`(head) } || !on(p, Effects.HEADHUNTER) || p.random.nextDouble() >= HEAD_CHANCE) return
        e.drops.add(ItemEntity(mob.level(), mob.x, mob.y, mob.z, ItemStack(head)))
        SkillServer.used(p, Effects.HEADHUNTER)
    }

    private fun tick(e: ServerTickEvent.Post) {
        val now = e.server.tickCount
        if (now % 10 != 0) return
        e.server.playerList.players.forEach { p ->
            magnet(p)
            if (now % 20 == 0) {
                canary(p, now)
                if (now % 80 == 0) hug(p, now)
            }
        }
    }

    private fun magnet(p: ServerPlayer) {
        if (!on(p, Effects.MAGNET)) return
        val to = p.position().add(0.0, 0.5, 0.0)
        val dim = p.level().dimension().location().toString()
        p.level().getEntitiesOfClass(ItemEntity::class.java, p.boundingBox.inflate(MAGNET_RANGE)) { i ->
            !i.hasPickUpDelay() && (i.target == null || i.target == p.uuid) && (i.owner == null || i.owner === p) && ClaimsApi.canBuild(p.uuid, dim, i.blockX, i.blockY, i.blockZ)
        }.take(MAGNET_MAX).forEach { i ->
            val d = to.subtract(i.position())
            if (d.lengthSqr() > 0.25) i.deltaMovement = i.deltaMovement.add(d.normalize().scale(MAGNET_PULL))
        }
    }

    private fun canary(p: ServerPlayer, now: Int) {
        if (!on(p, Effects.CANARY) || !SkillEvents.underground(p)) return
        if (warned[p.uuid]?.let { now - it < WARN_TICKS } == true) return
        if (p.level().getEntitiesOfClass(Creeper::class.java, p.boundingBox.inflate(CANARY_RANGE)) { it.isAlive }.isEmpty()) return
        warned[p.uuid] = now
        p.displayClientMessage(Component.translatable("kami_essentials.skill.canary_warn"), true)
        p.playNotifySound(SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 1f, 1f)
    }

    private fun hug(p: ServerPlayer, now: Int) {
        if (!p.isShiftKeyDown || p.health >= p.maxHealth || !on(p, Effects.TREEHUGGER) || recent(p, now)) return
        if (p.tickCount - p.lastHurtMobTimestamp < COMBAT_TICKS || p.tickCount - p.lastHurtByMobTimestamp < COMBAT_TICKS) return
        val level = p.level()
        val base = p.blockPosition()
        if (BlockPos.betweenClosed(base.offset(-1, 0, -1), base.offset(1, 1, 1)).any { SkillEvents.log(level.getBlockState(it)) }) p.heal(1f)
    }
}
