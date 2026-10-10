package kami.essentials.client

import kami.essentials.skills.Effects
import kami.essentials.skills.SkillClient
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Items
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS

object BonusClient {
    private const val BOOST = 1.4
    private const val MAX_CLIMB = 0.3

    fun register() {
        FORGE_BUS.addListener<ClientTickEvent.Post> { tick() }
        FORGE_BUS.addListener<PlayerInteractEvent.RightClickBlock> { e ->
            val p = e.entity as? LocalPlayer ?: return@addListener
            val item = e.itemStack.item
            if (p.isShiftKeyDown && (item == Items.CRAFTING_TABLE && SkillClient.value(Effects.POCKET_CRAFT) > 0 || item == Items.ENDER_CHEST && SkillClient.value(Effects.ENDER_SATCHEL) > 0)) {
                e.cancellationResult = InteractionResult.SUCCESS
                e.isCanceled = true
            }
        }
    }

    private fun tick() {
        val mc = Minecraft.getInstance()
        val p = mc.player ?: return
        if (p.isCreative || p.isSpectator || p.isShiftKeyDown || !p.onClimbable() || SkillClient.value(Effects.LADDER_RUSH) <= 0) return
        val v = p.deltaMovement
        if (v.y > 0 && (p.horizontalCollision || mc.options.keyJump.isDown)) p.setDeltaMovement(v.x, minOf(v.y * BOOST, MAX_CLIMB), v.z)
    }
}
