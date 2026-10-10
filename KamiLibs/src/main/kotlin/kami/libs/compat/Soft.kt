package kami.libs.compat

import kami.libs.KamiLibs
import net.neoforged.bus.api.Event
import net.neoforged.bus.api.EventPriority
import net.neoforged.neoforge.common.NeoForge
import java.util.function.Consumer

object Soft {
    @Suppress("UNCHECKED_CAST")
    fun event(modId: String, eventClass: String, priority: EventPriority = EventPriority.NORMAL, handler: (Event) -> Unit): Boolean {
        if (!Mods.loaded(modId)) return false
        return runCatching {
            val type = Class.forName(eventClass, false, Soft::class.java.classLoader)
            require(Event::class.java.isAssignableFrom(type)) { "not an event" }
            var warned = false
            NeoForge.EVENT_BUS.addListener(priority, false, type as Class<Event>, Consumer<Event> { e ->
                try {
                    handler(e)
                } catch (t: Throwable) {
                    if (!warned) {
                        warned = true
                        KamiLibs.LOG.warn("{} handler for {} failed: {}", modId, eventClass, t.toString(), t)
                    }
                }
            })
            true
        }.getOrElse {
            KamiLibs.LOG.warn("{} integration for {} unavailable: {}", modId, eventClass, it.toString())
            false
        }
    }

    fun expect(modId: String, className: String, vararg methods: String) {
        if (!Mods.loaded(modId)) return
        val type = runCatching { Class.forName(className, false, Soft::class.java.classLoader) }.getOrNull()
        if (type == null) {
            KamiLibs.LOG.error("{} class {} is missing, the protection hooked there is OFF", modId, className)
            return
        }
        val all = type.declaredMethods.map { it.name }.toSet() + type.methods.map { it.name }
        methods.filter { it !in all }.forEach { KamiLibs.LOG.error("{} method {}.{} is missing, the protection hooked there is OFF", modId, className, it) }
    }
}
