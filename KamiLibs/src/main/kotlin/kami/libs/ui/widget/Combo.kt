package kami.libs.ui.widget

import kami.libs.text.SearchQuery
import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.UiSound
import kami.libs.ui.text.tr
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
import java.util.UUID

private const val COMBO_ROW_H = 12
private const val QUERY_DELAY_MS = 350L
private const val MAX_SUGGESTIONS = 8
private const val MIN_NEAR_LENGTH = 4

private class ComboState {
    var index = 0
    var picked: String? = null
}

private class QueryState {
    var text = ""
    var changedAt = 0L
    var sent = ""
}

fun Ui.comboField(
    r: Rect, state: TextState, options: List<String>, placeholder: String = "", icon: Icon? = null, maxLength: Int = 16, key: String
): FieldResult {
    val combo = remember("combo:$key") { ComboState() }
    var picked = false
    combo.picked?.let { state.set(it); combo.picked = null; picked = true }
    val wasFocused = focused(key)
    val field = textField(r, state, placeholder, icon, maxLength = maxLength, key = key)
    val shown = options.takeIf { (wasFocused || focused(key)) && !(it.size == 1 && it[0].equals(state.text, true)) }.orEmpty()
    if (shown.isNotEmpty()) {
        if (field.changed) combo.index = 0
        if (input.takeKey(GLFW.GLFW_KEY_DOWN) != null) combo.index++
        if (input.takeKey(GLFW.GLFW_KEY_UP) != null) combo.index--
        combo.index = combo.index.coerceIn(0, shown.size - 1)
        if (field.submitted) { combo.picked = shown[combo.index]; UiSound.click() }
        overlay {
            popover(r, r.w, shown.size * COMBO_ROW_H + 4, shadow = 1) { menu ->
                shown.forEachIndexed { i, name ->
                    val row = Rect(menu.x + 2, menu.y + 2 + i * COMBO_ROW_H, menu.w - 4, COMBO_ROW_H)
                    val over = hovering(row)
                    if (over) cursor = Cursor.HAND
                    if (over || i == combo.index) Draw.fill(g, row, Palette.hover)
                    if (i == combo.index) Draw.fill(g, row.left(2), Palette.brass)
                    Draw.text(g, Draw.fit(name, row.w - 8), row.x + 5, row.y + 2, Palette.text)
                    if (pressed(row) != null) { combo.picked = name; UiSound.click() }
                }
            }
        }
    }
    return if (picked) FieldResult(true, field.submitted, field.blurred) else field
}

object PlayerNames {
    fun online(): List<String> =
        Minecraft.getInstance().connection?.onlinePlayers?.map { it.profile.name }.orEmpty()

    fun onlineIds(): Map<String, UUID> =
        Minecraft.getInstance().connection?.onlinePlayers?.associate { it.profile.name to it.profile.id }.orEmpty()

    fun suggest(typed: String, offline: List<String>): List<String> {
        val q = typed.trim()
        val me = Minecraft.getInstance().player?.gameProfile?.name
        val near = q.length >= MIN_NEAR_LENGTH
        val sq = SearchQuery.parse(q)
        val known = online().filter { sq.matches(it) } + offline.filter { it.equals(q, true) || near && sq.matches(it) }
        return known.filter { !it.equals(me, true) }.distinctBy { it.lowercase() }.take(MAX_SUGGESTIONS)
    }
}

private fun Ui.debounce(state: TextState, query: ((String) -> Unit)?, key: String) {
    val q = remember("player:query:$key") { QueryState() }
    if (state.text != q.text) { q.text = state.text; q.changedAt = wallMillis }
    if (query != null && q.text.isNotBlank() && q.text != q.sent && wallMillis - q.changedAt >= QUERY_DELAY_MS) {
        q.sent = q.text
        query(q.text.trim())
    }
}

fun Ui.playerField(
    r: Rect, state: TextState, offline: () -> List<String> = { emptyList() }, query: ((String) -> Unit)? = null,
    placeholder: String = tr("kami_libs.player.placeholder"), key: String = "player"
): FieldResult {
    debounce(state, query, key)
    return comboField(r, state, PlayerNames.suggest(state.text, offline()), placeholder, Icons.PERSON, key = key)
}

fun Ui.partyField(
    r: Rect, state: TextState, countries: () -> Map<String, String>, players: () -> Map<String, UUID> = { emptyMap() }, query: ((String) -> Unit)? = null,
    placeholder: String = tr("kami_libs.party.placeholder"), key: String = "party", onParty: (String?) -> Unit
): FieldResult {
    debounce(state, query, key)
    val known = countries()
    val people = PlayerNames.onlineIds() + players()
    val sq = SearchQuery.parse(state.text.trim())
    val suggestions = (people.keys.filter { sq.matches(it) } + known.keys.filter { sq.matches(it) })
        .distinctBy { it.lowercase() }.take(MAX_SUGGESTIONS)
    val field = comboField(r, state, suggestions, placeholder, Icons.PERSON, maxLength = 32, key = key)
    val name = state.text.trim()
    val party = people.entries.firstOrNull { it.key.equals(name, true) }?.let { "p:${it.value}" }
        ?: known.entries.firstOrNull { it.key.equals(name, true) }?.let { "c:${it.value}" }
    val last = remember("party:last:$key") { arrayOfNulls<String>(1) }
    if (party != last[0]) { last[0] = party; onParty(party) }
    return field
}
