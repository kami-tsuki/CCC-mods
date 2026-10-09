package kami.libs.xaero.impl

import kami.libs.xaero.ChunkLayer
import kami.libs.xaero.MapAction
import kami.libs.xaero.MapPoint
import kami.libs.xaero.PointSource
import kami.libs.xaero.Tracked
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.texture.TextureManager
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import xaero.map.WorldMap
import xaero.map.WorldMapSession
import xaero.map.element.MapElementReader
import xaero.map.element.MapElementRenderProvider
import xaero.map.element.MapElementRenderer
import xaero.map.element.render.ElementRenderLocation
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider
import xaero.map.gui.IRightClickableElement
import xaero.map.gui.dropdown.rightclick.RightClickOption
import xaero.map.highlight.ChunkHighlighter
import xaero.map.highlight.HighlighterRegistry
import xaero.map.radar.tracker.system.IPlayerTrackerSystem
import xaero.map.radar.tracker.system.ITrackedPlayerReader
import java.util.Objects
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

private fun dimName(dim: ResourceKey<Level>) = dim.location().toString()

private fun dimKey(name: String): ResourceKey<Level> = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(name))

private class RegionKey(val dim: String, val rx: Int, val rz: Int) {
    override fun equals(other: Any?) = other is RegionKey && other.rx == rx && other.rz == rz && other.dim == dim
    override fun hashCode() = Objects.hash(dim, rx, rz)
}

private class Active(val rev: Int, val size: Int, val layers: List<ChunkLayer>)

private class HashMemo(val rev: Int) {
    val map = ConcurrentHashMap<RegionKey, Int>()
}

private class KamiHighlighter : ChunkHighlighter(true) {
    @Volatile
    private var activeCache = Active(-1, -1, emptyList())

    @Volatile
    private var memo = HashMemo(-1)

    private val guard get() = WorldMapAdapter.guard

    private fun active(): List<ChunkLayer> {
        val c = activeCache
        val rev = Registry.rev
        val size = Registry.layers.size
        if (c.rev == rev && c.size == size) return c.layers
        val list = Registry.layers.filter { Registry.on(it.toggle) }.sortedBy { it.order }
        activeCache = Active(rev, size, list)
        return list
    }

    private fun regionHash(dim: ResourceKey<Level>, rx: Int, rz: Int): Int {
        var m = memo
        val rev = Registry.rev
        if (m.rev != rev) {
            m = HashMemo(rev)
            memo = m
        }
        val key = RegionKey(dimName(dim), rx, rz)
        m.map[key]?.let { return it }
        val h = Colors.combine(active().map { it.regionHash(key.dim, rx, rz) }.toIntArray())
        if (m.map.size > 8192) m.map.clear()
        m.map[key] = h
        return h
    }

    private fun paint(dim: ResourceKey<Level>, cx: Int, cz: Int): IntArray? {
        val name = dimName(dim)
        var fill = 0
        val edges = IntArray(4)
        var any = false
        for (l in active()) {
            val p = l.paint(name, cx, cz) ?: continue
            any = true
            fill = Colors.over(fill, p.fill)
            edges[0] = Colors.over(edges[0], p.north)
            edges[1] = Colors.over(edges[1], p.east)
            edges[2] = Colors.over(edges[2], p.south)
            edges[3] = Colors.over(edges[3], p.west)
        }
        if (!any) return null
        return IntArray(5) { Colors.toRgba(if (it == 0) fill else edges[it - 1]) }
    }

    private fun tooltip(dim: ResourceKey<Level>, cx: Int, cz: Int, blunt: Boolean): Component? {
        val name = dimName(dim)
        val parts = active().asReversed().mapNotNull { it.tooltip(name, cx, cz, blunt) }
        if (parts.isEmpty()) return null
        val out = Component.empty().append(parts[0])
        for (i in 1 until parts.size) out.append(Component.literal(" | ")).append(parts[i])
        return out
    }

