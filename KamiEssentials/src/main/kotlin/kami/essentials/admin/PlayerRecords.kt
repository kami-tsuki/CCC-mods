package kami.essentials.admin

import kami.libs.admin.Players
import kami.libs.util.toUuidOrNull
import kami.essentials.KamiEssentials
import kami.essentials.inv.Offline
import kami.essentials.net.played
import kami.libs.db.Db
import kami.libs.db.SavePoint
import kami.libs.db.Session
import kami.libs.db.Table
import net.minecraft.Util
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class Spot(val dim: String, val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float)

class PlayerRecord(val id: UUID, var name: String, var firstJoin: Long, var lastSeen: Long) {
    var spot: Spot? = null
    var lastIp: String? = null
    var playtimeMs = 0L
    val ips = LinkedHashMap<String, Long>()
    val names = LinkedHashMap<String, Long>()
}

object PlayerRecords {
    private const val MAX_IPS = 16
    private val players = Table<Collection<PlayerRecord>>(
        "ess_player", listOf("uuid"), listOf("name", "first_join", "last_seen", "dim", "x", "y", "z", "yaw", "pitch", "last_ip", "playtime_ms")
    ) { rs -> rs.map(::row) }
    private val ipRows = Table<Collection<PlayerRecord>>("ess_player_ip", listOf("uuid", "ip"), listOf("last_at")) { rs ->
        rs.flatMap { r -> r.ips.map { (ip, at) -> listOf(r.id.toString(), ip, at) } }
    }
    private val nameRows = Table<Collection<PlayerRecord>>("ess_player_name", listOf("uuid", "name"), listOf("at")) { rs ->
        rs.flatMap { r -> r.names.map { (name, at) -> listOf(r.id.toString(), name, at) } }
    }
    private val records = ConcurrentHashMap<UUID, PlayerRecord>()
    private val byIp = ConcurrentHashMap<String, MutableSet<UUID>>()
    @Volatile
    private var attached = false

    init {
        SavePoint.register("ess.players") {
            if (attached) SavePoint.join(listOf(players.sync(records.values), ipRows.sync(records.values), nameRows.sync(records.values))) else null
        }
    }

    fun all(): Collection<PlayerRecord> = records.values

    operator fun get(id: UUID) = records[id]

    fun byName(name: String) = records.values.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun onIp(ip: String): Set<UUID> = byIp[ip].orEmpty()

    fun alts(r: PlayerRecord): Set<UUID> = r.ips.keys.flatMap(::onIp).toSet() - r.id

    fun load(server: MinecraftServer) {
        Db.read { load(this) }
        bootstrap(server)
    }

    internal fun load(s: Session) {
        attached = false
        records.clear()
        byIp.clear()
        s.query("SELECT uuid, name, first_join, last_seen, dim, x, y, z, yaw, pitch, last_ip, playtime_ms FROM ess_player") { row ->
            PlayerRecord(UUID.fromString(row.string("uuid")), row.string("name"), row.long("first_join"), row.long("last_seen")).apply {
                spot = row.stringOrNull("dim")?.let { Spot(it, row.double("x"), row.double("y"), row.double("z"), row.double("yaw").toFloat(), row.double("pitch").toFloat()) }
                lastIp = row.stringOrNull("last_ip")
                playtimeMs = row.long("playtime_ms")
            }
        }.forEach { records[it.id] = it }
        s.query("SELECT uuid, ip, last_at FROM ess_player_ip ORDER BY last_at") { Triple(it.string("uuid"), it.string("ip"), it.long("last_at")) }
            .forEach { (id, ip, at) -> records[UUID.fromString(id)]?.ips?.put(ip, at) }
        s.query("SELECT uuid, name, at FROM ess_player_name ORDER BY at") { Triple(it.string("uuid"), it.string("name"), it.long("at")) }
            .forEach { (id, name, at) -> records[UUID.fromString(id)]?.names?.put(name, at) }
        records.values.forEach { r -> r.ips.keys.forEach { byIp.getOrPut(it) { ConcurrentHashMap.newKeySet() }.add(r.id) } }
        prime()
        attached = true
    }

    fun unload() {
        attached = false
        records.clear()
        byIp.clear()
        prime()
    }

    private fun prime() {
        players.prime(records.values)
        ipRows.prime(records.values)
        nameRows.prime(records.values)
    }

