package kami.libs.ui.app

import kami.libs.admin.Admin
import kami.libs.admin.AdminPerms
import kami.libs.admin.AdminReply
import kami.libs.admin.Page as DataPage
import kami.libs.ui.AutoRefresh
import kami.libs.net.snapshotJson
import kami.libs.text.Phrase
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.text.tr
import kami.libs.ui.widget.fitText
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.Column
import kami.libs.ui.widget.emptyState
import kami.libs.ui.widget.TableEvents
import kami.libs.ui.widget.TableState
import kami.libs.ui.widget.pager
import kami.libs.ui.widget.staleRows
import kami.libs.ui.widget.TABLE_HEADER_H
import kami.libs.ui.widget.TABLE_ROW_H
import kami.libs.ui.widget.table
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.max

object AdminKit {
    var epoch = 0
    private const val PENDING_MS = 15_000L
    private val inFlight = HashMap<Pair<String, List<String>>, Long>()

    internal fun begin(key: Pair<String, List<String>>): Boolean {
        if (pending(key)) return false
        inFlight[key] = System.currentTimeMillis()
        return true
    }

    internal fun end(key: Pair<String, List<String>>) { inFlight -= key }

    internal fun pending(key: Pair<String, List<String>>) = inFlight[key]?.let { System.currentTimeMillis() - it < PENDING_MS } == true
}

fun adminPending(name: String, args: List<String>): Boolean = AdminKit.pending(name to args)

val adminManager get() = Admin.level >= AdminPerms.Level.MANAGE

fun lockUnless(ok: Boolean) = if (ok) null else tr("kami_libs.admin.need_manage")

fun AdminReply.message(): String =
    tr(error ?: "kami_libs.admin.error", *(data as? JsonArray)?.map { (it as? JsonPrimitive)?.content.orEmpty().let { a -> Phrase.parse(a)?.resolve() ?: a } }?.toTypedArray().orEmpty())

fun <T> AdminReply.decode(serializer: KSerializer<T>): T? =
    data?.takeIf { ok }?.let { runCatching { snapshotJson.decodeFromJsonElement(serializer, it) }.getOrNull() }

fun KamiApp.adminAct(name: String, args: List<String>, reason: String, onDone: (AdminReply) -> Unit = {}) {
    val key = name to args
    if (!AdminKit.begin(key)) return
    Admin.request(name, args, reason) { r ->
        AdminKit.end(key)
        if (r.ok) {
            AdminKit.epoch++
            toast(Severity.SUCCESS, tr("kami_libs.common.done"))
            onDone(r)
        } else toast(Severity.DANGER, r.message())
    }
}

fun KamiApp.adminView(name: String, args: List<String>) = Admin.request(name, args) { r -> if (!r.ok) toast(Severity.DANGER, r.message()) }

fun KamiApp.adminConfirmAct(title: String, name: String, args: List<String>, danger: Boolean = false) =
    reasonDialog({ open(it) }, title, danger) { reason, _, _ -> adminAct(name, args, reason) }

private const val REMOTE_CACHE = 16
private const val RETRY_MS = 5000L
private const val FILTER_DELAY_MS = 300L

class Remote<T>(
    private val name: String, private val serializer: KSerializer<T>, private val refreshMs: Long = 0,
    private val send: (String, List<String>, (AdminReply) -> Unit) -> Unit = { n, a, done -> Admin.request(n, a, onReply = done) },
    private val args: () -> List<String> = { emptyList() }
) {
    private class Slot<V> {
        var value: V? = null
        var error: String? = null
        var epoch = -1
        var at = 0L
        var pending = false
    }

    private val slots = object : LinkedHashMap<List<String>, Slot<T>>(REMOTE_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<String>, Slot<T>>?) = size > REMOTE_CACHE
    }
    private var current: Slot<T>? = null

    var latest: T? = null
        private set
    val data: T? get() = current?.value
    val error: String? get() = current?.error
    val loading: Boolean get() = current?.pending == true
    val stale: Boolean get() = current?.let { it.value != null && (it.pending || it.epoch != AdminKit.epoch) } == true

    fun reload() = slots.values.forEach { it.epoch = -1 }

    fun poll() { current = fetch(args()) }

    fun prefetch(args: List<String>) { fetch(args) }

    private fun fetch(key: List<String>): Slot<T> {
        val s = slots.getOrPut(key) { Slot() }
        val now = System.currentTimeMillis()
        val timed = refreshMs > 0 && AutoRefresh.active && now - s.at >= max(refreshMs, AutoRefresh.PERIOD)
        val fresh = s.epoch == AdminKit.epoch && !timed && (s.error == null || now - s.at < RETRY_MS)
        if (s.pending || fresh) return s
        s.epoch = AdminKit.epoch
        s.at = now
        s.pending = true
        send(name, key) { r ->
            s.pending = false
            s.at = System.currentTimeMillis()
            val v = r.decode(serializer)
            if (v != null) {
                s.value = v
                latest = v
            }
            s.error = if (v != null) null else if (r.ok) tr("kami_libs.admin.error") else r.message()
        }
        return s
    }
}

