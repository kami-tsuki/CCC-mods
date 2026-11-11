package kami.libs.ui.style

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object ThemeLoader {
    private val pretty = Json { prettyPrint = true }

    private fun JsonObject.text(key: String) = requireNotNull(get(key)?.jsonPrimitive?.contentOrNull) { "missing '$key'" }

    private fun strings(root: JsonObject, key: String): Map<String, String> =
        root[key]?.jsonObject?.entries?.associate { (k, v) -> k to v.jsonPrimitive.content } ?: emptyMap()

    fun parse(text: String): ThemeDef {
        val root = Json.parseToJsonElement(text).jsonObject
        val colors = LinkedHashMap<String, Int>()
        var chart = emptyList<Int>()
        root["color"]?.jsonObject?.forEach { (key, value) ->
            if (key == "chart") chart = value.jsonArray.map { Tokens.parse(it.jsonPrimitive.content) }
            else if (Tokens.known(key)) colors[Tokens.canonical(key)] = Tokens.parse(value.jsonPrimitive.content)
        }
        val id = root.text("id")
        val name = root["name"]?.jsonPrimitive?.contentOrNull ?: id
        return ThemeDef(id, root.text("family"), name, root["base"]?.jsonPrimitive?.contentOrNull, Variant.valueOf(root.text("variant").uppercase()), colors, chart, strings(root, "shape"), strings(root, "sound"))
    }

    fun unknownKeys(text: String): List<String> =
        Json.parseToJsonElement(text).jsonObject["color"]?.jsonObject?.keys?.filter { it != "chart" && !Tokens.known(it) } ?: emptyList()

    fun parseOverrides(text: String): Map<String, Int> =
        Json.parseToJsonElement(text).jsonObject.filterKeys { Tokens.known(it) }.entries.associate { (k, v) -> Tokens.canonical(k) to Tokens.parse(v.jsonPrimitive.content) }

    fun toJson(def: ThemeDef): String = pretty.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("id", def.id)
        put("family", def.family)
        put("name", def.name)
        def.base?.let { put("base", it) }
        put("variant", def.variant.name.lowercase())
        put("color", buildJsonObject {
            def.colors.forEach { (k, v) -> put(k, Tokens.format(v)) }
            if (def.chart.isNotEmpty()) put("chart", JsonArray(def.chart.map { JsonPrimitive(Tokens.format(it)) }))
        })
        if (def.shape.isNotEmpty()) put("shape", buildJsonObject { def.shape.forEach { (k, v) -> put(k, v) } })
        if (def.sound.isNotEmpty()) put("sound", buildJsonObject { def.sound.forEach { (k, v) -> put(k, v) } })
    })

    fun resolve(def: ThemeDef, lookup: (String) -> ThemeDef?): Resolved {
        val chain = ArrayList<ThemeDef>()
        val seen = HashSet<String>()
        var current: ThemeDef? = def
        while (current != null) {
            val node: ThemeDef = current
            require(seen.add(node.id)) { "theme cycle at '${node.id}'" }
            chain += node
            current = node.base?.let { base -> requireNotNull(lookup(base)) { "unknown base '$base' of '${node.id}'" } }
        }
        val values = HashMap<String, Int>()
        var chart = emptyList<Int>()
        val shape = HashMap<String, String>()
        val sound = HashMap<String, String>()
        chain.asReversed().forEach { values += it.colors; shape += it.shape; sound += it.sound; if (it.chart.isNotEmpty()) chart = it.chart }
        val missing = Tokens.names.filter { it !in values }
        require(missing.isEmpty()) { "theme '${def.id}' lacks $missing" }
        require(chart.size == Tokens.CHART) { "theme '${def.id}' needs ${Tokens.CHART} chart colours" }
        val sounds = sound.filterKeys { it in SoundSpec.KEYS }.mapValues { (_, v) -> requireNotNull(SoundSpec.parse(v)) { "theme '${def.id}' has bad sound '$v'" } }
        return Resolved(def, values, chart.toIntArray(), ShapeDef.parse(shape), sounds)
    }
}
