package kami.libs.admin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import net.minecraft.server.level.ServerPlayer

class AdminCtx(val player: ServerPlayer, val level: AdminPerms.Level, val reason: String) {
    fun fail(key: String, vararg args: Any?): Nothing = throw AdminFailure(key, args.map { it.toString() })
}

class AdminFailure(val key: String, val args: List<String> = emptyList()) : RuntimeException(key)

@Serializable
class AdminReply(val ok: Boolean, val data: JsonElement? = null, val error: String? = null, val rid: Int = 0)
