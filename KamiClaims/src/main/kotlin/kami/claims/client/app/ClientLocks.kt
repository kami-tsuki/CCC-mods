package kami.claims.client.app

import kami.claims.client.app.pages.ResearchLook
import kami.claims.client.store.ClientResearch
import kami.claims.net.LevelView
import kami.claims.Rank
import kami.claims.research.Capacity
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

    fun featureName(id: String) =
        if (id.startsWith(CLAIM_TYPE)) tr("kami_claims.feature.claim_type", Vocabulary.type(id.removePrefix(CLAIM_TYPE)).label)
        else tr("kami_claims.feature.${id.replace(':', '.')}")

    private val features = HashMap<String, Lock?>()
    private val raises = HashMap<Capacity, Lock?>()
    private var seenState: Any? = null
    private var seenDefs: Any? = null

    private fun fresh() {
        if (seenState === ClientResearch.state && seenDefs === ClientResearch.defs) return
        seenState = ClientResearch.state
        seenDefs = ClientResearch.defs
        features.clear()
        raises.clear()
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
        val state = ClientResearch.state
        if (state.used(cap) < state.max(cap)) return null
        return Lock(tr("kami_claims.lock.slots", state.used(cap), state.max(cap)), raise(cap)?.how)
    }

    private fun nextRaise(cap: Capacity): LevelView? {
        val key = cap.name.replace("_", "").lowercase()
        return ClientResearch.defs.levels.firstOrNull { level -> level.level > ClientResearch.state.level && level.rewards.any { it.kind == "capacity" && it.id.lowercase() == key } }
    }

    private fun computeRaise(cap: Capacity): Lock? {
        val next = nextRaise(cap) ?: return null
        val key = cap.name.replace("_", "").lowercase()
        val gain = next.rewards.filter { it.kind == "capacity" && it.id.lowercase() == key }.sumOf { it.add }
        return Lock.raise(next.level, ClientResearch.state.max(cap) + gain)
    }

    fun unlock(cap: Capacity, what: String): Lock? {
        val state = ClientResearch.state
        if (state.country.isEmpty() || state.max(cap) > 0 || state.used(cap) > 0) return null
        return nextRaise(cap)?.let { Lock.level(it.level, what) }
    }
}

fun Ui.capacityRow(stack: Stack, cap: Capacity) {
    val state = ClientResearch.state
    capacityRow(stack, ResearchLook.capacity(cap.name.lowercase()), state.used(cap), state.max(cap), ClientLocks.raise(cap), "capacity:${cap.name}")
}

fun Ui.capacityLine(r: Rect, cap: Capacity) {
    val state = ClientResearch.state
    capacityLine(r, ResearchLook.capacity(cap.name.lowercase()), state.used(cap), state.max(cap), ClientLocks.raise(cap), "capacity-line:${cap.name}")
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
