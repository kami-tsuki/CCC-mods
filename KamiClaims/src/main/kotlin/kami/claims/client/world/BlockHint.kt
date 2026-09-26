package kami.claims.client.world

import com.mojang.blaze3d.platform.InputConstants
import kami.claims.client.ClientClaims
import kami.claims.net.Denied
import kami.libs.ui.style.Glyphs
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.neoforged.neoforge.client.event.RenderHighlightEvent
import org.lwjgl.glfw.GLFW

enum class Hint(val rgb: Int) { BLOCKED(0xF2555A), ACROSS(0xF5A524) }

object BlockHint {
    private var deniedPos: BlockPos? = null
    private var deniedUntil = 0L

    private fun dim() = Minecraft.getInstance().level?.dimension()?.location()?.toString() ?: ""

    fun target(): BlockPos? {
        val hit = Minecraft.getInstance().hitResult as? BlockHitResult ?: return null
        return if (hit.type == HitResult.Type.BLOCK) hit.blockPos else null
    }

    fun hint(pos: BlockPos): Hint? {
        val player = Minecraft.getInstance().player ?: return null
        val dim = dim()
        if (!ClientClaims.active(dim)) return null
        if (pos == deniedPos && System.currentTimeMillis() < deniedUntil) return Hint.BLOCKED
        val holding = !player.mainHandItem.isEmpty
        val canBreak = AccessGuess.allowed(dim, pos.x, pos.z, "break")
        val here = AccessGuess.owner(dim, player.blockX shr 4, player.blockZ shr 4)
        val there = AccessGuess.owner(dim, pos.x shr 4, pos.z shr 4)
        return when {
            !canBreak && (holding || here != there) -> Hint.BLOCKED
            here != there -> Hint.ACROSS
            else -> null
        }
    }

    fun showDetails() = InputConstants.isKeyDown(Minecraft.getInstance().window.window, GLFW.GLFW_KEY_LEFT_ALT)

    fun highlight(e: RenderHighlightEvent.Block) {
        if (!ClientClaims.prefs.blockHints) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val player = mc.player ?: return
        val pos = e.target.blockPos
        val hint = hint(pos) ?: return
        e.isCanceled = true
        val shape = level.getBlockState(pos).getShape(level, pos, CollisionContext.of(player))
        val box = if (shape.isEmpty) AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0) else shape.bounds()
        val cam = e.camera.position
        val pose = e.poseStack
        pose.pushPose()
        pose.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z)
        val r = ((hint.rgb shr 16) and 255) / 255f
        val g = ((hint.rgb shr 8) and 255) / 255f
        val b = (hint.rgb and 255) / 255f
        LevelRenderer.renderLineBox(pose, e.multiBufferSource.getBuffer(RenderType.lines()), box.inflate(0.003), r, g, b, 0.9f)
        pose.popPose()
    }

    fun denied(d: Denied) {
        val mc = Minecraft.getInstance()
        deniedPos = BlockPos(d.x, d.y, d.z)
        deniedUntil = System.currentTimeMillis() + 1200
        Borders.pulse(d.x, d.z)
        val msg = Component.empty()
            .append(Glyphs.component(Glyphs.Glyph.CROSS).withColor(0xF2555A))
            .append(Component.literal(" ${d.reason}").withColor(0xF4A3A3))
        if (d.borderDistance > 0) msg.append(Component.literal("  ·  ${d.borderDistance} block${if (d.borderDistance == 1) "" else "s"} past your border").withColor(0xF5A524))
        mc.gui.setOverlayMessage(msg, false)
        mc.player?.playSound(SoundEvents.NOTE_BLOCK_BASS.value(), 0.4f, 0.6f)
        val prefs = ClientClaims.prefs
        if (prefs.denialTips < 3) {
            prefs.denialTips++
            ClientClaims.savePrefs()
            mc.player?.displayClientMessage(Component.literal("Tip: borders show up in red when something is blocked. Press B to always show them, or K to open your country screen.").withColor(0xAEB5C1), false)
        }
    }
}
