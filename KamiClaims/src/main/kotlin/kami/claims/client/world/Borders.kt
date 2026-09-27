package kami.claims.client.world

import kami.claims.client.BorderMode
import kami.claims.client.ClientClaims
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.network.chat.Component
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import org.joml.Vector3f
import kotlin.math.abs
import kotlin.math.max

object Borders {
    class Edge(val x0: Int, val z0: Int, val x1: Int, val z1: Int, val outside: Int, val inside: Int, val color: Int, val blocked: Boolean)

    private const val UNCLAIMED = 0x8C7A5B
    private var tick = 0
    private var lastRev = -1
    private var revChangedAt = 0L
    private var pulseUntil = 0L
    private var pulseChunk: Pair<Int, Int>? = null
    private var visibleUntil = 0L

    fun pulse(blockX: Int, blockZ: Int) {
        pulseChunk = (blockX shr 4) to (blockZ shr 4)
        pulseUntil = System.currentTimeMillis() + 3000
    }

    fun cycle() {
        val p = ClientClaims.prefs
        p.borderMode = BorderMode.entries[(p.borderMode.ordinal + 1) % BorderMode.entries.size]
        ClientClaims.savePrefs()
        val mode = p.borderMode.name.lowercase()
        Minecraft.getInstance().gui.setOverlayMessage(Component.translatable("kami_claims.border_mode.toast", Component.translatable("kami_claims.border_mode.$mode"), Component.translatable("kami_claims.border_mode.$mode.desc")), false)
    }

    private fun dim() = Minecraft.getInstance().level?.dimension()?.location()?.toString() ?: ""

    fun edges(radius: Int): List<Edge> {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return emptyList()
        val dim = dim()
        if (!ClientClaims.active(dim)) return emptyList()
        val px = player.blockX
        val pz = player.blockZ
        val here = AccessGuess.owner(dim, px shr 4, pz shr 4)
        val out = ArrayList<Edge>()
        val cr = radius / 16 + 1
        val cx = px shr 4
        val cz = pz shr 4
        for (x in cx - cr..cx + cr) for (z in cz - cr..cz + cr) {
            val a = AccessGuess.owner(dim, x, z)
            val east = AccessGuess.owner(dim, x + 1, z)
            if (a != east) out += edge(dim, (x + 1) * 16, z * 16, (x + 1) * 16, z * 16 + 16, a, east, x, z, x + 1, z, px, pz, here)
            val south = AccessGuess.owner(dim, x, z + 1)
            if (a != south) out += edge(dim, x * 16, (z + 1) * 16, x * 16 + 16, (z + 1) * 16, a, south, x, z, x, z + 1, px, pz, here)
        }
        return out.filter { distance(it, px, pz) <= radius }
    }

    private fun edge(dim: String, x0: Int, z0: Int, x1: Int, z1: Int, a: Int, b: Int, ax: Int, az: Int, bx: Int, bz: Int, px: Int, pz: Int, here: Int): Edge {
        val playerOnA = if (x0 == x1) px < x0 else pz < z0
        val far = if (playerOnA) b else a
        val near = if (playerOnA) a else b
        val farChunk = if (playerOnA) bx to bz else ax to az
        val color = if (far < 0) UNCLAIMED else ClientClaims.countries.getOrNull(far)?.color ?: UNCLAIMED
        val blocked = !AccessGuess.allowed(dim, farChunk.first * 16 + 8, farChunk.second * 16 + 8, "break")
        return Edge(x0, z0, x1, z1, far, near, color, blocked)
    }

    fun distance(e: Edge, px: Int, pz: Int): Int {
        val dx = if (e.x0 == e.x1) abs(px - e.x0) else max(0, max(e.x0 - px, px - e.x1))
        val dz = if (e.z0 == e.z1) abs(pz - e.z0) else max(0, max(e.z0 - pz, pz - e.z1))
        return max(dx, dz)
    }

    fun nearest(radius: Int = 8): Pair<Edge, Int>? {
        val player = Minecraft.getInstance().player ?: return null
        return edges(radius).map { it to distance(it, player.blockX, player.blockZ) }.minByOrNull { it.second }
    }

    private fun aimingAcross(): Boolean {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return false
        val hit = mc.hitResult as? BlockHitResult ?: return false
        if (hit.type != HitResult.Type.BLOCK || player.mainHandItem.isEmpty) return false
        val dim = dim()
        return AccessGuess.owner(dim, hit.blockPos.x shr 4, hit.blockPos.z shr 4) != AccessGuess.owner(dim, player.blockX shr 4, player.blockZ shr 4)
    }

    private fun wanted(): Int {
        val now = System.currentTimeMillis()
        if (ClientClaims.rev != lastRev) { if (lastRev >= 0) revChangedAt = now; lastRev = ClientClaims.rev }
        return when (ClientClaims.prefs.borderMode) {
            BorderMode.OFF -> 0
            BorderMode.ALWAYS, BorderMode.BUILDER -> 24
            BorderMode.AUTO -> {
                val near = (nearest(6)?.second ?: 99) <= 6
                if (near || aimingAcross() || now - revChangedAt < 10_000) visibleUntil = now + 400
                if (now < visibleUntil) 12 else 0
            }
        }
    }

