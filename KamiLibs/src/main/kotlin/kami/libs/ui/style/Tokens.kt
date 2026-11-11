package kami.libs.ui.style

object Tokens {
    const val CHART = 8

    val chunkTypes = listOf("civic", "mining", "farming", "forestry", "factory", "market", "residential", "infrastructure", "wilderness")
    val ranks = listOf("president", "chancellor", "officer", "citizen", "allied", "banished")
    val categorical = chunkTypes.map { "cat.chunk.$it" } + ranks.map { "cat.rank.$it" }

    val names = listOf(
        "bg.backdrop", "bg.canvas", "bg.surface", "bg.raised", "bg.sunken", "bg.field", "bg.hover", "bg.selected", "bg.overlay",
        "bevel.light", "bevel.dark", "bevel.inset_dark", "bevel.outline",
        "border.subtle", "border.default", "border.strong", "border.brass",
        "text.primary", "text.secondary", "text.muted", "text.disabled", "text.inverse", "text.link",
        "icon.tint", "icon.off",
        "tip.bg", "tip.border", "tip.text", "tip.secondary", "tip.muted",
        "sem.info", "sem.success", "sem.paid", "sem.warning", "sem.danger", "sem.money", "sem.focus",
        "geo.province", "geo.ally", "geo.neutral", "geo.banished", "geo.reserved",
        "shadow", "wash.hover", "wash.light", "wash.dark"
    ) + categorical

    val chunkTokens = chunkTypes.associateWith { "cat.chunk.$it" }
    val rankTokens = ranks.associateWith { "cat.rank.$it" }

    val tipPairs = listOf("text.primary" to "tip.text", "text.secondary" to "tip.secondary", "text.muted" to "tip.muted", "text.disabled" to "tip.muted")

    private val known = names.toSet()

    fun canonical(name: String) = if (name == "accent") "border.brass" else name

    fun known(name: String) = canonical(name) in known

    fun parse(hex: String): Int {
        val clean = hex.trim().removePrefix("#")
        require((clean.length == 6 || clean.length == 8) && clean.all { it in "0123456789abcdefABCDEF" }) { "bad colour '$hex'" }
        val alpha = if (clean.length == 8) clean.substring(6).toInt(16) else 0xFF
        return (alpha shl 24) or clean.substring(0, 6).toInt(16)
    }

    fun format(argb: Int): String {
        val rgb = "%06X".format(argb and 0xFFFFFF)
        val alpha = argb ushr 24
        return if (alpha == 0xFF) "#$rgb" else "#$rgb" + "%02X".format(alpha)
    }
}