    override fun calculateRegionHash(dim: ResourceKey<Level>, regionX: Int, regionZ: Int): Int = guard.call(0) { regionHash(dim, regionX, regionZ) }

    override fun regionHasHighlights(dim: ResourceKey<Level>, regionX: Int, regionZ: Int) = guard.call(false) { regionHash(dim, regionX, regionZ) != 0 }

    override fun chunkIsHighlit(dim: ResourceKey<Level>, chunkX: Int, chunkZ: Int) = guard.call(false) { paint(dim, chunkX, chunkZ) != null }

    override fun getColors(dim: ResourceKey<Level>, chunkX: Int, chunkZ: Int): IntArray? = guard.call(null) { paint(dim, chunkX, chunkZ) }

    override fun getChunkHighlightSubtleTooltip(dim: ResourceKey<Level>, chunkX: Int, chunkZ: Int) = guard.call(null) { tooltip(dim, chunkX, chunkZ, false) }

    override fun getChunkHighlightBluntTooltip(dim: ResourceKey<Level>, chunkX: Int, chunkZ: Int) = guard.call(null) { tooltip(dim, chunkX, chunkZ, true) }

    override fun addMinimapBlockHighlightTooltips(list: MutableList<Component>, dim: ResourceKey<Level>, blockX: Int, blockZ: Int, width: Int) {
        guard.run {
            val name = dimName(dim)
            active().asReversed().forEach { l -> l.tooltip(name, blockX shr 4, blockZ shr 4, true)?.let(list::add) }
        }
    }
}

internal class KamiPoint(val point: MapPoint) {
    val actions: List<MapAction> = if (point.waypoint) point.actions + MapAction(Component.translatable("kami_libs.maps.waypoint")) { Registry.temp(point) } else point.actions
}

private object Ctx

private class KamiOption(private val action: MapAction, index: Int, target: IRightClickableElement) : RightClickOption(action.label.string, index, target) {
    init { active = action.enabled }

    override fun onAction(screen: Screen) {
        WorldMapAdapter.guard.run { action.run() }
    }

    override fun getDisplayName(): Component = action.label
}

private class KamiProvider : MapElementRenderProvider<KamiPoint, Ctx>() {
    private var items: List<KamiPoint> = emptyList()
    private var cursor = 0

    override fun begin(location: Int, context: Ctx) {
        items = WorldMapAdapter.points()
        cursor = 0
    }

    override fun hasNext(location: Int, context: Ctx) = cursor < items.size

    override fun getNext(location: Int, context: Ctx): KamiPoint = items[cursor++]

    override fun end(location: Int, context: Ctx) {
        items = emptyList()
    }
}

private const val HALF = 7

private class KamiReader : MapElementReader<KamiPoint, Ctx, KamiRenderer>() {
    override fun isHidden(e: KamiPoint, c: Ctx) = false
    override fun getRenderX(e: KamiPoint, c: Ctx, partial: Float) = e.point.x + 0.5
    override fun getRenderZ(e: KamiPoint, c: Ctx, partial: Float) = e.point.z + 0.5
    override fun getInteractionBoxLeft(e: KamiPoint, c: Ctx, partial: Float) = -HALF
    override fun getInteractionBoxRight(e: KamiPoint, c: Ctx, partial: Float) = HALF
    override fun getInteractionBoxTop(e: KamiPoint, c: Ctx, partial: Float) = -HALF
    override fun getInteractionBoxBottom(e: KamiPoint, c: Ctx, partial: Float) = HALF
    override fun getRenderBoxLeft(e: KamiPoint, c: Ctx, partial: Float) = -HALF
    override fun getRenderBoxRight(e: KamiPoint, c: Ctx, partial: Float) = HALF
    override fun getRenderBoxTop(e: KamiPoint, c: Ctx, partial: Float) = -HALF
    override fun getRenderBoxBottom(e: KamiPoint, c: Ctx, partial: Float) = HALF
    override fun getLeftSideLength(e: KamiPoint, mc: Minecraft) = HALF + 2
    override fun getMenuName(e: KamiPoint): String = e.point.name
    override fun getFilterName(e: KamiPoint): String = e.point.name
    override fun getMenuTextFillLeftPadding(e: KamiPoint) = 0
    override fun getRightClickTitleBackgroundColor(e: KamiPoint) = 0xFF000000.toInt() or e.point.color
    override fun shouldScaleBoxWithOptionalScale() = false
    override fun isRightClickValid(e: KamiPoint) = e.actions.isNotEmpty()