    fun tick() {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val player = mc.player ?: return
        if (mc.isPaused) return
        tick++
        if (tick % 5 != 0) return
        val now = System.currentTimeMillis()
        val radius = wanted()
        val budget = ClientClaims.prefs.borderDensity / 4
        var spent = 0
        val eye = player.eyeY
        val underground = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, player.blockPosition()) == 0
        if (radius > 0 && !(ClientClaims.prefs.borderLines && ClientClaims.prefs.borderMode != BorderMode.OFF)) {
            edges(radius).sortedBy { distance(it, player.blockX, player.blockZ) }.forEach { e ->
                if (spent >= budget) return@forEach
                spent += curtain(e, eye, underground, player.blockX, player.blockZ, budget - spent, false)
            }
        }
        if (now < pulseUntil) pulseChunk?.let { (cx, cz) ->
            edges(24).filter { e -> touches(e, cx, cz) }.forEach { e -> curtain(e, eye, underground, player.blockX, player.blockZ, 120, true) }
        }
    }

    private fun touches(e: Edge, cx: Int, cz: Int): Boolean {
        val minX = cx * 16; val minZ = cz * 16
        return if (e.x0 == e.x1) (e.x0 == minX || e.x0 == minX + 16) && e.z0 == minZ
        else (e.z0 == minZ || e.z0 == minZ + 16) && e.x0 == minX
    }

    private fun curtain(e: Edge, eye: Double, underground: Boolean, px: Int, pz: Int, limit: Int, danger: Boolean): Int {
        val level = Minecraft.getInstance().level ?: return 0
        val rgb = if (danger) 0xF2555A else e.color
        val base = Vector3f(((rgb shr 16) and 255) / 255f, ((rgb shr 8) and 255) / 255f, (rgb and 255) / 255f)
        val red = DustParticleOptions(Vector3f(0.95f, 0.33f, 0.35f), 0.7f)
        val dust = DustParticleOptions(base, if (danger) 0.9f else 0.6f)
        var spent = 0
        val horizontal = e.z0 == e.z1
        val length = if (horizontal) e.x1 - e.x0 else e.z1 - e.z0
        var i = 0.0
        while (i < length && spent < limit) {
            val x = if (horizontal) e.x0 + i else e.x0.toDouble()
            val z = if (horizontal) e.z0.toDouble() else e.z0 + i
            val d = max(abs(x - px), abs(z - pz))
            val step = when {
                danger -> 0.5
                d < 4 -> 0.5
                d < 8 -> 1.0
                else -> 2.0
            }
            val ground = if (underground) eye - 3 else level.getHeight(Heightmap.Types.MOTION_BLOCKING, x.toInt(), z.toInt()).toDouble()
            val top = if (underground) eye + 3 else max(ground, eye) + 2.5
            var y = ground + 0.1
            var row = 0
            while (y <= top && spent < limit) {
                val jitter = (level.random.nextDouble() - 0.5) * 0.2
                val particle = if (!danger && e.blocked && row % 3 == 2) red else dust
                level.addParticle(particle, x + jitter, y, z + jitter, 0.0, 0.0, 0.0)
                spent++
                row++
                y += if (d < 6) 1.0 else 1.5
            }
            i += step
        }
        return spent
    }

    fun renderWalls(e: RenderLevelStageEvent) {
        if (e.stage != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return
        val prefs = ClientClaims.prefs
        if (!prefs.borderLines && prefs.borderMode != BorderMode.BUILDER) return
        val radius = wanted()
        if (radius == 0) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val cam = e.camera.position
        val pose = e.poseStack
        pose.pushPose()
        pose.translate(-cam.x, -cam.y, -cam.z)
        val buffers = mc.renderBuffers().bufferSource()
        val vc = buffers.getBuffer(RenderType.debugQuads())
        val m = pose.last().pose()
        edges(radius).forEach { edge ->
            val horizontal = edge.z0 == edge.z1
            val length = if (horizontal) edge.x1 - edge.x0 else edge.z1 - edge.z0
            val r = (edge.color shr 16) and 255
            val g = (edge.color shr 8) and 255
            val b = edge.color and 255
            for (i in 0 until length) {
                val x0 = (if (horizontal) edge.x0 + i else edge.x0).toFloat()
                val z0 = (if (horizontal) edge.z0 else edge.z0 + i).toFloat()
                val x1 = if (horizontal) x0 + 1 else x0
                val z1 = if (horizontal) z0 else z0 + 1
                val ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x0.toInt(), z0.toInt()).toFloat()
                val top = ground + 4f
                val alpha = if (edge.blocked) 0x50 else 0x30
                vc.addVertex(m, x0, ground, z0).setColor(r, g, b, alpha)
                vc.addVertex(m, x1, ground, z1).setColor(r, g, b, alpha)
                vc.addVertex(m, x1, top, z1).setColor(r, g, b, 0)
                vc.addVertex(m, x0, top, z0).setColor(r, g, b, 0)
                val line = 0.06f
                vc.addVertex(m, x0, ground, z0).setColor(r, g, b, 0xD0)
                vc.addVertex(m, x1, ground, z1).setColor(r, g, b, 0xD0)
                vc.addVertex(m, x1, ground + line, z1).setColor(r, g, b, 0xD0)
                vc.addVertex(m, x0, ground + line, z0).setColor(r, g, b, 0xD0)
            }
        }
        buffers.endBatch(RenderType.debugQuads())
        pose.popPose()
    }
}
