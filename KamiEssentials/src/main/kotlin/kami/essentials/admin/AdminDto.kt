package kami.essentials.admin

import kami.libs.admin.Audit
import kotlinx.serialization.Serializable

@Serializable
class PlayerRow(
    val uuid: String,
    val name: String,
    val online: Boolean,
    val country: String? = null,
    val rank: String? = null,
    val balance: Long = 0,
    val lastSeen: Long = 0,
    val playtimeMs: Long = 0,
    val banned: Boolean = false,
    val muted: Boolean = false,
    val warns: Int = 0
)

@Serializable
class Pos(val dim: String, val x: Double, val y: Double, val z: Double, val live: Boolean)

@Serializable
class BanInfo(val reason: String, val by: String, val since: Long, val until: Long? = null, val ip: Boolean = false)

@Serializable
class MuteInfo(val reason: String, val by: String, val since: Long, val until: Long? = null)

@Serializable
class PlayerDetail(
    val row: PlayerRow,
    val firstJoin: Long,
    val lastSeen: Long,
    val position: Pos? = null,
    val ban: BanInfo? = null,
    val mute: MuteInfo? = null,
    val lastIp: String? = null,
    val knownIps: List<String>? = null,
    val alts: List<PlayerRow>? = null,
    val canManage: Boolean = false,
    val warns: List<WarnRow> = emptyList()
)

@Serializable
class BanRow(val uuid: String, val name: String, val reason: String, val by: String, val since: Long, val until: Long? = null, val ip: Boolean = false)

@Serializable
class MuteRow(val uuid: String, val name: String, val reason: String, val by: String, val since: Long, val until: Long? = null)

@Serializable
class RevokeInfo(val by: String, val at: Long, val reason: String)

@Serializable
class WarnRow(val id: Long, val uuid: String, val name: String, val reason: String, val by: String, val at: Long, val revoked: RevokeInfo? = null)

@Serializable
class NameRow(val uuid: String, val name: String, val online: Boolean)

@Serializable
class Dashboard(
    val tps: Double,
    val mspt: Double,
    val online: Int,
    val max: Int,
    val uptimeMs: Long,
    val memUsed: Long,
    val memMax: Long,
    val uniqueToday: Int,
    val newToday: Int,
    val bank: Long,
    val bankNet24h: Long,
    val metrics: Map<String, Long> = emptyMap(),
    val audit: List<Audit.Entry> = emptyList(),
    val onlineSeries: List<Int> = emptyList()
)
