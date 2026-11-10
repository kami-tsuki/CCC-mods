package kami.claims.research

import kami.claims.Country
import kami.claims.net.ResearchSync
import kami.libs.config.ConfigFolder
import kami.libs.config.Configs
import kami.libs.config.Jsonc
import kami.libs.log.Log
import kami.libs.text.Phrase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import net.minecraft.server.MinecraftServer
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.nio.file.Files

object Research {
    var defs = ResearchDefs.EMPTY
        internal set
    private var problems: List<String> = emptyList()
    private var loadProblems: List<String> = emptyList()
    private val log = Log.of("research")
    private val folder by lazy { ConfigFolder(Configs.dir("claims").resolve("research"), "Reload with /kami reload claims") }

    fun level(country: Country) = country.level.coerceAtLeast(1)

    fun available(country: Country): List<Node> = defs.treesFor(country).flatMap { it.nodes }.filter { node ->
        node.key !in country.research.done && country.research.queue.none { it.node == node.key } && Penalty.hard(node).all { it.met(country) }
    }

    fun reload(): String? {
        load(folder)
        ServerLifecycleHooks.getCurrentServer()?.let(::resolve)
        val migrated = ResearchMigration.last
        val text = if (defs.settings.migrations == MigrationMode.DRY_RUN) "kami_claims.research.reload.dry_run" else "kami_claims.research.reload.migrated"
        return listOfNotNull(
            if (problems.isEmpty()) null else Phrase.of("kami_claims.research.reload.problems", problems.size).resolve(),
            if (migrated.isEmpty()) null else Phrase.of(text, migrated.size, migrated.map { it.country }.distinct().size).resolve()
        ).joinToString(". ").ifEmpty { null }
    }

    fun resolve(server: MinecraftServer) {
        val resolution = try {
            if (defs.settings.enabled) Resolver(ServerFacts.read(server), defs).run() else Resolution.EMPTY
        } catch (e: Exception) {
            log.error("Research resolve failed; keeping the previous gate", e)
            return
        }
        Gate.install(resolution)
        problems = loadProblems + resolution.problems
        resolution.problems.forEach { log.warn("{}", it) }
        resolution.notes.forEach { log.info("{}", it) }
        log.info("Gated {} recipes and {} blocks", resolution.gated.recipes.size, resolution.gated.blocks.size)
        defs.trees.values.forEach { tree ->
            val parts = tree.nodes.mapNotNull { resolution.nodes[it.key] }
            log.info("{}: {} nodes, {} recipes, {} blocks", tree.id, tree.nodes.size, parts.flatMapTo(HashSet()) { it.recipes }.size, parts.flatMapTo(HashSet()) { it.blocks }.size)
        }
        ResearchSync.defsChanged()
        ResearchSync.menus(null)
    }

    private fun persistLevels(folder: ConfigFolder, levels: LevelsConfig, from: Int) {
        val path = folder.root.resolve("levels.json")
        val backup = folder.root.resolve("levels.json.v$from.bak")
        runCatching {
            if (!Files.exists(backup)) Files.copy(path, backup)
            val json = Configs.json()
            val text = Jsonc.annotate(json.encodeToString(JsonObject.serializer(), json.encodeToJsonElement(LevelsConfig.serializer(), levels).jsonObject), Docs.levels, listOf("Country levels and capacities", "Reload with /kami reload claims"))
            Jsonc.write(path, text)
            check(Jsonc.read(path) == text) { "the file was not written" }
            log.info("levels.json upgraded to version {}, the previous file is kept as {}", levels.version, backup.fileName)
        }.onFailure { log.warn("Could not write the upgraded levels.json: {}", it.message) }
    }

    private fun warnCustomTrees(trees: Map<String, TreeFile>) {
        val json = Configs.json()
        val custom = trees.filter { (id, file) ->
            Defaults.trees[id]?.let { runCatching { json.encodeToString(TreeFile.serializer(), it) != json.encodeToString(TreeFile.serializer(), file) }.getOrDefault(false) } == true
        }.keys
        if (custom.isNotEmpty()) log.warn("Research trees {} differ from the bundled defaults, the legacy task signatures and costs may not match and queued progress may be remapped wrongly", custom)
    }

    fun load(folder: ConfigFolder): List<String> {
        folder.startLoad()
        val settings = folder.file("research.json", ResearchSettings.serializer(), ResearchSettings(), Docs.settings, "Research settings")
        val stored = folder.file("levels.json", LevelsConfig.serializer(), LevelsConfig(version = LevelDefaults.VERSION), Docs.levels, "Country levels and capacities")
        val levels = LevelDefaults.upgrade(stored)
        if (levels !== stored) persistLevels(folder, levels, stored.version)
        val groups = folder.files("groups", Group.serializer(), Defaults.groups, Docs.group, "Research group")
        val trees = folder.files("trees", TreeFile.serializer(), Defaults.trees, Docs.tree, "Research tree")
        warnCustomTrees(trees)
        val built = Validator.build(settings, levels, groups, trees, defs.trees, Defaults.trees)
        defs = built.defs
        ResearchMigration.run()
        if (defs.settings.migrations != MigrationMode.APPLY) Queue.dropOrphans()
        Levels.advanceAll()
        loadProblems = folder.problems + built.problems
        problems = loadProblems
        problems.forEach { log.warn("{}", it) }
        return problems
    }
}