class Paged<T>(name: String, item: KSerializer<T>, private val filter: () -> List<String>) {
    var index = 0
    private var last: List<String>? = null
    private var typed: List<String>? = null
    private var typedAt = 0L
    private var viewed: List<String>? = null
    val remote = Remote(name, DataPage.serializer(item)) {
        val f = filter()
        val now = System.currentTimeMillis()
        if (f != typed) { typed = f; typedAt = now }
        if (f != last && (last == null || now - typedAt >= FILTER_DELAY_MS)) {
            if (last != null) index = 0
            last = f
        }
        last.orEmpty() + index.toString()
    }
    private val shown get() = remote.data ?: remote.latest
    val stale get() = remote.data == null && remote.latest != null
    val rows get() = shown?.rows.orEmpty()
    val total get() = shown?.total ?: 0L
    val pages get() = shown?.let { ((it.total + it.size - 1) / it.size.coerceAtLeast(1)).toInt().coerceAtLeast(1) } ?: 1

    internal fun moved(): Boolean {
        val v = last.orEmpty() + index.toString()
        return (viewed != null && v != viewed).also { viewed = v }
    }
}

fun <T> Ui.pagedTable(
    r: Rect, p: Paged<T>, columns: List<Column<T>>, state: TableState<T>, keyOf: (T) -> Any, emptyText: String, key: String,
    severity: (T) -> Severity? = { null }, rowHeight: Int = TABLE_ROW_H, multi: Boolean = false, serverSort: Set<Int> = emptySet()
): TableEvents<T> {
    p.remote.poll()
    if (p.remote.data != null && !p.remote.loading && p.index >= p.pages) p.index = max(0, p.pages - 1)
    if (p.moved()) state.clear()
    val cols = columns.mapIndexed { i, c -> if (c.sort == null || i in serverSort) c else Column(c.title, c.width, c.align, null, c.tip, c.cell) }
    val foot = r.bottom(CONTROL_H)
    val area = r.dropBottom(CONTROL_H, 4)
    val failed = p.remote.error != null && p.rows.isEmpty() && !p.remote.loading
    val events = table(area, cols, p.rows, state, keyOf, multi, rowHeight, severity, if (failed) "" else emptyText, key = key, loading = p.remote.loading)
    if (failed) remoteFailed(area.dropTop(TABLE_HEADER_H), p.remote, key)
    staleRows(area, p.stale && p.rows.isNotEmpty())
    fitText(foot.x, foot.centerY - 4, foot.w - 116, tr("kami_libs.admin.total", Format.number(p.total)), Palette.textMuted)
    pager(foot.right(110), p.index, p.pages, key = "$key:pager")?.let { p.index = it }
    return events
}

fun Ui.remoteFailed(r: Rect, remote: Remote<*>, key: String) {
    if (emptyState(r, tr("kami_libs.admin.load_failed"), remote.error.orEmpty(), action = tr("kami_libs.common.retry"), key = "$key:retry")) remote.reload()
}

fun <T> Ui.remoteTable(
    r: Rect, remote: Remote<*>, columns: List<Column<T>>, rows: List<T>, state: TableState<T>, keyOf: (T) -> Any, emptyText: String, key: String,
    severity: (T) -> Severity? = { null }, rowHeight: Int = TABLE_ROW_H
): TableEvents<T> {
    val failed = remote.error != null && remote.data == null && !remote.loading
    val events = table(r, columns, rows, state, keyOf, rowHeight = rowHeight, severity = severity, emptyText = if (failed) "" else emptyText, key = key,
        loading = remote.loading || remote.data == null && remote.error == null)
    if (failed) remoteFailed(r.dropTop(TABLE_HEADER_H), remote, key)
    return events
}
