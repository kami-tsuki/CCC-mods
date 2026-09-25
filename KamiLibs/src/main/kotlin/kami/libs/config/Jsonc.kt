package kami.libs.config

import java.nio.file.Files
import java.nio.file.Path

object Jsonc {
    private val key = Regex("""^(\s*)"([^"]+)":\s*(.*)$""")

    fun annotate(text: String, docs: Map<String, String>, header: List<String> = emptyList()): String {
        val parents = ArrayDeque<Pair<Int, String>>()
        val seen = HashSet<String>()
        return buildString {
            header.forEach { append("// ").append(it).append('\n') }
            text.lines().forEach { line ->
                val match = key.matchEntire(line)
                if (match != null) {
                    val (indent, name, rest) = match.destructured
                    while (parents.isNotEmpty() && parents.last().first >= indent.length) parents.removeLast()
                    doc(docs, parents.map { it.second } + name)?.takeIf { seen.add(it) }?.let { append(indent).append("// ").append(docs[it]).append('\n') }
                    if (rest.endsWith("{") || rest.endsWith("[")) parents.addLast(indent.length to name)
                }
                append(line).append('\n')
            }
        }.trimEnd() + "\n"
    }

    private fun doc(docs: Map<String, String>, path: List<String>): String? = path.joinToString(".").takeIf { it in docs }
        ?: docs.keys.firstOrNull { key -> key.split('.').let { it.size == path.size && it.indices.all { i -> it[i] == "*" || it[i] == path[i] } } }

    fun write(path: Path, text: String) {
        if (!Files.exists(path) || Files.readString(path) != text) Files.writeString(path, text)
    }

    fun reason(e: Throwable): String = e.message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: e.javaClass.simpleName
}
