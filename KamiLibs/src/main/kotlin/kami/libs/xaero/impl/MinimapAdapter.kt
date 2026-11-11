package kami.libs.xaero.impl

import kami.libs.compat.Refl
import kami.libs.xaero.PointSource
import kami.libs.xaero.Tracked
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Objects
import java.util.function.Supplier

object MinimapAdapter {
    private val trackerGuard = Guard("minimap tracker")
    private val waypointGuard = Guard("minimap waypoints")
    private val fairGuard = Guard("minimap fair play")
    private val infoGuard = Guard("minimap info display")

    private class Synced(var tp: Any?, var dim: String?, var rev: Int, var sigs: Map<String, Int>)

    private class Api {
        val waypoint = Refl.cls("xaero.common.minimap.waypoints.Waypoint")
        val color = Refl.cls("xaero.hud.minimap.waypoint.WaypointColor")
        val purpose = Refl.cls("xaero.hud.minimap.waypoint.WaypointPurpose")
        val make = waypoint.getConstructor(Int::class.java, Int::class.java, Int::class.java, String::class.java, String::class.java, color, purpose)
        val fromIndex: Method = color.getMethod("fromIndex", Int::class.java)
        val normal: Any = purpose.getField("NORMAL").get(null)
        val trackerSystem = Refl.cls("xaero.common.minimap.radar.tracker.system.IPlayerTrackerSystem")
        val trackerReader = Refl.cls("xaero.common.minimap.radar.tracker.system.ITrackedPlayerReader")
        val onCompile = Refl.cls("xaero.hud.minimap.info.render.compile.InfoDisplayOnCompile")
    }

    private val api by lazy { Api() }
    private val synced = HashMap<String, Synced>()
    private var session: WeakReference<Any>? = null
    private var shown: Any? = null
    private var trackerDone = false
    private var addLine: Method? = null
    private var effective: Method? = null

