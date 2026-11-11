package kami.claims.client.app.pages

import kami.claims.client.app.Vocabulary
import kami.claims.client.app.permsOf
import kami.claims.gov.Perm
import kami.claims.net.Info
import kami.claims.net.RankLine
import kami.libs.ui.text.tr
import kami.libs.ui.widget.DiffLine
import kami.libs.ui.widget.NumberState
import kami.libs.ui.widget.TextState
import kami.libs.ui.widget.diffOf

class RankDraft(private var base: RankLine) {
    var perms = base.perms.toSet()
    val name = TextState(base.name)
    var color = base.color
    val plots = NumberState(base.plots.toLong())

    fun rebase(line: RankLine) {
        if (name.text.trim() == base.name && line.name != base.name) name.set(line.name)
        if (color == base.color) color = line.color
        if (perms == base.perms.toSet()) perms = line.perms.toSet()
        if (plots.value == base.plots.toLong()) plots.sync(line.plots.toLong())
        base = line
    }

    fun changed(line: RankLine) = perms != line.perms.toSet() || identityChanged(line) || plots.value != line.plots.toLong()

    fun identityChanged(line: RankLine) = name.text.trim() != line.name || color != line.color
}

class RankDrafts {
    private val ranks = HashMap<String, RankDraft>()
    private val groups = HashMap<String, Set<String>>()

    fun rank(line: RankLine) = ranks.getOrPut(line.id) { RankDraft(line) }

    fun group(key: String, current: Set<String>) = groups[key] ?: current

    fun stagedGroup(key: String) = groups.getValue(key)

    fun setGroup(key: String, perms: Set<String>) {
        groups[key] = perms
    }

    fun prune(info: Info, selected: String) {
        info.ranks.forEach { line -> ranks[line.id]?.rebase(line) }
        ranks.entries.removeIf { (id, draft) -> info.ranks.firstOrNull { it.id == id }?.let { id != selected && !draft.changed(it) } != false }
        groups.entries.removeIf { (key, perms) -> perms == info.groups[key].orEmpty().toSet() }
    }

    fun dirtyRanks(info: Info) = info.ranks.mapNotNull { line -> ranks[line.id]?.takeIf { it.changed(line) }?.let { line to it } }

    fun dirtyGroups() = groups.keys.toList()

    fun grantsPerms(info: Info) = dirtyRanks(info).any { (line, draft) -> (draft.perms - line.perms.toSet()).isNotEmpty() } || groups.any { (key, perms) -> (perms - info.groups[key].orEmpty().toSet()).isNotEmpty() }

    fun count(info: Info) = dirtyRanks(info).size + groups.size

    fun clear() {
        ranks.clear()
        groups.clear()
    }

    fun lines(info: Info): List<DiffLine> = dirtyRanks(info).flatMap { (line, draft) -> rankLines(line, draft) } + groups.flatMap { (key, perms) -> permLines(groupLabel(key), permsOf(info.groups[key].orEmpty()), permsOf(perms)) }

    private fun rankLines(line: RankLine, draft: RankDraft): List<DiffLine> =
        permLines(line.name, permsOf(line.perms), permsOf(draft.perms)) +
            (if (draft.name.text.trim() != line.name) DiffLine.change("${line.name}: ${tr("kami_claims.ranks.field.name")}", line.name, draft.name.text.trim()) else emptyList()) +
            (if (draft.color != line.color) DiffLine.change("${line.name}: ${tr("kami_claims.ranks.field.color")}", "#%06X".format(line.color), "#%06X".format(draft.color)) else emptyList()) +
            (if (draft.plots.value != line.plots.toLong()) DiffLine.change("${line.name}: ${tr("kami_claims.ranks.field.plots")}", line.plots.toString(), draft.plots.value.toString()) else emptyList())

    private fun permLines(owner: String, before: Set<Perm>, after: Set<Perm>) =
        diffOf(before, after, { "$owner: ${Vocabulary.perm(it).label}" }, { Vocabulary.perm(it).description })

    private fun groupLabel(key: String) = tr("kami_claims.group.${key.lowercase()}")
}
