package kami.libs.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonBuilder
import net.neoforged.fml.loading.FMLPaths
import java.nio.file.Path

object Configs {
    fun json(build: JsonBuilder.() -> Unit = {}): Json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        build()
    }

    fun dir(modId: String): Path = FMLPaths.CONFIGDIR.get().resolve(modId)
    fun file(name: String): Path = FMLPaths.CONFIGDIR.get().resolve(name)
}
