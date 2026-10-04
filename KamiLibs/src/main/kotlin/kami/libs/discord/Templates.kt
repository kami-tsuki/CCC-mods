package kami.libs.discord

object Templates {
    private val placeholder = Regex("\\{(\\w+)}")

    fun fill(template: String, values: Map<String, String>): String =
        placeholder.replace(template) { values[it.groupValues[1]] ?: it.value }
}
