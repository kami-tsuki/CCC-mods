package kami.claims.research

import kami.claims.Country
import kami.claims.net.ResearchSync
import kami.libs.config.ConfigFolder
import kami.libs.config.Configs
import kami.libs.log.Log
import kami.libs.text.Phrase
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.neoforged.neoforge.server.ServerLifecycleHooks

object Research {
    var defs = ResearchDefs.EMPTY
        internal set
    var problems: List<String> = emptyList()
        internal set

    private var loadProblems: List<String> = emptyList()
    private val log = Log.of("research")
    private val folder by lazy { ConfigFolder(Configs.dir("claims").resolve("research"), "Reload with /kami reload claims") }

    fun level(country: Country) = country.level.coerceAtLeast(1)

    fun available(country: Country): List<Node> = defs.treesFor(country).flatMap { it.nodes }.filter { node ->
        node.key !in country.research.done && country.research.queue.none { it.node == node.key } && node.conditions().all { it.met(country) }
    }

    fun allowed(country: Country?, recipe: ResourceLocation) = Gate.recipe(country, recipe.namespace, recipe.path)

    fun allowedBlock(country: Country?, block: ResourceLocation) = Gate.block(country, block.namespace, block.path)

    fun reload(): String? {
        load(folder)
        ServerLifecycleHooks.getCurrentServer()?.let(::resolve)
        return if (problems.isEmpty()) null else Phrase.of("kami_claims.research.reload.problems", problems.size).resolve()
    }

    fun resolve(server: MinecraftServer) {
        val resolution = if (defs.settings.enabled) Resolver(ServerFacts.read(server), defs).run() else Resolution.EMPTY
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
    }

    fun load(folder: ConfigFolder): List<String> {
        folder.startLoad()
        val settings = folder.file("research.json", ResearchSettings.serializer(), ResearchSettings(), Docs.settings, "Research settings")
        val levels = folder.file("levels.json", LevelsConfig.serializer(), LevelsConfig(), Docs.levels, "Country levels and capacities")
        val groups = folder.files("groups", Group.serializer(), Defaults.groups, Docs.group, "Research group")
        val trees = folder.files("trees", TreeFile.serializer(), Defaults.trees, Docs.tree, "Research tree")
        val built = Validator.build(settings, levels, groups, trees, defs.trees, Defaults.trees)
        defs = built.defs
        Gate.install(Resolution.EMPTY)
        ResearchSync.defsChanged()
        Queue.dropOrphans()
        Levels.settleAllRewards()
        loadProblems = folder.problems + built.problems
        problems = loadProblems
        problems.forEach { log.warn("{}", it) }
        return problems
    }
}