    override fun getRightClickOptions(e: KamiPoint, target: IRightClickableElement): ArrayList<RightClickOption> =
        ArrayList<RightClickOption>().also { out -> e.actions.forEach { out += KamiOption(it, out.size, target) } }
}

private class KamiRenderer(reader: KamiReader, provider: KamiProvider) :
    MapElementRenderer<KamiPoint, Ctx, KamiRenderer>(Ctx, provider, reader) {
    override fun beforeRender(location: Int, mc: Minecraft, g: GuiGraphics, cx: Double, cz: Double, mx: Double, mz: Double, brightness: Float, scale: Double, screenScale: Double, tm: TextureManager, font: Font, buffers: MultiBufferSource.BufferSource, rp: MultiTextureRenderTypeRendererProvider, shadow: Boolean) {}

    override fun afterRender(location: Int, mc: Minecraft, g: GuiGraphics, cx: Double, cz: Double, mx: Double, mz: Double, brightness: Float, scale: Double, screenScale: Double, tm: TextureManager, font: Font, buffers: MultiBufferSource.BufferSource, rp: MultiTextureRenderTypeRendererProvider, shadow: Boolean) {}

    override fun renderElementPre(location: Int, e: KamiPoint, hovered: Boolean, mc: Minecraft, g: GuiGraphics, cx: Double, cz: Double, mx: Double, mz: Double, brightness: Float, scale: Double, screenScale: Double, tm: TextureManager, font: Font, buffers: MultiBufferSource.BufferSource, rp: MultiTextureRenderTypeRendererProvider, partial: Float, depth: Double, extra: Double, cave: Boolean, tick: Float) {}

    override fun renderElement(location: Int, e: KamiPoint, hovered: Boolean, mc: Minecraft, g: GuiGraphics, cx: Double, cz: Double, mx: Double, mz: Double, brightness: Float, scale: Double, screenScale: Double, tm: TextureManager, font: Font, buffers: MultiBufferSource.BufferSource, rp: MultiTextureRenderTypeRendererProvider, order: Int, depth: Double, optionalScale: Float, partialX: Double, partialZ: Double, cave: Boolean, tick: Float): Boolean {
        val p = e.point
        val half = if (hovered) HALF + 1 else HALF
        g.fill(-half - 1, -half - 1, half + 1, half + 1, 0xFF101010.toInt())
        g.fill(-half, -half, half, half, 0xFF000000.toInt() or p.color)
        val text = p.initials.take(2)
        val lum = ((p.color shr 16 and 255) * 299 + (p.color shr 8 and 255) * 587 + (p.color and 255) * 114) / 1000
        g.drawString(font, text, -font.width(text) / 2, -4, if (lum > 140) 0xFF101010.toInt() else 0xFFFFFFFF.toInt(), false)
        if (hovered) tooltip(g, font, p)
        return hovered
    }

    private fun tooltip(g: GuiGraphics, font: Font, p: MapPoint) {
        val lines = listOf(Component.literal(p.name)) + p.tooltip
        val w = lines.maxOf { font.width(it) } + 6
        val h = lines.size * 10 + 4
        val x = HALF + 4
        g.pose().pushPose()
        g.pose().translate(0f, 0f, 400f)
        g.fill(x, -h / 2, x + w, h / 2, 0xE0100010.toInt())
        lines.forEachIndexed { i, c -> g.drawString(font, c, x + 3, -h / 2 + 3 + i * 10, 0xFFFFFFFF.toInt(), false) }
        g.pose().popPose()
    }

    override fun shouldRender(location: Int, pre: Boolean) = location == ElementRenderLocation.WORLD_MAP.index
}

