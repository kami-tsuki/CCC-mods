package kami.libs.admin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import net.minecraft.server.level.ServerPlayer
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Supplier

class AdminCtx(val player: ServerPlayer, val level: AdminPerms.Level, val reason: String) {
    fun fail(key: String, vararg args: Any?): Nothing = throw AdminFailure(key, args.map { it.toString() })

    fun <T> onServer(block: () -> T): T {
        val server = player.server
        if (server.isSameThread) return block()
        val future = server.submit(Supplier { block() })
        try {
            return future.get(SERVER_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw AdminFailure("kami_libs.admin.busy")
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private companion object {
        const val SERVER_WAIT_SECONDS = 5L
    }
}

class AdminFailure(val key: String, val args: List<String> = emptyList()) : RuntimeException(key)

@Serializable
class AdminReply(val ok: Boolean, val data: JsonElement? = null, val error: String? = null, val rid: Int = 0)
