package kami.claims

import kami.libs.admin.Players
import net.minecraft.server.MinecraftServer
import java.util.UUID

object Names {
    fun of(server: MinecraftServer?, id: String): String =
        runCatching { UUID.fromString(id) }.getOrNull()?.let { server?.profileCache?.get(it)?.map { p -> p.name }?.orElse(null) } ?: id.take(8)

    fun id(server: MinecraftServer, name: String): String? = Players.resolve(server, name)?.toString()
}
