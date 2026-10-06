package kami.libs.admin

import kami.libs.net.SnapshotPayload
import kami.libs.net.decode
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.minecraft.client.Minecraft
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS

internal object AdminClient {
    private class Pending(val at: Long, val onReply: (AdminReply) -> Unit)

    private const val TIMEOUT = 10_000L
    private val pending = LinkedHashMap<Int, Pending>()
    private var next = 0
    private var asking = false

    var level = AdminPerms.Level.NONE
        private set

    fun init() {
        FORGE_BUS.addListener<ClientTickEvent.Post> { expire() }
        FORGE_BUS.addListener<ClientPlayerNetworkEvent.LoggingIn> { refreshLevel() }
        FORGE_BUS.addListener<ClientPlayerNetworkEvent.LoggingOut> { reset() }
    }

    fun request(name: String, args: List<String>, reason: String?, onReply: (AdminReply) -> Unit) {
        if (Minecraft.getInstance().connection?.hasChannel(AdminNet.requestType) != true) return onReply(AdminReply(false, error = "kami_libs.admin.unavailable"))
        val rid = ++next
        pending[rid] = Pending(System.currentTimeMillis(), onReply)
        PacketDistributor.sendToServer(AdminNet.request(name, listOf(reason.orEmpty()) + args, rid))
    }

    fun receive(s: SnapshotPayload) {
        val r = s.decode<AdminReply>() ?: return
        pending.remove(r.rid)?.onReply?.invoke(r)
    }

    fun refreshLevel() {
        if (asking) return
        asking = true
        request("admin.level", emptyList(), null) { r ->
            asking = false
            val name = (r.data as? JsonPrimitive)?.contentOrNull
            AdminPerms.Level.entries.firstOrNull { r.ok && it.name == name }?.let { level = it }
        }
    }

    private fun expire() {
        if (pending.isEmpty()) return
        val cutoff = System.currentTimeMillis() - TIMEOUT
        val late = pending.filterValues { it.at < cutoff }
        late.keys.forEach { pending.remove(it) }
        late.values.forEach { it.onReply(AdminReply(false, error = "kami_libs.admin.timeout")) }
    }

    private fun reset() {
        val dropped = pending.values.toList()
        pending.clear()
        dropped.forEach { it.onReply(AdminReply(false, error = "kami_libs.admin.unavailable")) }
        level = AdminPerms.Level.NONE
        asking = false
    }
}
