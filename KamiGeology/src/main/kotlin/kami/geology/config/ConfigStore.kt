package kami.geology.config

import kami.geology.KamiGeology
import kami.libs.config.Configs
import kami.libs.mc.MissingMod
import kotlinx.serialization.ExperimentalSerializationApi
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

@OptIn(ExperimentalSerializationApi::class)
object ConfigStore {
    private val json = Configs.json {
        explicitNulls = false
        coerceInputValues = true
        allowComments = true
        allowTrailingComma = true
    }
    private val key = Regex("""^(\s*)"([^"]+)":\s*(.*)$""")
    private val dir: Path get() = Configs.dir(KamiGeology.ID)
    private var version = 0

    @Volatile
    private var settings: Settings? = null

    @Volatile
    private var tagged = false

    val current: Settings? get() {
        if (!tagged) refresh()
        return settings
    }

    @Synchronized
    private fun refresh() {
        if (!tagged && Compat.ready()) load()
    }

    @Synchronized
    fun load(): Settings? = try {
        tagged = Compat.ready()
        build().also { settings = it }
    } catch (e: Exception) {
        KamiGeology.LOG.error("Could not load configuration", e)
        null
    }

    private fun build(): Settings {
        val oreDir = dir.resolve("ores")
        Files.createDirectories(oreDir)
        val general = sync(dir.resolve("general.json"), GeneralConfig(), Docs.general) ?: GeneralConfig()
        val provinceMap = sync(dir.resolve("provinces.json"), Defaults.provinces, emptyMap()) ?: Defaults.provinces
        Defaults.ores.forEach { (id, config) ->
            val file = oreDir.resolve("$id.json")
            if (!Files.exists(file)) Compat.adapt(id, config)?.let { save(file, json.encodeToString(it), Docs.ore) }
        }

        val order = Defaults.ores.keys.toList()
        val files = Files.list(oreDir).use { stream -> stream.filter { it.extension == "json" }.toList() }
            .sortedWith(compareBy({ order.indexOf(it.nameWithoutExtension).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.fileName.toString() }))

        val fixed = if (general.richness.grades.any { it.weight > 0.0 }) general else general.copy(richness = Richness())
        val replaceable = BlockRule(fixed.replaceable, "general.replaceable")
        val deepslate = BlockRule(fixed.deepslateHosts, "general.deepslateHosts")

        val parsed = files.map { it.nameWithoutExtension to sync<OreConfig>(it, null, Docs.ore) }
        val found = if (fixed.discoverOres && tagged) {
            val claimed = parsed.flatMapTo(HashSet()) { (id, config) -> config?.let { Compat.claims(id, it) }.orEmpty() }
            Compat.discover(claimed, parsed.mapTo(HashSet()) { it.first }, provinceMap.keys.toList())
                .onEach { (id, config) -> save(oreDir.resolve("$id.json"), json.encodeToString(config), Docs.ore) }
                .map { it.key to it.value }
        } else emptyList()

        val resolved = (parsed + found).mapIndexedNotNull { index, (id, config) ->
            config?.let { resolve(id, index, it, provinceMap.keys, replaceable, deepslate) }
        }

        return Settings(fixed, Provinces(provinceMap, fixed.fallbackProvince), link(resolved), ++version)
    }

    private fun link(ores: List<Ore>): List<Ore> {
        val byId = ores.associateBy { it.id }
        fun anchored(ore: Ore): Boolean {
            var current = ore
            repeat(ores.size + 1) {
                val anchor = current.deposit?.anchor ?: return true
                current = byId[anchor.ore] ?: return false
            }
            return false
        }
        return ores.filter { ore ->
            anchored(ore).also { if (!it) KamiGeology.LOG.info("Ore '{}' unavailable: its anchor ore is missing or circular", ore.id) }
        }.onEach { ore -> ore.anchor = ore.deposit?.anchor?.let { byId[it.ore] } }
    }

    private fun resolve(id: String, index: Int, config: OreConfig, provinces: Set<String>, replaceable: BlockRule, deepslate: BlockRule): Ore? {
        if (!config.enabled) return null
        val context = "ore '$id'"
        return try {
            validate(config, provinces)
            val stone = Blocks.require(config.blocks.stone, context)
            val deep = config.blocks.deepslate?.let { Blocks.require(it, context) } ?: stone
            val hosts = config.blocks.hosts.entries.associate { (host, ore) ->
                Blocks.require(host, context) to Blocks.require(ore, context).defaultBlockState()
            }
            val core = config.core?.let { Blocks.find(it.block) ?: null.also { _ -> Blocks.missing(it.block, context) } }
            val own = hosts.values.mapTo(hashSetOf(stone, deep)) { it.block }
            val strip = (config.blocks.strip.mapNotNull { Blocks.find(it) } + Compat.material(id)).filterNot { it in own }.toSet()
            Ore(
                id, index, config, stone.defaultBlockState(), deep.defaultBlockState(), hosts, core?.defaultBlockState(),
                config.halo?.let { Palette(it.blocks, context) }?.takeUnless { it.isEmpty },
                config.outcrop?.let { Palette(it.blocks, context) }?.takeUnless { it.isEmpty },
                replaceable,
                config.halo?.replace?.let { BlockRule(it, context) } ?: replaceable,
                BlockRule(config.outcrop?.replace.orEmpty(), context),
                deepslate,
                strip,
                config.scatter?.provinces?.toSet()
            )
        } catch (e: MissingMod) {
            KamiGeology.LOG.info("Ore '{}' skipped: {}", id, e.message)
            null
        } catch (e: IllegalArgumentException) {
            KamiGeology.LOG.error("Ore '{}' disabled: {}", id, e.message)
            null
        }
    }

