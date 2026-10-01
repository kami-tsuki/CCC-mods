package kami.libs.progress

import kami.libs.log.Log
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.Event
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS

class ProgressEvent(val kind: String, val subject: String, val amount: Long, val player: ServerPlayer?, val country: String?, val once: String? = null) : Event()

object KamiProgress {
    private val log = Log.of("libs")
    private val busAvailable = runCatching { FORGE_BUS }.isSuccess

    fun post(player: ServerPlayer, kind: String, subject: String, amount: Long, once: String? = null) = post(kind) { ProgressEvent(kind, subject, amount, player, null, once) }

    fun post(country: String, kind: String, subject: String, amount: Long, once: String? = null) = post(kind) { ProgressEvent(kind, subject, amount, null, country, once) }

    private inline fun post(kind: String, event: () -> ProgressEvent) {
        if (!busAvailable) return
        try {
            FORGE_BUS.post(event())
        } catch (e: Exception) {
            log.error("Progress listener failed for {}", kind, e)
        }
    }
}
