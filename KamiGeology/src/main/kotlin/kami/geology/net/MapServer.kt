package kami.geology.net

import kami.geology.KamiGeology
import kami.geology.client.ClientHooks
import kami.geology.config.Distribution
import kami.geology.config.Ore
import kami.geology.map.Heatmap
import kami.geology.map.MapColors
import kami.geology.map.Sparse
import kami.geology.map.Workers
import kami.geology.world.Prospector
import kami.geology.world.Worlds
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object MapServer {
    private const val MAX_CELLS = 160_000
    private val latestMap = ConcurrentHashMap<UUID, Int>()
    private val latestProbe = ConcurrentHashMap<UUID, Int>()

    private class ScanLock(val dimension: ResourceLocation, val oreId: String, val x0: Int, val z0: Int, val w: Int, val h: Int)
    private val scans = ConcurrentHashMap<UUID, ScanLock>()

    /** Survival prospector: opens a heatmap locked to a single ore and the tool tier's region, no OP needed. Tier 1 is handled separately (see Heatmap.probeOre). */
    fun scan(player: ServerPlayer, ore: Ore, tier: Int) {
        val level = player.serverLevel()
        val chunkX = Math.floorDiv(player.blockX, 16)
        val chunkZ = Math.floorDiv(player.blockZ, 16)
        val region = Prospector.region(tier, player.blockX, player.blockZ, chunkX, chunkZ)
        scans[player.uuid] = ScanLock(level.dimension().location(), ore.id, region.x0, region.z0, region.w, region.h)
        val info = OreInfo(
            ore.id, MapColors.ore(ore.id), ore.deposit != null, Heatmap.scatterColumn(ore).toFloat(),
            ore.scatter?.height?.get(0) ?: 0, ore.scatter?.height?.get(1) ?: 0,
            ore.scatter?.distribution == Distribution.TRIANGLE, null
        )
        PacketDistributor.sendToPlayer(
            player,
            OpenMap(
                level.dimension().location().toString(), region.x0 + region.w / 2, region.z0 + region.h / 2,
                level.minBuildHeight, level.maxBuildHeight - 1, listOf(info), emptyList(),
                region.x0, region.z0, region.w, region.h
            )
        )
    }

    fun register(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("1").optional()
        registrar.playToClient(OpenMap.TYPE, OpenMap.CODEC) { payload, _ -> ClientHooks.open(payload) }
        registrar.playToClient(MapLayer.TYPE, MapLayer.CODEC) { payload, _ -> ClientHooks.layer(payload) }
        registrar.playToClient(MapDone.TYPE, MapDone.CODEC) { payload, _ -> ClientHooks.done(payload) }
        registrar.playToClient(ProbeResponse.TYPE, ProbeResponse.CODEC) { payload, _ -> ClientHooks.probe(payload) }
        registrar.playToServer(MapRequest.TYPE, MapRequest.CODEC) { payload, context -> (context.player() as? ServerPlayer)?.let { map(it, payload) } }
        registrar.playToServer(ProbeRequest.TYPE, ProbeRequest.CODEC) { payload, context -> (context.player() as? ServerPlayer)?.let { probe(it, payload) } }
    }

    fun canOpen(player: ServerPlayer) = player.connection.hasChannel(OpenMap.TYPE)

    fun open(player: ServerPlayer): Boolean {
        val level = player.serverLevel()
        val world = Worlds.of(level) ?: return false
        val names = world.settings.provinces.names.toList()
        val ores = world.settings.ores.map { ore ->
            val scatter = ore.scatter
            OreInfo(
                ore.id, MapColors.ore(ore.id), ore.deposit != null,
                Heatmap.scatterColumn(ore).toFloat(),
                scatter?.height?.get(0) ?: 0, scatter?.height?.get(1) ?: 0, scatter?.distribution == Distribution.TRIANGLE,
                ore.scatterProvinces?.map { names.indexOf(it) }?.filter { it >= 0 }
            )
        }
        val provinces = names.map { ProvinceInfo(it, MapColors.province(it)) }
        PacketDistributor.sendToPlayer(
            player,
            OpenMap(level.dimension().location().toString(), player.blockX, player.blockZ, level.minBuildHeight, level.maxBuildHeight - 1, ores, provinces)
        )
        return true
    }

    private fun allowed(player: ServerPlayer) = player.createCommandSourceStack().hasPermission(2)

    private fun map(player: ServerPlayer, r: MapRequest) {
        val world = Worlds.of(player.serverLevel()) ?: return
        val ores: List<Ore>
        if (allowed(player)) {
            if (r.cell !in 1..256 || r.w < 1 || r.h < 1 || r.w.toLong() * r.h > MAX_CELLS || r.y0 > r.y1) return
            ores = world.settings.ores
        } else {
            val lock = scans[player.uuid] ?: return
            if (lock.dimension != player.serverLevel().dimension().location()) return
            if (r.cell != 1 || r.w != lock.w || r.h != lock.h || r.x0 != lock.x0 || r.z0 != lock.z0 || r.y0 > r.y1) return
            ores = listOfNotNull(world.settings.ore(lock.oreId))
            if (ores.isEmpty()) return
        }
        latestMap[player.uuid] = r.seq
        val server = player.server
        val query = Heatmap.Query(r.x0, r.z0, r.cell, r.w, r.h, r.y0, r.y1)
        val timing = Heatmap.Timing()
        val started = System.nanoTime()
        val remaining = AtomicInteger(ores.size + 1)
        fun stale() = latestMap[player.uuid] != r.seq

        fun deliver(layer: Int, data: ByteArray) {
            server.execute {
                PacketDistributor.sendToPlayer(player, MapLayer(r.seq, layer, data))
                if (remaining.decrementAndGet() == 0) {
                    fun ms(nanos: Long) = (nanos / 1_000_000L).toInt()
                    PacketDistributor.sendToPlayer(
                        player,
                        MapDone(r.seq, ms(System.nanoTime() - started), ms(timing.provinces.get()), ms(timing.sites.get()), ms(timing.paint.get()))
                    )
                }
            }
        }

        fun task(work: () -> Unit) = Workers.pool.execute {
            if (stale()) return@execute
            try {
                work()
            } catch (e: Exception) {
                KamiGeology.LOG.error("Map layer failed", e)
            }
        }

        task { Heatmap.provinces(world, query, ::stale, timing)?.let { deliver(-1, it) } }
        ores.forEachIndexed { index, ore ->
            task { Heatmap.deposits(world, query, ore, ::stale, timing)?.let { deliver(index, Sparse.pack(it)) } }
        }
    }

    private fun probe(player: ServerPlayer, r: ProbeRequest) {
        if (!allowed(player)) return
        val world = Worlds.of(player.serverLevel()) ?: return
        latestProbe[player.uuid] = r.seq
        val server = player.server
        Workers.pool.execute {
            if (latestProbe[player.uuid] != r.seq) return@execute
            val lines = try {
                Heatmap.probe(world, r.x, r.z, r.y0, r.y1)
            } catch (e: Exception) {
                KamiGeology.LOG.error("Probe failed", e)
                return@execute
            }
            server.execute { PacketDistributor.sendToPlayer(player, ProbeResponse(r.seq, r.x, r.z, lines)) }
        }
    }
}
