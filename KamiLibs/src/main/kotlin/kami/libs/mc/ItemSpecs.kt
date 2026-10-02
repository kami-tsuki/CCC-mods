package kami.libs.mc

object ItemSpecs {
    val packKeys = mapOf("tacz:modern_kinetic_gun" to "GunId", "tacz:ammo" to "AmmoId", "tacz:attachment" to "AttachmentId")

    fun base(spec: String): String = if (spec.indexOf('#') > 0) spec.substringBefore('#') else spec

    fun pack(spec: String): String = if (spec.indexOf('#') > 0) spec.substringAfter('#') else ""

    fun join(base: String, pack: String): String = if (pack.isEmpty()) base else "$base#$pack"
}
