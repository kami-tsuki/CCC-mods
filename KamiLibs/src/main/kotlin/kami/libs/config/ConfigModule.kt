package kami.libs.config

import kotlinx.serialization.KSerializer

abstract class ConfigModule<T : Any>(
    mod: String,
    serializer: KSerializer<T>,
    default: T,
    sections: List<Section>,
    legacy: String? = null,
    sane: (T) -> T = { it },
) {
    private val file = KamiConfig(mod, serializer, default, sections, legacy, sane = sane)

    var s: T
        get() = file.value
        set(value) { file.value = value }

    fun load() = file.load()
}
