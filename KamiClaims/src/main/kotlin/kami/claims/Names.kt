package kami.claims

import kami.libs.util.toUuidOrNull
import kami.libs.admin.Players
import net.minecraft.server.MinecraftServer
import java.util.UUID

object Names {
    fun of(server: MinecraftServer?, id: String): String =
        id.toUuidOrNull()?.let { u -> server?.let { Players.name(it, u) } } ?: id.take(8)

    fun id(server: MinecraftServer, name: String): String? = Players.resolve(server, name)?.toString()
}
