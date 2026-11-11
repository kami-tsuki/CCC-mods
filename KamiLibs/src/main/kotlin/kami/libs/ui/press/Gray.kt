package kami.libs.ui.press

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.platform.Lighting
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.blaze3d.vertex.VertexSorting
import kami.libs.KamiLibs
import kami.libs.press.Dye
import kami.libs.ui.core.Rect
import kami.libs.util.toUuidOrNull
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.client.event.RegisterShadersEvent
import org.joml.Matrix4f
import org.lwjgl.opengl.GL11
import java.util.UUID
import kotlin.math.max

object Gray {
    private const val CACHE_LIMIT = 96
    private const val FALLBACK = 0xFF707070.toInt()
    private const val SEPIA_G = 0.9f
    private const val SEPIA_B = 0.72f

    @Volatile
    private var shader: ShaderInstance? = null
    private var shaderWarned = false
    private val broken = HashSet<String>()
    private val icons = object : LinkedHashMap<String, RenderTarget>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RenderTarget>): Boolean {
            val drop = size > CACHE_LIMIT
            if (drop) runCatching { eldest.value.destroyBuffers() }
            return drop
        }
    }

    fun register(e: RegisterShadersEvent) {
        runCatching {
            e.registerShader(
                ShaderInstance(e.resourceProvider, ResourceLocation.fromNamespaceAndPath(KamiLibs.ID, "kami_gray"), DefaultVertexFormat.POSITION_TEX_COLOR)
            ) { shader = it }
        }.onFailure { warnShader(it) }
    }

    fun reset() {
        icons.values.forEach { runCatching { it.destroyBuffers() } }
        icons.clear()
        broken.clear()
    }

    private fun warnShader(t: Throwable) {
        if (shaderWarned) return
        shaderWarned = true
        KamiLibs.LOG.warn("Greyscale shader unavailable, using a dark tint instead: {}", t.message)
    }

    fun luma(rgb: Int) = ((rgb shr 16 and 0xFF) * 299 + (rgb shr 8 and 0xFF) * 587 + (rgb and 0xFF) * 114) / 1000

    fun tone(argb: Int, dye: Dye? = null): Int {
        val l = luma(argb)
        val alpha = argb and 0xFF000000.toInt()
        if (dye == null || dye.free) return alpha or (l shl 16) or (l shl 8) or l
        val c = PressColors.rgb(dye)
        val m = max(1, max(c shr 16 and 0xFF, max(c shr 8 and 0xFF, c and 0xFF)))
        fun ch(shift: Int) = (l * (c shr shift and 0xFF) / m).coerceAtMost(255)
        return alpha or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun painted(dye: Dye?) = dye != null && !dye.free

    private fun quad(g: GuiGraphics, r: Rect, u0: Float, v0: Float, u1: Float, v1: Float, dye: Dye?, sepia: Float, bind: () -> Unit) {
        if (r.isEmpty) return
        var color = -1
        runCatching {
            bind()
            val sh = shader
            if (sh != null) {
                RenderSystem.setShader { sh }
                val tint = if (painted(dye)) PressColors.rgb(dye!!).let { c ->
                    val m = max(1, max(c shr 16 and 0xFF, max(c shr 8 and 0xFF, c and 0xFF))).toFloat()
                    floatArrayOf((c shr 16 and 0xFF) / m, (c shr 8 and 0xFF) / m, (c and 0xFF) / m, 1f)
                } else floatArrayOf(1f, SEPIA_G, SEPIA_B, sepia.coerceIn(0f, 1f))
                sh.getUniform("Tint")?.set(tint[0], tint[1], tint[2], tint[3])
            } else {
                warnShader(IllegalStateException("not loaded"))
                RenderSystem.setShader(GameRenderer::getPositionTexColorShader)
                color = if (painted(dye)) 0xFF000000.toInt() or PressColors.rgb(dye!!) else FALLBACK
            }
        }.onFailure { warnShader(it); return }
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        val m = g.pose().last().pose()
        val b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR)
        b.addVertex(m, r.x.toFloat(), r.y.toFloat(), 0f).setUv(u0, v0).setColor(color)
        b.addVertex(m, r.x.toFloat(), r.bottom.toFloat(), 0f).setUv(u0, v1).setColor(color)
        b.addVertex(m, r.right.toFloat(), r.bottom.toFloat(), 0f).setUv(u1, v1).setColor(color)
        b.addVertex(m, r.right.toFloat(), r.y.toFloat(), 0f).setUv(u1, v0).setColor(color)
        BufferUploader.drawWithShader(b.buildOrThrow())
    }

    fun blit(
        g: GuiGraphics, texture: ResourceLocation, r: Rect, u: Float, v: Float, uw: Float, vh: Float, texW: Int, texH: Int,
        dye: Dye? = null, sepia: Float = 0f
    ) = quad(g, r, u / texW, v / texH, (u + uw) / texW, (v + vh) / texH, dye, sepia) { RenderSystem.setShaderTexture(0, texture) }

    fun sprite(g: GuiGraphics, id: ResourceLocation, r: Rect, dye: Dye? = null, sepia: Float = 0f) {
        val sprite = Minecraft.getInstance()?.guiSprites?.getSprite(id) ?: return
        quad(g, r, sprite.u0, sprite.v0, sprite.u1, sprite.v1, dye, sepia) { RenderSystem.setShaderTexture(0, sprite.atlasLocation()) }
    }

    fun head(g: GuiGraphics, id: String, r: Rect, dye: Dye? = null, sepia: Float = 0f) {
        val uuid = id.toUuidOrNull()
        val mc = Minecraft.getInstance() ?: return
        val skin = uuid?.let { mc.connection?.getPlayerInfo(it)?.skin } ?: DefaultPlayerSkin.get(uuid ?: UUID(0, 0))
        val texture = skin.texture()
        blit(g, texture, r, 8f, 8f, 8f, 8f, 64, 64, dye, sepia)
        blit(g, texture, r, 40f, 8f, 8f, 8f, 64, 64, dye, sepia)
    }

    fun item(g: GuiGraphics, stack: ItemStack, r: Rect, dye: Dye? = null, sepia: Float = 0f) {
        if (stack.isEmpty || r.isEmpty) return
        val mc = Minecraft.getInstance() ?: return
        val scale = (mc.window?.guiScale?.toInt() ?: 1).coerceAtLeast(1)
        val px = ((max(r.w, r.h) * scale + 15) / 16 * 16).coerceIn(16, 64)
        val key = "${BuiltInRegistries.ITEM.getKey(stack.item)}@$px"
        val target = if (key in broken) null else icons[key] ?: runCatching { renderIcon(g, mc, stack, px) }.onFailure {
            broken += key
            KamiLibs.LOG.warn("Could not render item icon {} offscreen, drawing it directly: {}", key, it.message)
        }.getOrNull()?.also { icons[key] = it }
        if (target == null) return direct(g, stack, r)
        quad(g, r, 0f, 1f, 1f, 0f, dye, sepia) { RenderSystem.setShaderTexture(0, target.colorTextureId) }
    }

    private fun direct(g: GuiGraphics, stack: ItemStack, r: Rect) {
        val s = minOf(r.w, r.h).coerceAtLeast(1)
        g.pose().pushPose()
        g.pose().translate(r.x + (r.w - s) / 2f, r.y + (r.h - s) / 2f, 0f)
        g.pose().scale(s / 16f, s / 16f, 1f)
        g.renderItem(stack, 0, 0)
        g.pose().popPose()
    }

    private fun renderIcon(g: GuiGraphics, mc: Minecraft, stack: ItemStack, px: Int): RenderTarget {
        g.flush()
        val target = TextureTarget(px, px, true, Minecraft.ON_OSX)
        target.setClearColor(0f, 0f, 0f, 0f)
        target.setFilterMode(GL11.GL_LINEAR)
        val scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)
        val box = IntArray(4)
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, box)
        GlStateManager._disableScissorTest()
        RenderSystem.backupProjectionMatrix()
        val view = RenderSystem.getModelViewStack()
        view.pushMatrix()
        try {
            target.clear(Minecraft.ON_OSX)
            target.bindWrite(true)
            RenderSystem.setProjectionMatrix(Matrix4f().setOrtho(0f, 16f, 16f, 0f, 1000f, 21000f), VertexSorting.ORTHOGRAPHIC_Z)
            view.translation(0f, 0f, -11000f)
            RenderSystem.applyModelViewMatrix()
            Lighting.setupFor3DItems()
            val own = GuiGraphics(mc, mc.renderBuffers().bufferSource())
            own.renderItem(stack, 0, 0)
            own.flush()
        } catch (t: Throwable) {
            runCatching { target.destroyBuffers() }
            throw t
        } finally {
            view.popMatrix()
            RenderSystem.applyModelViewMatrix()
            RenderSystem.restoreProjectionMatrix()
            mc.mainRenderTarget.bindWrite(true)
            if (scissor) {
                GlStateManager._enableScissorTest()
                GlStateManager._scissorBox(box[0], box[1], box[2], box[3])
            }
        }
        return target
    }
}
