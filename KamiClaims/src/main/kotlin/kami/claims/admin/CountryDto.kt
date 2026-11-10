package kami.claims.admin

import kotlinx.serialization.Serializable

@Serializable
class CountryRow(
    val id: String, val name: String, val leader: String?, val leaderName: String, val members: Int, val claims: Int, val treasury: Long,
    val level: Int, val wars: Int, val overlord: String?, val provinces: Int, val color: Int, val active: Boolean,
    val power: Double = 0.0, val reputation: Int = 0, val fame: Double? = null, val fameCount: Int = 0
)

@Serializable
class MemberRow(val uuid: String, val name: String, val rank: String, val since: Long, val seen: Long)

@Serializable
class LedgerRow(val at: Long, val kind: String, val amount: Long, val balance: Long, val actor: String?, val note: String)

@Serializable
class WarRow(val id: String, val enemy: String, val phase: String, val attacker: Boolean)

@Serializable
class ResearchSummary(val done: Int, val total: Int, val queue: List<String>, val categories: List<ResearchCategory>)

@Serializable
class ResearchCategory(val tree: String, val treeTitle: String, val id: String, val title: String, val done: Int, val total: Int, val queued: Int)

@Serializable
class CountryDetail(
    val row: CountryRow, val rev: String, val created: Long, val members: List<MemberRow>, val ledger: List<LedgerRow>,
    val wars: List<WarRow>, val provinces: List<String>, val research: ResearchSummary
)
