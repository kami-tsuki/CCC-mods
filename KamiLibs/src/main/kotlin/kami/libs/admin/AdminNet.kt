package kami.libs.admin

import kami.libs.KamiLibs
import kami.libs.db.Db
import kami.libs.net.ActPayload
import kami.libs.net.Packets
import kami.libs.net.SnapshotPayload
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent

object AdminNet {
    private const val MAX_REPLY = 1_048_576
    private val packets = Packets.forMod(KamiLibs.ID)
    private val requests = ActPayload.channel(packets, "admin_req", maxArgs = 64, maxArg = 8192)
    private val replies = SnapshotPayload.channel(packets, "admin_reply", MAX_REPLY)

    internal val requestType get() = requests.type

    internal fun request(name: String, args: List<String>, rid: Int) = requests(name, args, rid = rid)

    fun register(e: RegisterPayloadHandlersEvent) {
        val r = e.registrar("1").optional()
        r.playToServer(requests.type, requests.codec) { a, ctx -> (ctx.player() as? ServerPlayer)?.let { Admin.handle(it, a.name, a.args, a.rid) } }
        r.playToClient(replies.type, replies.codec) { s, _ -> AdminClient.receive(s) }
    }

    internal fun reply(p: ServerPlayer, r: AdminReply) {
        if (!replies.send(p, encode(r))) replies.send(p, encode(AdminReply(false, error = "kami_libs.admin.too_large", rid = r.rid)))
    }

    private fun encode(r: AdminReply) = Db.json.encodeToString(AdminReply.serializer(), r)
}
