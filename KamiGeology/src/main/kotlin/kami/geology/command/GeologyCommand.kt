package kami.geology.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import kami.geology.config.ConfigStore
import kami.geology.map.Workers
import kami.geology.net.MapServer
import kami.geology.world.Site
import kami.geology.world.WorldContext
import kami.geology.world.Worlds
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import kotlin.math.hypot
import kotlin.math.roundToInt

object GeologyCommand {
    private val searchRadii = listOf(256, 512, 1024, 2048, 3072)

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("kami_geology").requires { it.hasPermission(2) }
                .then(Commands.literal("heatmap").executes(::heatmap))
                .then(
                    Commands.literal("audit").executes { audit(it, 200, 5, 3000) }
                        .then(
                            Commands.argument("samples", IntegerArgumentType.integer(20, 2000)).executes { audit(it, IntegerArgumentType.getInteger(it, "samples"), 5, 3000) }
                                .then(
                                    Commands.argument("claimChunks", IntegerArgumentType.integer(1, 32)).executes {
                                        audit(it, IntegerArgumentType.getInteger(it, "samples"), IntegerArgumentType.getInteger(it, "claimChunks"), 3000)
                                    }.then(
                                        Commands.argument("radius", IntegerArgumentType.integer(256, 20000)).executes {
                                            audit(
                                                it, IntegerArgumentType.getInteger(it, "samples"),
                                                IntegerArgumentType.getInteger(it, "claimChunks"), IntegerArgumentType.getInteger(it, "radius")
                                            )
                                        }
                                    )
                                )
                        )
                )
                .then(
                    Commands.literal("info").executes { info(it, 96) }
                        .then(Commands.argument("radius", IntegerArgumentType.integer(16, 512)).executes { info(it, IntegerArgumentType.getInteger(it, "radius")) })
                )
                .then(
                    Commands.literal("find").then(
                        Commands.argument("ore", StringArgumentType.word())
                            .suggests { _, builder -> SharedSuggestionProvider.suggest(ConfigStore.current?.ores?.map { it.id }.orEmpty(), builder) }
                            .executes { find(it, StringArgumentType.getString(it, "ore"), null, null) }
                            .then(
                                Commands.literal("core").then(
                                    Commands.argument("core", BoolArgumentType.bool())
                                        .executes { find(it, StringArgumentType.getString(it, "ore"), BoolArgumentType.getBool(it, "core"), null) }
                                        .then(tierArgument(withCore = true))
                                )
                            )
                            .then(tierArgument(withCore = false))
                    )
                )
        )
    }

    fun reload(): String {
        val settings = ConfigStore.load() ?: error("could not load, see the log")
        Worlds.clear()
        val skipped = ConfigStore.problems.let { if (it.isEmpty()) "" else ", skipped ${it.joinToString("; ")}" }
        return "${settings.ores.size} ores loaded$skipped. New chunks use it, ore removal needs a restart."
    }

    private fun heatmap(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        if (!MapServer.canOpen(player)) {
            context.source.sendFailure(Component.literal("Your client needs the kami_geology mod to open the map"))
            return 0
        }
        if (!MapServer.open(player)) {
            context.source.sendFailure(Component.literal("Deposits are not active in this dimension"))
            return 0
        }
        return 1
    }

    private fun audit(context: CommandContext<CommandSourceStack>, samples: Int, claimChunks: Int, radius: Int): Int {
        val world = world(context) ?: return 0
        val source = context.source
        val origin = BlockPos.containing(source.position)
        source.sendSuccess({ Component.literal("Audit running for $samples areas...") }, false)
        Workers.pool.execute {
            val lines = try {
                Audit.run(world, origin, samples, claimChunks, radius)
            } catch (e: Exception) {
                listOf("Audit failed: ${e.message}")
            }
            source.server.execute { lines.forEach { line -> source.sendSuccess({ Component.literal(line) }, false) } }
        }
        return 1
    }

    private fun world(context: CommandContext<CommandSourceStack>): WorldContext? =
        Worlds.of(context.source.level).also { if (it == null) context.source.sendFailure(Component.literal("Deposits are not active in this dimension")) }

    private fun info(context: CommandContext<CommandSourceStack>, radius: Int): Int {
        val world = world(context) ?: return 0
        val origin = BlockPos.containing(context.source.position)
        val sites = world.sitesNear(origin, radius).sortedBy { distance(origin, it) }
        if (sites.isEmpty()) context.source.sendSuccess({ Component.literal("No deposits within $radius blocks") }, false)
        sites.forEach { context.source.sendSuccess({ Component.literal(describe(it, origin)) }, false) }
        return sites.size
    }

    private fun tierArgument(withCore: Boolean) = Commands.literal("tier").then(
        Commands.argument("tier", StringArgumentType.word())
            .suggests { context, builder ->
                val ore = ConfigStore.current?.ore(StringArgumentType.getString(context, "ore"))
                SharedSuggestionProvider.suggest(ore?.tiers?.map { it.name }.orEmpty(), builder)
            }
            .executes {
                find(it, StringArgumentType.getString(it, "ore"), if (withCore) BoolArgumentType.getBool(it, "core") else null, StringArgumentType.getString(it, "tier"))
            }
            .let { tier ->
                if (withCore) tier else tier.then(
                    Commands.literal("core").then(
                        Commands.argument("core", BoolArgumentType.bool())
                            .executes { find(it, StringArgumentType.getString(it, "ore"), BoolArgumentType.getBool(it, "core"), StringArgumentType.getString(it, "tier")) }
                    )
                )
            }
    )

    private fun find(context: CommandContext<CommandSourceStack>, id: String, core: Boolean?, tier: String?): Int {
        val world = world(context) ?: return 0
        val ore = world.settings.ore(id)
        if (ore == null) {
            context.source.sendFailure(Component.literal("Unknown ore '$id'"))
            return 0
        }
        if (tier != null && ore.tiers.none { it.name == tier }) {
            context.source.sendFailure(Component.literal("Unknown tier '$tier', use ${ore.tiers.joinToString { it.name }}"))
            return 0
        }
        val origin = BlockPos.containing(context.source.position)
        for (radius in searchRadii) {
            val site = world.sitesIn(ore, origin.x - radius, origin.z - radius, origin.x + radius, origin.z + radius)
                .filter { (core == null || it.hasCore == core) && (tier == null || it.tier.name == tier) }
                .minByOrNull { distance(origin, it) }
            if (site != null) {
                context.source.sendSuccess({ Component.literal(describe(site, origin)) }, false)
                return 1
            }
        }
        val filters = listOfNotNull(core?.let { "core=$it" }, tier?.let { "tier=$it" }).joinToString(" ")
        context.source.sendFailure(Component.literal("No '$id' deposit${if (filters.isEmpty()) "" else " ($filters)"} within ${searchRadii.last()} blocks"))
        return 0
    }

    private fun distance(origin: BlockPos, site: Site) = hypot(site.x - origin.x, site.z - origin.z)

    private fun describe(site: Site, origin: BlockPos) =
        "${site.ore.id} ${site.tier.name} ${site.grade.name}${if (site.hasCore) " core" else ""} ${site.length}x${site.width}x${site.thickness} at ${site.x.toInt()} ${site.y.toInt()} ${site.z.toInt()} (${distance(origin, site).roundToInt()} blocks)"
}
