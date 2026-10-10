package kami.essentials.skills

import kami.essentials.KamiEssentials
import net.minecraft.core.UUIDUtil
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.ItemTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.monster.Ravager
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.attachment.AttachmentType
import net.neoforged.neoforge.common.Tags
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.registries.DeferredRegister
import net.neoforged.neoforge.registries.NeoForgeRegistries
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS
import java.util.UUID
import java.util.function.Supplier

object Ravagers {
    private val NONE = UUID(0L, 0L)
    private val attachments = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, KamiEssentials.ID)
    private val tamer = attachments.register("tamer", Supplier { AttachmentType.builder(Supplier { NONE }).serialize(UUIDUtil.CODEC) { it != NONE }.sync(UUIDUtil.STREAM_CODEC).build() })

    fun owner(e: Entity): UUID? = (e as? Ravager)?.getExistingDataOrNull(tamer)?.takeIf { it != NONE }

    fun tamed(e: Entity?) = e != null && owner(e) != null

    fun rideable(e: Entity, rider: Player) = owner(e) == rider.uuid && SkillSide.value(rider, Effects.RAVAGER_TAMER) > 0

    fun register() {
        attachments.register(MOD_BUS)
        FORGE_BUS.addListener<PlayerInteractEvent.EntityInteract>(::interact)
        FORGE_BUS.addListener<LivingChangeTargetEvent> { e -> if (tamed(e.newAboutToBeSetTarget) || e.newAboutToBeSetTarget != null && tamed(e.entity)) e.isCanceled = true }
        FORGE_BUS.addListener<EntityMobGriefingEvent> { e -> if (tamed(e.entity)) e.setCanGrief(false) }
        FORGE_BUS.addListener<LivingIncomingDamageEvent> { e ->
            val source = e.source.entity
            if (source != null && tamed(source) && owner(source) == e.entity.uuid) e.isCanceled = true
        }
    }

    private fun meat(s: ItemStack) = s.`is`(ItemTags.MEAT) || s.`is`(Tags.Items.FOODS_RAW_MEAT) || s.`is`(Tags.Items.FOODS_COOKED_MEAT)

    private fun interact(e: PlayerInteractEvent.EntityInteract) {
        val r = e.target as? Ravager ?: return
        if (e.hand != InteractionHand.MAIN_HAND) return
        val p = e.entity
        val client = p.level().isClientSide
        if (SkillSide.value(p, Effects.RAVAGER_TAMER) <= 0) return
        val stack = e.itemStack
        if (meat(stack)) {
            e.cancellationResult = InteractionResult.sidedSuccess(client)
            e.isCanceled = true
            if (!client && !tamed(r)) feed(p as ServerPlayer, r, stack)
        } else if (stack.isEmpty && owner(r) == p.uuid) {
            e.cancellationResult = InteractionResult.sidedSuccess(client)
            e.isCanceled = true
            if (!client && !r.isVehicle && !p.isPassenger) {
                p.yRot = r.yRot
                p.xRot = r.xRot
                p.startRiding(r)
            }
        }
    }

    private fun feed(p: ServerPlayer, r: Ravager, stack: ItemStack) {
        if (!p.abilities.instabuild) stack.shrink(1)
        if (r.random.nextInt(3) != 0) {
            (r.level() as ServerLevel).sendParticles(ParticleTypes.SMOKE, r.x, r.eyeY, r.z, 5, 0.4, 0.4, 0.4, 0.0)
            return
        }
        r.target = null
        r.setCanJoinRaid(false)
        r.currentRaid?.removeFromRaid(r, true)
        r.currentRaid = null
        r.setPersistenceRequired()
        r.setData(tamer, p.uuid)
        (r.level() as ServerLevel).sendParticles(ParticleTypes.HEART, r.x, r.eyeY, r.z, 7, 0.5, 0.5, 0.5, 0.0)
    }
}
