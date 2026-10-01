package kami.claims.client.app

import kami.claims.client.app.pages.ResearchLook
import kami.claims.client.rankOf
import kami.claims.client.store.ClaimsStore
import kami.claims.client.store.ClientResearch
import kami.claims.net.LevelView
import kami.claims.Rank
import kami.claims.research.Capacity
import kami.libs.ui.core.Memo
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Stack
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Format
import kami.libs.ui.style.Severity
import kami.libs.ui.style.Icons
import kami.libs.ui.text.tr
import kami.libs.ui.widget.Lock
import kami.libs.ui.widget.capacityRow
import kami.libs.ui.widget.capacityLine

object ClientLocks {
    const val CLAIM_TYPE = "claim_type:"

    fun cap(cap: String): String? {
        val info = ClaimsStore.info ?: return tr("kami_claims.lock.no_country")
        val snap = ClaimsStore.snap ?: return tr("kami_claims.lock.loading")
        if (info.delegated && cap !in snap.delegable) return tr("kami_claims.lock.province_only")
        val min = snap.caps[cap]?.let(::rankOf) ?: Rank.PRESIDENT
        if (ClaimsStore.rank < min) return tr("kami_claims.lock.rank", Vocabulary.rank(min.name).label)
        return null
    }

    fun featureName(id: String) =
        if (id.startsWith(CLAIM_TYPE)) tr("kami_claims.feature.claim_type", Vocabulary.type(id.removePrefix(CLAIM_TYPE)).label)
        else tr("kami_claims.feature.${id.replace(':', '.')}")

    private val features = HashMap<String, Lock?>()
    private val raises = HashMap<Capacity, Lock?>()
    private val fulls = HashMap<Capacity, Lock?>()
    private val seen = Memo()
    private val loansTeaserMemo = Memo()
    private val buffsTeaserMemo = Memo()

    private fun fresh() = seen.of(ClientResearch.state, ClientResearch.defs, Format.locale) {
        features.clear()
        raises.clear()
        fulls.clear()
    }

    fun feature(id: String): Lock? {
        fresh()
        if (id in features) return features[id]
        return (ClientResearch.unlockLevel(id)?.takeIf { ClientResearch.state.level < it }?.let { Lock.level(it, featureName(id)) }).also { features[id] = it }
    }

    fun claimType(type: String) = feature(CLAIM_TYPE + type)

    fun featureIcon(id: String): Icon = when {
        id.startsWith(CLAIM_TYPE) -> Vocabulary.type(id.removePrefix(CLAIM_TYPE)).icon
        id == "role:chancellor" -> Icons.CROWN
        id == "banish" -> Icons.BAN
        id == "alliances" -> Icons.HANDSHAKE
        id == "embargoes" -> Icons.BROKEN_CHAIN
        id == "buffs" -> Icons.SHIELD
        id.startsWith("economy") -> Icons.SCALES
        else -> Icons.STAR
    }

    fun loansTeaser(): Lock? {
        if (ClientResearch.loans.unlocked) return null
        return loansTeaserMemo.of(ClientResearch.defs, Format.locale) {
            val level = ClientResearch.defs.trees.asSequence().flatMap { it.nodes }.firstOrNull { n -> n.unlocks.any { it.kind == "feature" && it.id == "loans" } }?.level ?: 10
            Lock(tr("kami_claims.loans.lock.label"), tr("kami_claims.loans.lock.how", level))
        }
    }

    fun buffsTeaser(): Lock? {
        if (ClientResearch.buffs.unlocked) return null
        return buffsTeaserMemo.of(ClientResearch.defs, Format.locale) {
            Lock(tr("kami_claims.buffs.lock.label"), tr("kami_claims.buffs.lock.how", ClientResearch.node("buffs:buffs_view")?.level ?: 15))
        }
    }

    fun tokenName(id: String) = tr("kami_claims.token.$id")

    fun raise(cap: Capacity): Lock? {
        fresh()
        if (cap in raises) return raises[cap]
        return computeRaise(cap).also { raises[cap] = it }
    }

    fun rank(target: Rank, current: Rank): Lock? = when {
        target == current -> null
        target == Rank.CHANCELLOR -> feature("role:chancellor")
        target == Rank.OFFICER -> full(Capacity.OFFICERS)
        else -> null
    }

    fun full(cap: Capacity): Lock? {
        fresh()
        if (cap in fulls) return fulls[cap]
        val state = ClientResearch.state
        val lock = if (state.used(cap) < state.max(cap)) null else Lock(tr("kami_libs.format.used", state.used(cap), state.max(cap)), raise(cap)?.how)
        return lock.also { fulls[cap] = it }
    }

    private fun capacityRewards(level: LevelView, cap: Capacity) = level.rewards.filter { it.kind == "capacity" && it.id.equals(cap.id, ignoreCase = true) }

    private fun nextRaise(cap: Capacity): LevelView? =
        ClientResearch.defs.levels.firstOrNull { it.level > ClientResearch.state.level && capacityRewards(it, cap).isNotEmpty() }

    private fun computeRaise(cap: Capacity): Lock? {
        val next = nextRaise(cap) ?: return null
        return Lock.raise(next.level, ClientResearch.state.max(cap) + capacityRewards(next, cap).sumOf { it.count })
    }

    fun unlock(cap: Capacity, what: String): Lock? {
        val state = ClientResearch.state
        if (state.country.isEmpty() || state.max(cap) > 0 || state.used(cap) > 0) return null
        return nextRaise(cap)?.let { Lock.level(it.level, what) }
    }
}

fun Ui.capacityRow(stack: Stack, cap: Capacity) {
    val state = ClientResearch.state
    capacityRow(stack, ResearchLook.capacity(cap.id), state.used(cap), state.max(cap), ClientLocks.raise(cap), "capacity:${cap.name}")
}

fun Ui.capacityLine(r: Rect, cap: Capacity) {
    val state = ClientResearch.state
    capacityLine(r, ResearchLook.capacity(cap.id), state.used(cap), state.max(cap), ClientLocks.raise(cap), "capacity-line:${cap.name}")
}

class TokenOffer(val text: String, val severity: Severity, val free: Boolean, val affordable: Boolean)

fun tokenOffer(id: String): TokenOffer {
    val held = ClientResearch.tokens(id)
    val cost = ClientResearch.tokenCost(id)
    val treasury = ClientResearch.state.treasury
    return when {
        held > 0 -> TokenOffer(tr("kami_claims.token.free", ClientLocks.tokenName(id), held), Severity.SUCCESS, true, true)
        treasury >= cost -> TokenOffer(tr("kami_claims.token.cost", Format.money(cost)), Severity.NEUTRAL, false, true)
        else -> TokenOffer(tr("kami_claims.token.short", Format.money(cost), Format.money(cost - treasury)), Severity.DANGER, false, false)
    }
}
