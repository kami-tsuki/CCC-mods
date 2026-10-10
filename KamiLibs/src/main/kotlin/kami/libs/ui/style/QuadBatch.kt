package kami.libs.ui.style

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.resources.ResourceLocation
import org.joml.Matrix4f
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class AtlasRegion(val u0: Float, val v0: Float, val u1: Float, val v1: Float) {
    val uc get() = (u0 + u1) / 2
    val vc get() = (v0 + v1) / 2

    companion object {
        fun cell(col: Int, row: Int, cell: Int, size: Int) = AtlasRegion(col * cell / size.toFloat(), row * cell / size.toFloat(), (col + 1) * cell / size.toFloat(), (row + 1) * cell / size.toFloat())

        fun gui(id: ResourceLocation): AtlasRegion = Minecraft.getInstance().guiSprites.getSprite(id).let { AtlasRegion(it.u0, it.v0, it.u1, it.v1) }

        val GUI_ATLAS: ResourceLocation = ResourceLocation.withDefaultNamespace("textures/atlas/gui.png")
    }
}

class QuadBatch(private val texture: ResourceLocation, private val smooth: Boolean = false, private val mips: Int = 0) {
    private var buffer: BufferBuilder? = null
    private var pose = Matrix4f()
    private var ready = false
    private val line2x = FloatArray(2)
    private val line2y = FloatArray(2)
    private var nx = FloatArray(0)
    private var ny = FloatArray(0)
    var px = 1f
        private set

    fun begin(g: GuiGraphics) {
        g.flush()
        pose = g.pose().last().pose()
        px = 1f / (pose.m00() * Minecraft.getInstance().window.guiScale.toFloat())
        if (!ready) {
            ready = true
            val tm = Minecraft.getInstance().textureManager
            if (mips > 0) tm.register(texture, MipTexture(texture, mips)) else if (smooth) tm.getTexture(texture).setFilter(true, false)
        }
        buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR)
    }

    private fun v(b: BufferBuilder, x: Float, y: Float, u: Float, w: Float, color: Int) {
        b.addVertex(pose, x, y, 0f).setUv(u, w).setColor(color)
    }

    fun rect(x0: Float, y0: Float, x1: Float, y1: Float, r: AtlasRegion, color: Int) = quad(x0, y0, x1, y1, r, color, color, color, color)

    fun quad(x0: Float, y0: Float, x1: Float, y1: Float, r: AtlasRegion, c00: Int, c01: Int, c11: Int, c10: Int) {
        val b = buffer ?: return
        v(b, x0, y0, r.u0, r.v0, c00)
        v(b, x0, y1, r.u0, r.v1, c01)
        v(b, x1, y1, r.u1, r.v1, c11)
        v(b, x1, y0, r.u1, r.v0, c10)
    }

    fun sprite(r: AtlasRegion, cx: Float, cy: Float, size: Float, color: Int) {
        val h = size / 2
        rect(cx - h, cy - h, cx + h, cy + h, r, color)
    }

    fun line(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, solid: AtlasRegion, color: Int) {
        if (buffer == null) return
        val xs = line2x
        val ys = line2y
        xs[0] = x0
        xs[1] = x1
        ys[0] = y0
        ys[1] = y1
        path(xs, ys, 2, width, solid, color)
    }

    fun path(xs: FloatArray, ys: FloatArray, n: Int, width: Float, solid: AtlasRegion, color: Int) {
        val b = buffer ?: return
        if (n < 2) return
        if (nx.size < n) { nx = FloatArray(n); ny = FloatArray(n) }
        for (i in 0 until n) {
            val dx = xs[minOf(i + 1, n - 1)] - xs[maxOf(i - 1, 0)]
            val dy = ys[minOf(i + 1, n - 1)] - ys[maxOf(i - 1, 0)]
            val len = hypot(dx, dy)
            nx[i] = if (len < 1e-6f) 0f else -dy / len
            ny[i] = if (len < 1e-6f) 0f else dx / len
        }
        val core = maxOf(0f, (width - px) / 2)
        val edge = (width + px) / 2
        val clear = color and 0xFFFFFF
        val u = solid.uc
        val w = solid.vc
        for (i in 0 until n - 1) {
            val j = i + 1
            band(b, xs, ys, i, j, edge, core, clear, color, u, w)
            band(b, xs, ys, i, j, core, -core, color, color, u, w)
            band(b, xs, ys, i, j, -core, -edge, color, clear, u, w)
        }
    }

    private fun band(b: BufferBuilder, xs: FloatArray, ys: FloatArray, i: Int, j: Int, o0: Float, o1: Float, c0: Int, c1: Int, u: Float, w: Float) {
        v(b, xs[i] + nx[i] * o0, ys[i] + ny[i] * o0, u, w, c0)
        v(b, xs[i] + nx[i] * o1, ys[i] + ny[i] * o1, u, w, c1)
        v(b, xs[j] + nx[j] * o1, ys[j] + ny[j] * o1, u, w, c1)
        v(b, xs[j] + nx[j] * o0, ys[j] + ny[j] * o0, u, w, c0)
    }

    fun wedge(cx: Float, cy: Float, r0: Float, r1: Float, a0: Double, a1: Double, steps: Int, solid: AtlasRegion, inner: Int, outer: Int) {
        val b = buffer ?: return
        for (i in 0 until steps) {
            val p = a0 + (a1 - a0) * i / steps
            val q = a0 + (a1 - a0) * (i + 1) / steps
            v(b, cx + (cos(p) * r0).toFloat(), cy + (sin(p) * r0).toFloat(), solid.uc, solid.vc, inner)
            v(b, cx + (cos(p) * r1).toFloat(), cy + (sin(p) * r1).toFloat(), solid.uc, solid.vc, outer)
            v(b, cx + (cos(q) * r1).toFloat(), cy + (sin(q) * r1).toFloat(), solid.uc, solid.vc, outer)
            v(b, cx + (cos(q) * r0).toFloat(), cy + (sin(q) * r0).toFloat(), solid.uc, solid.vc, inner)
        }
    }

    fun end() {
        val b = buffer ?: return
        buffer = null
        val mesh = b.build() ?: return
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader)
        RenderSystem.setShaderTexture(0, texture)
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableCull()
        BufferUploader.drawWithShader(mesh)
        RenderSystem.enableCull()
    }
}
