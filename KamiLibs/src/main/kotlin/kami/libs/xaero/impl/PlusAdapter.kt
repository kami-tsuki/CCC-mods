package kami.libs.xaero.impl

import it.unimi.dsi.fastutil.longs.Long2ObjectMap
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.objects.Object2IntMap
import it.unimi.dsi.fastutil.objects.Object2IntMaps
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import java.lang.reflect.Constructor
import java.util.Objects
import kami.libs.compat.Refl
import net.minecraft.resources.ResourceKey

object PlusAdapter {
    val guard = Guard("plus")

    private class Snapshot(val dim: String, val lines: Object2IntMap<Any>, val labels: Long2ObjectMap<Any>)

    private class Api {
        val line = Refl.cls("xaeroplus.feature.render.line.Line").getConstructor(Int::class.java, Int::class.java, Int::class.java, Int::class.java)
        val text: Constructor<*> = Refl.cls("xaeroplus.feature.render.text.Text").getConstructor(String::class.java, Int::class.java, Int::class.java, Int::class.java, Float::class.java)
        val factory = Refl.cls("xaeroplus.feature.render.DrawFeatureFactory")
        val lineSupplier = Refl.cls("xaeroplus.feature.render.line.MultiColorLineSupplier")
        val lineColor = Refl.cls("xaeroplus.feature.render.line.MultiColorLineColorFunction")
        val floatSupplier = Refl.cls("xaeroplus.util.FloatSupplier")
        val textSupplier = Refl.cls("xaeroplus.feature.render.text.TextSupplier")
    }

    private val api by lazy { Api() }

    @Volatile
    private var snapshot: Snapshot? = null
    private var linesDone = false
    private var textsDone = false
    private var lastKey = 0

    private val emptyLines: Object2IntMap<Any> = Object2IntMaps.emptyMap()

    fun tick(force: Boolean) {
        guard.run {
            if (!linesDone || !textsDone) register()
            val dim = Registry.dimension() ?: return@run
            val key = Objects.hash(dim, Registry.lines.map { listOf(Registry.on(it.toggle), it.rev()) })
            if (force || key != lastKey || snapshot?.dim != dim) {
                lastKey = key
                snapshot = build(dim)
            }
        }
    }

    fun clear() {
        snapshot = null
        lastKey = 0
    }

    private fun build(dim: String): Snapshot {
        val lines = Object2IntOpenHashMap<Any>()
        val labels = Long2ObjectOpenHashMap<Any>()
        var n = 0L
        for (s in Registry.lines) {
            if (!Registry.on(s.toggle)) continue
            s.lines(dim).forEach { lines.put(api.line.newInstance(it.x1, it.z1, it.x2, it.z2), it.color) }
            s.labels(dim).forEach { labels.put(n++, api.text.newInstance(it.text, it.x, it.z, it.color, it.scale)) }
        }
        return Snapshot(dim, lines, labels)
    }

    private fun register() {
        val manager = Refl.staticField("xaeroplus.Globals", "drawManager") ?: return
        val registry = Refl.call(manager, "registry") ?: return
        if (!linesDone) {
            val supplier = Refl.proxy(api.lineSupplier) { _, args -> guard.call(emptyLines) { linesFor(dimArg(args)) } }
            val color = Refl.proxy(api.lineColor) { _, args -> args.firstOrNull { it is Int } ?: 0 }
            val width = Refl.proxy(api.floatSupplier) { _, _ -> 0.3f }
            Refl.call(registry, "register", Refl.staticCallOn(api.factory, "multiColorLines", "kami_libs_lines", supplier, color, width, 250))
            linesDone = true
        }
        if (!textsDone) {
            val texts = Refl.proxy(api.textSupplier) { _, args -> guard.call(Long2ObjectMaps.emptyMap()) { labelsFor(dimArg(args)) } }
            Refl.call(registry, "register", Refl.staticCallOn(api.factory, "text", "kami_libs_labels", texts))
            textsDone = true
        }
    }

    private fun dimArg(args: Array<Any?>) = args.firstOrNull { it is ResourceKey<*> }

    private fun dimOf(key: Any?) = (key as? ResourceKey<*>)?.location()?.toString()

    private fun linesFor(dim: Any?): Object2IntMap<Any> = snapshot?.takeIf { it.dim == dimOf(dim) }?.lines ?: emptyLines

    private fun labelsFor(dim: Any?): Long2ObjectMap<Any> = snapshot?.takeIf { it.dim == dimOf(dim) }?.labels ?: Long2ObjectMaps.emptyMap()
}
