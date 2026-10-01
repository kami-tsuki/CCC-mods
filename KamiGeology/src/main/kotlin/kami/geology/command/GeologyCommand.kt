package kami.geology.command

import kami.libs.chat.Theme
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import kami.geology.command.GeoText.distance
import kami.geology.command.GeoText.ore
import kami.geology.command.GeoText.site
import kami.geology.config.ConfigStore
import kami.geology.map.Workers
import kami.geology.net.MapServer
import kami.geology.world.Hardness
import kami.geology.world.WorldContext
import kami.geology.world.Worlds
import kami.libs.chat.Chat
import kami.libs.text.Phrase
import kami.libs.command.*
import net.minecraft.core.BlockPos

object GeologyCommand {
    private val searchRadii = listOf(256, 512, 1024, 2048, 3072)

    fun register() = KamiCommands.module("geology", "Ore deposits, heatmap and audits") {
        requires(op)
            .then(lit("heatmap").does(::heatmap))
            .then(
                lit("audit").does { audit(it, 200, 5, 3000) }
                    .then(
                        arg("samples", IntegerArgumentType.integer(20, 2000)).does { audit(it, it.int("samples"), 5, 3000) }
                            .then(
                                arg("claimChunks", IntegerArgumentType.integer(1, 32)).does { audit(it, it.int("samples"), it.int("claimChunks"), 3000) }
                                    .then(arg("radius", IntegerArgumentType.integer(256, 20000)).does { audit(it, it.int("samples"), it.int("claimChunks"), it.int("radius")) })
                            )
                    )
            )
            .then(
                lit("info").does { info(it, 96) }
                    .then(arg("radius", IntegerArgumentType.integer(16, 512)).does { info(it, it.int("radius")) })
            )
            .then(
                lit("find").then(
                    word("ore") { ConfigStore.current?.ores?.map { it.id }.orEmpty() }
                        .does { find(it, null, null) }
                        .then(lit("core").then(arg("core", BoolArgumentType.bool()).does { find(it, core(it), null) }.then(tier(withCore = true))))
                        .then(tier(withCore = false))
                )
            )
    }

    fun reload(): String {
        val settings = ConfigStore.load() ?: error("could not load, see the log")
        Worlds.clear()
        Hardness.update(settings)
        val skipped = ConfigStore.problems.let { if (it.isEmpty()) "" else " Skipped: ${it.joinToString("; ")}." }
        return "{${settings.ores.size}} ores active.$skipped New chunks use it, ore removal needs a restart."
    }

    private fun core(ctx: Ctx) = BoolArgumentType.getBool(ctx, "core")

    private fun tier(withCore: Boolean) = lit("tier").then(
        word("tier") { emptyList() }
            .suggests { ctx, builder ->
                ConfigStore.current?.ore(ctx.text("ore"))?.tiers?.forEach { builder.suggest(it.name) }
                builder.buildFuture()
            }
            .does { find(it, if (withCore) core(it) else null, it.text("tier")) }
            .let { if (withCore) it else it.then(lit("core").then(arg("core", BoolArgumentType.bool()).does { ctx -> find(ctx, core(ctx), ctx.text("tier")) })) }
    )

    private fun world(ctx: Ctx): WorldContext = Worlds.of(ctx.source.level) ?: fail(Phrase.of("kami_geology.error.dimension"))

    private fun dim(ctx: Ctx) = ctx.source.level.dimension().location().toString()

    private fun heatmap(ctx: Ctx) {
        val player = ctx.me()
        if (!MapServer.canOpen(player)) fail(Phrase.of("kami_geology.error.no_client"))
        if (!MapServer.open(player)) fail(Phrase.of("kami_geology.error.dimension"))
    }

    private fun audit(ctx: Ctx, samples: Int, claimChunks: Int, radius: Int) {
        val world = world(ctx)
        val source = ctx.source
        val origin = BlockPos.containing(source.position)
        ctx.info(Phrase.of("kami_geology.audit.start", Phrase.value(samples)))
        Workers.pool.execute {
            val lines = runCatching { Audit.run(world, origin, samples, claimChunks, radius) }
            source.server.execute {
                lines.onSuccess { l ->
                    source.sendSuccess({ GeoText.chat.info(l.first().component()) }, false)
                    l.drop(1).forEach { line -> source.sendSuccess({ Chat.row { add(line) } }, false) }
                }.onFailure { source.sendFailure(GeoText.chat.bad(Phrase.of("kami_geology.audit.failed", it.message ?: "").component())) }
            }
        }
    }

    private fun info(ctx: Ctx, radius: Int) {
        val world = world(ctx)
        val origin = BlockPos.containing(ctx.source.position)
        val sites = world.sitesNear(origin, radius).sortedBy { distance(origin, it) }
        if (sites.isEmpty()) return ctx.info(Phrase.of("kami_geology.info.none", Phrase.value(radius)))
        ctx.info(Phrase.of("kami_geology.info.count", Phrase.plural("kami_geology.unit.deposit", sites.size.toLong()).asValue(), Phrase.value(radius)))
        sites.forEach { site -> ctx.row { site(site, origin, dim(ctx)) } }
    }

    private fun find(ctx: Ctx, core: Boolean?, tier: String?) {
        val id = ctx.text("ore")
        val world = world(ctx)
        val ore = world.settings.ore(id) ?: fail(Phrase.of("kami_geology.find.unknown_ore", Phrase.value(id)))
        if (tier != null && ore.tiers.none { it.name == tier }) fail(Phrase.of("kami_geology.find.unknown_tier", Phrase.value(tier), ore.tiers.joinToString { it.name }))
        val origin = BlockPos.containing(ctx.source.position)
        val site = searchRadii.firstNotNullOfOrNull { r ->
            world.sitesIn(ore, origin.x - r, origin.z - r, origin.x + r, origin.z + r)
                .filter { (core == null || it.hasCore == core) && (tier == null || it.tier.name == tier) }
                .minByOrNull { distance(origin, it) }
        }
        val filters = listOfNotNull(tier?.let(GeoText::tier), core?.let { Phrase.of(if (it) "kami_geology.find.with_core" else "kami_geology.find.without_core") })
        val filter = filters.reduceOrNull { a, b -> Phrase.of("kami_libs.format.list", a, b) }
        val range = Phrase.value(searchRadii.last())
        if (site == null) fail(if (filter == null) Phrase.of("kami_geology.find.none", GeoText.orePhrase(id), range) else Phrase.of("kami_geology.find.none_filtered", GeoText.orePhrase(id), filter, range))
        ctx.msg { add(Phrase.of("kami_geology.find.nearest", GeoText.orePhrase(id).asValue())); filter?.let { muted(" ("); add(it, Theme.MUTED); muted(")") } }
        ctx.row { site(site, origin, dim(ctx)) }
    }
}
