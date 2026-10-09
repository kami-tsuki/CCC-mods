package kami.essentials.client.admin

import kami.libs.util.toUuidOrNull
import kami.libs.economy.Flow as MoneyFlow
import kami.libs.ui.app.SELECT_W
import kami.libs.ui.app.segmentWidth
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Palette
import kami.libs.ui.text.partyLabel
import kami.libs.ui.style.Severity
import kami.libs.ui.widget.Column
import kami.libs.ui.widget.cellText
import kami.libs.ui.widget.Option
import kami.libs.ui.widget.property
import kami.libs.ui.widget.segmented
import kami.libs.ui.widget.select
import kami.libs.ui.widget.textOrder
import kami.libs.ui.widget.PlayerNames
import kami.libs.ui.widget.avatar
import kami.libs.ui.widget.secret
import net.minecraft.client.Minecraft

internal object AdminData {
    private val names = HashMap<String, String>()

    fun remember(uuid: String, name: String) { names[uuid] = name }

    fun clear() = names.clear()

    fun known(uuid: String): String? = names[uuid]
        ?: uuid.toUuidOrNull()?.let { Minecraft.getInstance().connection?.getPlayerInfo(it)?.profile?.name }

    fun name(uuid: String): String = known(uuid) ?: uuid.take(8)

    fun uuidIn(key: String): String? = key.removePrefix("p:").takeIf { it.length == 36 && it.toUuidOrNull() != null }

    fun uuidOf(name: String): String? = name.takeIf { it.isNotEmpty() }?.let { n ->
        names.entries.firstOrNull { it.value.equals(n, true) }?.key ?: PlayerNames.onlineIds().entries.firstOrNull { it.key.equals(n, true) }?.value?.toString()
    }
}

internal fun <T> playerColumn(title: String, uuid: (T) -> String, name: (T) -> String, online: (T) -> Boolean? = { null }) =
    Column<T>(title, -1, sort = textOrder<T> { name(it) }) { _, r, row ->
        avatar(uuid(row), r.x, r.y + (r.h - 10) / 2, 10, online(row))
        cellText(Rect(r.x + 14, r.y, r.w - 14, r.h), name(row))
    }

internal fun party(key: String) = partyLabel(key) { AdminData.name(it.toString()) }

internal fun <T> whoColumn(title: String, width: Int, key: (T) -> String?) =
    Column<T>(title, width, sort = textOrder<T> { key(it)?.let(::party).orEmpty() }) { _, r, row ->
        val k = key(row)?.takeIf { it.isNotEmpty() } ?: return@Column
        val id = AdminData.uuidIn(k)
        val name = id?.let(AdminData::known)
        if (id != null && name == null) secret(r, id, "who:$id")
        else cellText(r, name ?: party(k))
    }

internal const val NARROW_W = 560

internal const val PROP_H = 11
internal const val INFO_H = 96

internal fun Ui.prop(f: Flow, label: String, value: String, color: Int = Palette.text, copy: Boolean = false) =
    property(f.take(PROP_H), label, value, color, copy, key = "prop:$label:$value")

internal fun amountColor(v: Long) = if (v > 0) Palette.success else if (v < 0) Palette.danger else Palette.textMuted

internal fun flowColor(f: MoneyFlow) = Palette.chart[f.ordinal % Palette.chart.size]

internal fun amountSeverity(amount: Long) = if (amount >= 0) Severity.SUCCESS else Severity.DANGER

internal fun isAmountChar(c: Char) = c.isDigit() || c in "kmbKMB.,"

internal fun <T> Ui.choice(row: Rect, options: List<Option<T>>, selected: T, key: Any): T? {
    val width = segmentWidth(options)
    return if (width > row.w) select(row.right(SELECT_W), options, selected, key = key) else segmented(row.right(width), options, selected, key = key)
}
