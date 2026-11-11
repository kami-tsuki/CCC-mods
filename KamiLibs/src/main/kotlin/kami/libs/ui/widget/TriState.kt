package kami.libs.ui.widget

import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui

enum class TriState { OFF, PARTIAL, ON }

fun triState(checked: Int, total: Int) = when {
    checked <= 0 || total <= 0 -> TriState.OFF
    checked >= total -> TriState.ON
    else -> TriState.PARTIAL
}

fun Ui.triStateToggle(r: Rect, label: String, state: TriState, enabled: Boolean = true, disabledReason: String? = null, tip: String? = null, key: Any? = null): Boolean? =
    checkbox(r, label, when (state) { TriState.ON -> true; TriState.OFF -> false; TriState.PARTIAL -> null }, enabled, disabledReason, tip, key ?: autoKey("tristate:$label"))
