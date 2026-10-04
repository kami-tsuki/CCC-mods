package kami.libs.discord

object Templates {
    private val placeholder = Regex("\\{(\\w+)}")
    private val code = Regex("`[^`\n]+`")

    fun fill(template: String, values: Map<String, String>): String =
        placeholder.replace(template) { values[it.groupValues[1]] ?: it.value }

    fun outsideCode(text: String, transform: (String, Boolean) -> String): String {
        val spans = code.findAll(text).map { it.value }.toList()
        return text.split(code).mapIndexed { i, part -> transform(part, i == 0) + spans.getOrElse(i) { "" } }.joinToString("")
    }

    fun code(value: String): String = if (value.isBlank()) value else "`${value.replace('`', '\'')}`"
}