private object KamiTracker : IPlayerTrackerSystem<Tracked> {
    private val reader = object : ITrackedPlayerReader<Tracked> {
        override fun getId(p: Tracked): UUID = p.id
        override fun getX(p: Tracked) = p.x
        override fun getY(p: Tracked) = p.y
        override fun getZ(p: Tracked) = p.z
        override fun getDimension(p: Tracked): ResourceKey<Level> = WorldMapAdapter.guard.call(Level.OVERWORLD) { dimKey(p.dim) }
    }

    override fun getReader() = reader

    override fun getTrackedPlayerIterator(): MutableIterator<Tracked> =
        WorldMapAdapter.guard.call(mutableListOf<Tracked>().iterator()) { Registry.tracked.flatMap { it.players() }.toMutableList().iterator() }
}

object WorldMapAdapter {
    val guard = Guard("world map")
    private var attached = false
    private val pending = mutableListOf<ChunkHighlighter>()
    private var registry: HighlighterRegistry? = null

    private class Points(val dim: ResourceKey<Level>, val rev: Int, val key: Int, val items: List<KamiPoint>)

    @Volatile
    private var snapshot: Points? = null

    @Synchronized
    private fun register(h: ChunkHighlighter) {
        pending += h
        registry?.register(h)
    }

    @JvmStatic
    @Synchronized
    fun attach(target: Any) {
        val r = target as? HighlighterRegistry ?: return
        if (registry === r) return
        registry = r
        pending.forEach(r::register)
    }

    fun clear() {
        snapshot = null
    }

    fun tick(dirty: Boolean) {
        guard.run {
            if (!attached) {
                attached = true
                register(KamiHighlighter())
                WorldMap.mapElementRenderHandler.add(KamiRenderer(KamiReader(), KamiProvider()))
                WorldMap.playerTrackerSystemManager.register("kami", KamiTracker)
            }
            if (dirty) refresh()
            currentDim()?.let(::build)
        }
    }

    fun refresh() {
        guard.run {
            WorldMapSession.getCurrentSession()?.mapProcessor?.mapWorld?.dimensionsList?.forEach { it.highlightHandler.clearCachedHashes() }
        }
    }

    private fun currentDim(): ResourceKey<Level>? = WorldMapSession.getCurrentSession()?.mapProcessor?.mapWorld?.currentDimensionId

    private fun build(dim: ResourceKey<Level>): Points {
        val name = dimName(dim)
        val sources: List<PointSource> = Registry.markers.filter { Registry.on(it.toggle) }
        val rev = Registry.rev
        val key = Objects.hash(name, rev, sources.map { it.id to it.rev() })
        val c = snapshot
        if (c != null && c.dim === dim && c.key == key) return if (c.rev == rev) c else Points(dim, rev, key, c.items).also { snapshot = it }
        return Points(dim, rev, key, sources.flatMap { s -> s.points(name).map(::KamiPoint) }).also { snapshot = it }
    }

    internal fun points(): List<KamiPoint> = guard.call(emptyList()) {
        val dim = currentDim() ?: return@call emptyList()
        val c = snapshot
        (if (c != null && c.dim === dim && c.rev == Registry.rev) c else build(dim)).items
    }

    @JvmStatic
    fun rightClick(dim: ResourceKey<Level>, x: Int, z: Int, target: IRightClickableElement, start: Int): List<RightClickOption> = guard.call(emptyList()) {
        val name = dimName(dim)
        val out = ArrayList<RightClickOption>()
        Registry.actions.forEach { p -> p.actions(name, x, z).forEach { out += KamiOption(it, start + out.size, target) } }
        out
    }
}