    fun joined(p: ServerPlayer) = login(p.uuid, p.gameProfile.name, p.ipAddress.takeUnless { it.startsWith("<") }, System.currentTimeMillis())

    fun left(p: ServerPlayer) = update(p.uuid, spot(p), playtime(p), System.currentTimeMillis())

    fun autosave(server: MinecraftServer) = server.playerList.players.forEach(::left)

    fun spot(p: ServerPlayer) = Spot(p.level().dimension().location().toString(), p.x, p.y, p.z, p.yRot, p.xRot)

    fun playtime(p: ServerPlayer) = played(p).playtime

    fun position(server: MinecraftServer, r: PlayerRecord): Spot? = r.spot ?: runCatching { Offline.read(server, r.id)?.let(::stored) }.getOrNull()

    internal fun login(id: UUID, name: String, ip: String?, now: Long): PlayerRecord {
        val r = records.getOrPut(id) { PlayerRecord(id, name, now, now) }
        r.name = name
        r.lastSeen = now
        r.names.putIfAbsent(name, now)
        ip?.let { seen(r, it, now) }
        return r
    }

    internal fun update(id: UUID, spot: Spot, playtimeMs: Long, now: Long) {
        val r = records[id] ?: return
        r.spot = spot
        r.playtimeMs = playtimeMs
        r.lastSeen = now
    }

    private fun seen(r: PlayerRecord, ip: String, now: Long) {
        r.lastIp = ip
        r.ips.remove(ip)
        r.ips[ip] = now
        byIp.getOrPut(ip) { ConcurrentHashMap.newKeySet() }.add(r.id)
        while (r.ips.size > MAX_IPS) {
            val old = r.ips.keys.first()
            r.ips.remove(old)
            byIp[old]?.remove(r.id)
        }
    }

    private class Found(val id: UUID, val created: Long, val modified: Long, val spot: Spot?, val playtimeMs: Long)

    private fun bootstrap(server: MinecraftServer) {
        val dir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR)
        val stats = server.getWorldPath(LevelResource.PLAYER_STATS_DIR)
        val known = records.keys.toSet()
        Util.backgroundExecutor().execute {
            if (!Files.isDirectory(dir)) return@execute
            val found = Files.list(dir).use { files -> files.toList() }.mapNotNull { f ->
                f.fileName.toString().takeIf { it.endsWith(".dat") }?.let { it.removeSuffix(".dat").toUuidOrNull() }?.let { it to f }
            }.filter { it.first !in known }.mapNotNull { (id, file) ->
                runCatching {
                    val attrs = Files.readAttributes(file, BasicFileAttributes::class.java)
                    Found(id, attrs.creationTime().toMillis(), attrs.lastModifiedTime().toMillis(), Offline.read(server, id)?.let(::stored), played(stats.resolve("$id.json")).playtime)
                }.onFailure { KamiEssentials.LOG.warn("Could not import the player record of {}: {}", id, it.toString()) }.getOrNull()
            }
            server.execute {
                if (!attached) return@execute
                found.forEach { f ->
                    if (records.containsKey(f.id)) return@forEach
                    val name = Players.name(server, f.id) ?: f.id.toString()
                    records[f.id] = PlayerRecord(f.id, name, f.created, f.modified).also {
                        it.spot = f.spot
                        it.playtimeMs = f.playtimeMs
                        it.names[name] = it.firstJoin
                    }
                }
            }
        }
    }

    private fun stored(tag: CompoundTag): Spot? {
        val pos = tag.getList("Pos", Tag.TAG_DOUBLE.toInt()).takeIf { it.size == 3 } ?: return null
        val rot = tag.getList("Rotation", Tag.TAG_FLOAT.toInt())
        val dim = tag.getString("Dimension").ifEmpty { "minecraft:overworld" }
        return Spot(dim, pos.getDouble(0), pos.getDouble(1), pos.getDouble(2), rot.getFloat(0), rot.getFloat(1))
    }

    private fun row(r: PlayerRecord): List<Any?> {
        val s = r.spot
        return listOf(r.id.toString(), r.name, r.firstJoin, r.lastSeen, s?.dim, s?.x, s?.y, s?.z, s?.yaw?.toDouble(), s?.pitch?.toDouble(), r.lastIp, r.playtimeMs)
    }
}
