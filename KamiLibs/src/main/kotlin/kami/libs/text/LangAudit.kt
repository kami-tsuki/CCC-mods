package kami.libs.text

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

object LangAudit {
    class Report(val problems: List<String>, val unused: List<String>)

    private val placeholder = Regex("""%(\d+\$)?s""")
    private val keyLiteral = Regex(""""(kami_[a-z]+\.[a-z0-9_.]*[a-z0-9_])"""")
    private val fileName = Regex("""\.(json|wal|dat|txt|log)(\.|$)""")
    private val pluralCall = Regex("""\b(?:trn|plural|count)\(\s*"([a-z0-9_.]+)"""")
    private val bannedChars = listOf("—", "!")
    private val bannedEnglish = listOf(
        "seamless", "seamlessly", "effortless", "effortlessly", "robust", "leverage", "unlock", "dive into", "delve", "empower",
        "elevate", "streamline", "enhance", "comprehensive", "cutting-edge", "powerful", "intuitive", "simply", "just", "easily",
        "please", "successfully", "oops", "whoops", "awesome", "great", "exciting", "journey", "let's", "feel free",
        "don't worry", "it's important to", "note that", "are you sure"
    )
    private val bannedGerman = listOf(
        "nahtlos", "mühelos", "einfach", "leistungsstark", "intuitiv", "erfolgreich", "bitte", "leider", "hoppla", "super",
        "spannend", "tauche ein", "entdecke", "keine sorge", "bist du sicher"
    )

    fun load(file: Path): Map<String, String> =
        Json.parseToJsonElement(file.readText()).jsonObject.mapValues { it.value.jsonPrimitive.content }

    fun referencedKeys(sourceDir: Path): Set<String> {
        if (!Files.exists(sourceDir)) return emptySet()
        val sources = Files.walk(sourceDir).use { s -> s.filter { it.extension == "kt" }.map { it.readText() }.toList() }.joinToString("\n")
        return keyLiteral.findAll(sources).map { it.groupValues[1] }.filterNot { fileName.containsMatchIn(it) }.toSet()
    }

    fun audit(langDir: Path, sourceDir: Path, shared: Map<String, String> = emptyMap(), extraUsedKeys: Set<String> = emptySet()): Report {
        val en = load(langDir.resolve("en_us.json"))
        val de = load(langDir.resolve("de_de.json"))
        val problems = ArrayList<String>()
        (en.keys - de.keys).forEach { problems += "missing in de_de: $it" }
        (de.keys - en.keys).forEach { problems += "missing in en_us: $it" }
        en.forEach { (key, value) -> de[key]?.let { if (placeholders(value) != placeholders(it)) problems += "placeholders differ: $key" } }
        en.forEach { (key, value) -> banned(value.replace("just now", ""), bannedEnglish)?.let { problems += "en_us $key: $it" } }
        de.forEach { (key, value) -> banned(value, bannedGerman)?.let { problems += "de_de $key: $it" } }
        val known = en.keys + shared.keys
        val sources = Files.walk(sourceDir).use { s -> s.filter { it.extension == "kt" }.map { it.readText() }.toList() }.joinToString("\n")
        val referenced = keyLiteral.findAll(sources).map { it.groupValues[1] }.filterNot { fileName.containsMatchIn(it) }.toSet()
        referenced.filter { it !in known && !(("$it.one" in known) && ("$it.other" in known)) }.forEach { problems += "unknown key: $it" }
        pluralCall.findAll(sources).map { it.groupValues[1] }.filter { "$it.one" !in known || "$it.other" !in known }.forEach { problems += "plural forms missing: $it" }
        val used = referenced.flatMap { listOf(it, "$it.one", "$it.other", "$it.desc", "$it.tooltip") }.toSet() + extraUsedKeys
        val prefixes = Regex(""""(kami_[a-z]+\.[a-z0-9_.]*\.)\$""").findAll(sources).map { it.groupValues[1] }.toSet()
        val unused = en.keys.filter { key -> !key.startsWith("key.") && key !in used && prefixes.none { key.startsWith(it) } }
        return Report(problems, unused)
    }

    private fun placeholders(value: String): Pair<Int, Set<String>> {
        val found = placeholder.findAll(value.replace("%%", "")).map { it.value }.toList()
        return found.count { it == "%s" } to found.filter { it != "%s" }.toSet()
    }

    private fun banned(value: String, words: List<String>): String? {
        bannedChars.firstOrNull { it in value }?.let { return "contains '$it'" }
        return words.firstOrNull { Regex("""(?i)(?<!\p{L})${Regex.escape(it)}(?!\p{L})""").containsMatchIn(value) }?.let { "contains '$it'" }
    }
}
