package kami.essentials.client.admin

import kami.libs.ui.style.Draw
import kami.libs.ui.style.Palette
import kami.libs.ui.text.partyLabel
import kami.libs.ui.widget.Column
import kami.libs.ui.widget.PlayerNames
import kami.libs.ui.widget.avatar
import kami.libs.ui.widget.secret
import net.minecraft.client.Minecraft
import java.util.UUID

internal object AdminData {
    private val names = HashMap<String, String>()

    fun remember(uuid: String, name: String) { names[uuid] = name }

    fun clear() = names.clear()

    fun known(uuid: String): String? = names[uuid]
        ?: runCatching { UUID.fromString(uuid) }.getOrNull()?.let { Minecraft.getInstance().connection?.getPlayerInfo(it)?.profile?.name }

    fun name(uuid: String): String = known(uuid) ?: uuid.take(8)

    fun uuidIn(key: String): String? = key.removePrefix("p:").takeIf { it.length == 36 && runCatching { UUID.fromString(it) }.isSuccess }

    fun uuidOf(name: String): String? = name.takeIf { it.isNotEmpty() }?.let { n ->
        names.entries.firstOrNull { it.value.equals(n, true) }?.key ?: PlayerNames.onlineIds().entries.firstOrNull { it.key.equals(n, true) }?.value?.toString()
    }
}

internal fun <T> playerColumn(title: String, uuid: (T) -> String, name: (T) -> String, online: (T) -> Boolean? = { null }) =
    Column<T>(title, -1, sort = compareBy<T> { name(it).lowercase() }) { _, r, row ->
        avatar(uuid(row), r.x, r.y + (r.h - 10) / 2, 10, online(row))
        Draw.text(g, Draw.fit(name(row), r.w - 14), r.x + 14, r.y + (r.h - 8) / 2, Palette.text)
    }

internal fun party(key: String) = partyLabel(key) { AdminData.name(it.toString()) }

internal fun <T> whoColumn(title: String, width: Int, key: (T) -> String?) =
    Column<T>(title, width, sort = compareBy<T> { key(it)?.let(::party).orEmpty().lowercase() }) { _, r, row ->
        val k = key(row)?.takeIf { it.isNotEmpty() } ?: return@Column
        val id = AdminData.uuidIn(k)
        val name = id?.let(AdminData::known)
        if (id != null && name == null) secret(r, id, "who:$id")
        else Draw.text(g, Draw.fit(name ?: party(k), r.w), r.x, r.y + (r.h - 8) / 2, Palette.text)
    }

internal const val NARROW_W = 560