    private fun key(dim: String) = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dim))

    private fun onWorldMapScreen(): Boolean {
        val screen = Minecraft.getInstance().screen ?: return false
        return Detect.worldMap && screen.javaClass.name == "xaero.map.gui.GuiMap"
    }

    fun tick(force: Boolean) {
        trackerGuard.run { registerTracker() }
        waypointGuard.run { syncWaypoints(force) }
    }

    fun forceFairPlay(on: Boolean) {
        fairGuard.run {
            val module = Refl.staticField("xaero.hud.minimap.BuiltInHudModules", "MINIMAP")
            val processor = Refl.call(Refl.call(module, "getCurrentSession"), "getProcessor")
            Refl.call(processor, "setFairPlayOnlyMessageReceived", on)
        }
    }

    fun clear() {
        synced.clear()
        session = null
    }

    private fun registerTracker() {
        if (trackerDone) return
        val mod = Refl.staticField("xaero.common.HudMod", "INSTANCE") ?: return
        val manager = Refl.call(mod, "getPlayerTrackerSystemManager") ?: return
        val reader = Refl.proxy(api.trackerReader) { name, args ->
            val p = args[0] as Tracked
            when (name) {
                "getId" -> p.id
                "getX" -> p.x
                "getY" -> p.y
                "getZ" -> p.z
                "getDimension" -> trackerGuard.call(Level.OVERWORLD) { key(p.dim) }
                else -> null
            }
        }
        val system = Refl.proxy(api.trackerSystem) { name, _ ->
            when (name) {
                "getReader" -> reader
                "getTrackedPlayerIterator" -> trackerGuard.call(mutableListOf<Tracked>().iterator()) { Registry.tracked.flatMap { it.players() }.toMutableList().iterator() }
                else -> null
            }
        }
        manager.javaClass.getMethod("register", String::class.java, api.trackerSystem).invoke(manager, "kami", system)
        trackerDone = true
    }

    @Volatile
    private var mapScreen = false
    private var displayFailed = false

    fun pollScreen() {
        mapScreen = onWorldMapScreen()
    }

    private fun markersOn(source: PointSource) = Registry.markers.any { it.id == source.id && Registry.on(it.toggle) }

    private fun syncWaypoints(force: Boolean) {
        val level = Minecraft.getInstance().level ?: return
        val dim = level.dimension().location().toString()
        val module = Refl.staticField("xaero.hud.minimap.BuiltInHudModules", "MINIMAP")
        val current = Refl.call(module, "getCurrentSession") ?: return
        val fresh = session?.get() !== current
        if (fresh) {
            session = WeakReference(current)
            synced.clear()
        }
        val sources = Registry.waypoints.toList()
        val stale = sources.filter { s ->
            val st = synced[s.id]
            force || fresh || st == null || st.dim != dim || st.rev != s.rev()
        }
        if (stale.isEmpty()) return
        val root = Refl.call(Refl.call(current, "getWorldManager"), "getAutoRootContainer") ?: return
        val dirName = Refl.call(Refl.call(current, "getDimensionHelper"), "getDimensionDirectoryName", level.dimension()) as String
        val container = Refl.call(root, "addSubContainer", Refl.call(Refl.call(root, "getPath"), "resolve", dirName))
        val manager = Refl.call(container, "getThirdPartyWaypointManager")
        for (s in stale) {
            val tp = Refl.call(manager, "get", ResourceLocation.fromNamespaceAndPath("kami_libs", s.id)) ?: continue
            val st = synced.getOrPut(s.id) { Synced(null, null, -1, emptyMap()) }
            if (st.tp !== tp || st.dim != dim) {
                Refl.call(tp, "clear")
                st.sigs = emptyMap()
                if (st.tp !== tp) Refl.call(tp, "setEnabledStateGetter", Supplier { Registry.on(s.toggle) && !(mapScreen && markersOn(s)) })
                st.tp = tp
            }
            val points = s.points(dim).associateBy { it.id }
            val sigs = points.mapValues { (_, p) -> Objects.hash(p.x, p.y, p.z, p.name, p.initials, p.color) }
            val removed = st.sigs.keys - sigs.keys
            val upserted = sigs.filter { (id, sig) -> st.sigs[id] != sig }.keys
            (removed + upserted).forEach { Refl.call(tp, "remove", it) }
            upserted.forEach { id ->
                val p = points.getValue(id)
                val color = api.fromIndex.invoke(null, Colors.nearestChatColor(p.color))
                Refl.call(tp, "add", id, api.make.newInstance(p.x, p.y ?: 64, p.z, p.name, p.initials.take(2), color, api.normal))
            }
            st.dim = dim
            st.rev = s.rev()
            st.sigs = sigs
        }
    }

    fun display(): Any? = if (displayFailed) null else infoGuard.call(null) {
        shown ?: try {
            build().also { shown = it }
        } catch (e: Throwable) {
            displayFailed = true
            throw e
        }
    }

    private fun build(): Any {
        val builder = Refl.staticCallOn(Refl.cls("xaero.hud.minimap.info.InfoDisplay\$Builder"), "begin")
        fun set(name: String, arg: Any?, param: Class<*>? = null) {
            val m = builder!!.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 1 && (param?.let { p -> it.parameterTypes[0] == p } ?: (arg == null || it.parameterTypes[0].isInstance(arg))) }
                ?: throw NoSuchMethodException("InfoDisplay.Builder.$name")
            m.invoke(builder, arg)
        }
        val compile = Refl.proxy(api.onCompile) { name, args ->
            if (name == "onCompile") compile(args[0], args[1])
            null
        }
        set("setId", "kami")
        set("setName", Component.literal("Kami"))
        set("setDefaultState", true)
        set("setCodec", Refl.staticField("xaero.hud.minimap.info.codec.InfoDisplayCommonStateCodecs", "BOOLEAN"), Refl.cls("xaero.hud.minimap.info.codec.InfoDisplayStateCodec"))
        set("setWidgetFactory", Refl.staticField("xaero.hud.minimap.info.widget.InfoDisplayCommonWidgetFactories", "OFF_ON"))
        set("setCompiler", compile)
        return Refl.call(builder, "build")!!
    }

    private fun compile(display: Any?, compiler: Any?) {
        infoGuard.run {
            val on = (effective ?: display!!.javaClass.getMethod("getEffectiveState").also { effective = it }).invoke(display)
            if (on != true) return@run
            val mc = Minecraft.getInstance()
            val level = mc.level ?: return@run
            val pos: BlockPos = mc.player?.blockPosition() ?: return@run
            val dim = level.dimension().location().toString()
            val add = addLine ?: compiler!!.javaClass.getMethod("addLine", Component::class.java).also { addLine = it }
            Registry.infos.forEach { info -> info.lines(dim, pos.x, pos.y, pos.z).forEach { add.invoke(compiler, it) } }
        }
    }
}
