package kami.claims.client.app

import java.util.IdentityHashMap
import java.util.Locale
import kami.libs.ui.style.Format
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.text.tr

object Tenure {
    const val MOVING = "moving_out"

    fun label(state: String) = when (state) {
        "active" -> tr("kami_claims.home.state.active")
        "removed" -> tr("kami_claims.home.state.removed")
        MOVING -> tr("kami_claims.home.state.moving_out")
        else -> tr("kami_claims.home.state.free")
    }

    fun severity(state: String, debt: Long) = when {
        state == MOVING -> Severity.DANGER
        state == "removed" || debt > 0 -> Severity.WARNING
        state == "active" -> Severity.SUCCESS
        else -> Severity.NEUTRAL
    }

    fun category(category: String) = if (category.isEmpty()) "" else tr("kami_claims.claimant.$category")

    fun color(state: String, debt: Long) = when {
        state == MOVING -> Palette.danger
        debt > 0 -> Palette.warning
        else -> Palette.textSecondary
    }

    fun hint(state: String) = when (state) {
        MOVING -> tr("kami_claims.home.moving.hint")
        "removed" -> tr("kami_claims.home.removed.hint")
        else -> null
    }

    fun countdown(until: Long, now: Long = System.currentTimeMillis()) = tr("kami_claims.home.countdown", Format.duration(until - now))

    private val notes = IdentityHashMap<Any, String>()
    private var stamp = 0L
    private var noteLocale: Locale? = null

    fun note(key: Any, state: String, until: Long, debt: Long): String {
        val minute = System.currentTimeMillis() / 60_000
        if (minute != stamp || noteLocale !== Format.locale) {
            notes.clear()
            stamp = minute
            noteLocale = Format.locale
        }
        return notes.getOrPut(key) {
            when {
                state == MOVING && until > 0 -> countdown(until)
                debt > 0 -> tr("kami_claims.home.debt", Format.money(debt))
                else -> ""
            }
        }
    }
}
