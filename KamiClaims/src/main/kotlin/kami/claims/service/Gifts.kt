package kami.claims.service

import kami.claims.Names
import kami.claims.economy.Bank
import kami.claims.now
import kami.claims.social.Mail
import kami.libs.chat.Tone
import kami.libs.economy.Flow
import kami.libs.economy.MoneyGuard
import kami.libs.economy.PlayerParty
import kami.libs.economy.ServerBank
import kami.libs.text.Phrase
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

object Gifts {
    const val TTL_MS = 15 * 60_000L
    private const val COOLDOWN_MS = 10_000L
    private const val MAX_OPEN = 5

    private class Gift(val amount: Int, val at: Long)

    private val open = HashMap<Pair<UUID, UUID>, Gift>()
    private val lastSent = HashMap<UUID, Long>()

    fun reset() {
        open.clear()
        lastSent.clear()
    }

    fun pending(sender: UUID, receiver: UUID): Int = live(Pair(sender, receiver))?.amount ?: 0

    fun send(p: ServerPlayer, receiver: UUID, amount: Int): Phrase {
        if (receiver == p.uuid) throw Fail("kami_claims.error.gift.self")
        if (p.server.playerList.getPlayer(receiver) == null && p.server.profileCache?.get(receiver)?.isPresent != true) throw Fail("kami_claims.error.unknown_player")
        val t = now()
        if (t - (lastSent[p.uuid] ?: 0L) < COOLDOWN_MS) throw Fail("kami_claims.error.gift.cooldown")
        open.values.removeIf { t - it.at > TTL_MS }
        lastSent.values.removeIf { t - it > COOLDOWN_MS }
        if (open.keys.count { it.first == p.uuid } >= MAX_OPEN) throw Fail("kami_claims.error.gift.pending")
        if (amount <= 0) throw Fail("kami_claims.money.disabled.range", Words.money(Bank.funds(p.uuid)))
        if (Bank.funds(p.uuid) < amount + open.filterKeys { it.first == p.uuid }.values.sumOf { it.amount.toLong() }) throw Fail("kami_claims.loans.reason.funds", Words.money(amount))
        if (live(Pair(p.uuid, receiver)) != null) throw Fail("kami_claims.error.gift.pending")
        lastSent[p.uuid] = t
        open[Pair(p.uuid, receiver)] = Gift(amount, t)
        val name = Names.of(p.server, receiver.toString())
        Mail.direct(receiver.toString(), Phrase.of("kami_claims.mail.gift.offer", Words.v(p.name.string), Words.money(amount)))
        return Phrase.of("kami_claims.done.gift.sent", Words.money(amount), Words.v(name))
    }

    fun accept(p: ServerPlayer, from: UUID): Phrase {
        val gift = take(Pair(from, p.uuid))
        val sender = Names.of(p.server, from.toString())
        val moved = MoneyGuard.explained("transfer") {
            if (!Bank.take(from, gift.amount, Flow.TRANSFER, PlayerParty(p.uuid).key)) return@explained false
            if (!Bank.give(p.uuid, gift.amount, Flow.TRANSFER, PlayerParty(from).key) && !Bank.give(from, gift.amount, Flow.TRANSFER, PlayerParty(p.uuid).key)) {
                ServerBank.collect(PlayerParty(from), gift.amount.toLong(), Flow.TRANSFER, "gift refund failed")
            }
            true
        }
        if (!moved) {
            Mail.direct(from.toString(), Phrase.of("kami_claims.mail.gift.failed", Words.v(p.name.string), Words.money(gift.amount)), Tone.BAD)
            throw Fail("kami_claims.error.gift.funds", Words.v(sender))
        }
        Mail.direct(from.toString(), Phrase.of("kami_claims.mail.gift.accepted", Words.v(p.name.string), Words.money(gift.amount)), Tone.OK)
        return Phrase.of("kami_claims.done.gift.accepted", Words.money(gift.amount), Words.v(sender))
    }

    fun decline(p: ServerPlayer, from: UUID): Phrase {
        val gift = take(Pair(from, p.uuid))
        Mail.direct(from.toString(), Phrase.of("kami_claims.mail.gift.declined", Words.v(p.name.string), Words.money(gift.amount)), Tone.BAD)
        return Phrase.of("kami_claims.done.gift.declined")
    }

    fun cancel(p: ServerPlayer, receiver: UUID): Phrase {
        take(Pair(p.uuid, receiver))
        return Phrase.of("kami_claims.done.gift.cancelled")
    }

    private fun take(key: Pair<UUID, UUID>): Gift = live(key)?.also { open.remove(key) } ?: throw Fail("kami_claims.error.gift.none")

    private fun live(key: Pair<UUID, UUID>): Gift? {
        val gift = open[key] ?: return null
        if (now() - gift.at <= TTL_MS) return gift
        open.remove(key)
        return null
    }
}
