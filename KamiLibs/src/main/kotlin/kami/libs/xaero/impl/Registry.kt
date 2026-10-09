package kami.libs.xaero.impl

import kami.libs.xaero.InfoLine
import kami.libs.xaero.LineSource
import kami.libs.xaero.MapChoice
import kami.libs.xaero.MapPoint
import kami.libs.xaero.MapToggle
import kami.libs.xaero.PointSource
import kami.libs.xaero.PositionActions
import kami.libs.xaero.TrackedSource
import kami.libs.xaero.ChunkLayer
import net.minecraft.client.Minecraft
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private val LOG = org.slf4j.LoggerFactory.getLogger("KamiLibs")

object Registry {
    val waypoints = CopyOnWriteArrayList<PointSource>()
    val markers = CopyOnWriteArrayList<PointSource>()
    val layers = CopyOnWriteArrayList<ChunkLayer>()
    val infos = CopyOnWriteArrayList<InfoLine>()
    val tracked = CopyOnWriteArrayList<TrackedSource>()
    val lines = CopyOnWriteArrayList<LineSource>()
    val actions = CopyOnWriteArrayList<PositionActions>()
    val fairPlay = CopyOnWriteArrayList<() -> Boolean>()
    val radar = CopyOnWriteArrayList<(UUID) -> Boolean>()

    @Volatile
    private var toggleMap: Map<String, MapToggle> = emptyMap()

    @Volatile
    private var choiceMap: Map<String, MapChoice> = emptyMap()
    private val unknown = ConcurrentHashMap.newKeySet<String>()
    private val dirty = AtomicBoolean(true)
    private val revision = AtomicInteger()

    @Volatile
    var caveHooked = false

    @Volatile
    var temp: MapPoint? = null
        private set
    private val tempRev = AtomicInteger()

    private val tempSource = object : PointSource {
        override val id = "temp"
        override val toggle = ""
        override fun rev() = tempRev.get()
        override fun points(dim: String) = temp?.takeIf { it.dim == dim }?.let(::listOf).orEmpty()
    }

    init {
        waypoints += tempSource
        markers += tempSource
    }

    val rev: Int get() = revision.get()

    @Synchronized
    fun putToggle(t: MapToggle) {
        toggleMap = LinkedHashMap(toggleMap).also { it[t.id] = t }
        refresh()
    }

    @Synchronized
    fun putChoice(c: MapChoice) {
        choiceMap = LinkedHashMap(choiceMap).also { it[c.id] = c }
        refresh()
    }

    fun toggles(): List<MapToggle> = toggleMap.values.toList()

    fun choices(): List<MapChoice> = choiceMap.values.toList()

    private fun warnUnknown(kind: String, id: String) {
        if (unknown.add("$kind:$id")) LOG.warn("Unknown map $kind id '{}'", id)
    }

    fun on(id: String): Boolean {
        if (id.isEmpty()) return true
        val t = toggleMap[id] ?: return false.also { warnUnknown("toggle", id) }
        return MapPrefs.get(id)?.toBooleanStrictOrNull() ?: t.default
    }

    fun choice(id: String): String {
        val c = choiceMap[id] ?: return "".also { warnUnknown("choice", id) }
        return MapPrefs.get(id)?.takeIf { v -> c.options.any { it.first == v } } ?: c.default
    }

    fun setToggle(id: String, value: Boolean) {
        MapPrefs.set(id, value.toString())
        refresh()
    }

    fun setChoice(id: String, value: String) {
        MapPrefs.set(id, value)
        refresh()
    }

    fun temp(p: MapPoint) {
        temp = p
        tempRev.incrementAndGet()
        refresh()
    }

    fun clearTemp() {
        if (temp != null) {
            temp = null
            tempRev.incrementAndGet()
            refresh()
        }
    }

    fun refresh() {
        revision.incrementAndGet()
        dirty.set(true)
    }

    fun take() = dirty.getAndSet(false)

    fun fairPlayActive(): Boolean {
        for (f in fairPlay) if (runCatching(f).getOrDefault(false)) return true
        return false
    }

    fun radarHidden(id: UUID): Boolean {
        for (f in radar) if (runCatching { f(id) }.getOrDefault(false)) return true
        return false
    }

    @JvmStatic
    fun caveBlocked(): Boolean {
        caveHooked = true
        return fairPlayActive()
    }

    fun dimension(): String? = Minecraft.getInstance().level?.dimension()?.location()?.toString()
}