    private fun validate(config: OreConfig, provinces: Set<String>) {
        config.provinces.forEach { (name, weight) ->
            require(weight >= 0.0) { "province weight '$name' must not be negative" }
            if (name !in provinces) KamiGeology.LOG.warn("Province '{}' is not defined in provinces.json", name)
        }
        config.deposit?.let { deposit ->
            require(deposit.tiers.isNotEmpty()) { "deposit.tiers must not be empty" }
            require(deposit.spacing >= 16) { "deposit.spacing must be at least 16" }
            require(deposit.chance in 0.0..1.0) { "deposit.chance must be between 0 and 1" }
            require(deposit.bodyRadius in 0.05..0.99) { "deposit.bodyRadius must be between 0.05 and 0.99" }
            require(deposit.shape.warpScale > 1.0) { "deposit.shape.warpScale must be above 1" }
            ints(deposit.height, "deposit.height")
            deposit.limit?.let { ints(it, "deposit.limit") }
            deposit.surfaceDepth?.let { ints(it, "deposit.surfaceDepth", 0) }
            doubles(deposit.shape.dip, "deposit.shape.dip")
            ints(deposit.shape.lumps, "deposit.shape.lumps")
            deposit.tiers.forEach { tier ->
                require(tier.weight > 0.0) { "tier '${tier.name}' weight must be positive" }
                ints(tier.length, "tier '${tier.name}' length", 2)
                ints(tier.width, "tier '${tier.name}' width", 2)
                ints(tier.thickness, "tier '${tier.name}' thickness", 2)
            }
            deposit.anchor?.let { anchor ->
                ints(anchor.offsetX, "deposit.anchor.offsetX")
                ints(anchor.offsetY, "deposit.anchor.offsetY")
                ints(anchor.offsetZ, "deposit.anchor.offsetZ")
            }
        }
        config.scatter?.let { scatter ->
            require(scatter.perChunk >= 0.0) { "scatter.perChunk must not be negative" }
            ints(scatter.size, "scatter.size", 1)
            ints(scatter.height, "scatter.height")
        }
        config.core?.let { require(it.chance in 0.0..1.0) { "core.chance must be between 0 and 1" } }
    }

    private fun ints(range: List<Int>, name: String, min: Int = Int.MIN_VALUE) =
        require(range.size == 2 && range[0] <= range[1] && range[0] >= min) { "$name must be [min, max]" }

    private fun doubles(range: List<Double>, name: String) =
        require(range.size == 2 && range[0] <= range[1]) { "$name must be [min, max]" }

    private inline fun <reified T> sync(path: Path, default: T?, docs: Map<String, String>): T? {
        val value = if (Files.exists(path)) parse<T>(path) ?: return null else default ?: return null
        save(path, json.encodeToString(value), docs)
        return value
    }

    private inline fun <reified T> parse(path: Path): T? = try {
        json.decodeFromString<T>(Files.readString(path))
    } catch (e: Exception) {
        KamiGeology.LOG.error("Ignoring {}: {}", path.fileName, e.message)
        null
    }

    private fun save(path: Path, text: String, docs: Map<String, String>) {
        val annotated = annotate(text, docs)
        if (!Files.exists(path) || Files.readString(path) != annotated) Files.writeString(path, annotated)
    }

    private fun annotate(text: String, docs: Map<String, String>): String {
        val parents = ArrayDeque<Pair<Int, String>>()
        val seen = HashSet<String>()
        return buildString {
            text.lines().forEach { line ->
                val match = key.matchEntire(line)
                if (match != null) {
                    val (indent, name, rest) = match.destructured
                    while (parents.isNotEmpty() && parents.last().first >= indent.length) parents.removeLast()
                    val path = (parents.map { it.second } + name).joinToString(".")
                    docs[path]?.takeIf { seen.add(path) }?.let { append(indent).append("// ").append(it).append('\n') }
                    if (rest.endsWith("{") || rest.endsWith("[")) parents.addLast(indent.length to name)
                }
                append(line).append('\n')
            }
        }.trimEnd() + "\n"
    }
}
