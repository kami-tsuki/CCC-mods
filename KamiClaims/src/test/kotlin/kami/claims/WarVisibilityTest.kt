package kami.claims

import kami.claims.war.Entry
import kami.claims.war.NameTagTeams
import kami.claims.war.Pos
import kami.claims.war.PositionFilter
import kami.claims.war.Seen
import kami.claims.war.Selection
import kami.claims.war.Stakes
import kami.claims.war.TeamStep
import kami.claims.war.Visibility
import kami.claims.war.WarPhase
import kami.claims.war.Wars
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WarVisibilityTest : WithDb() {
    private val day get() = Config.s.dayMillis

    private fun country(name: String): Country {
        val c = Country(name, created = now() - 10 * day, level = 100, treasury = 100_000)
        Realm.data.countries[c.id] = c
        c.members["${name}_p"] = Member(Rank.PRESIDENT, since = now() - 1000)
        return c
    }

    private val ids = List(8) { UUID(0, it.toLong() + 1) }

    private fun pos(i: Int, country: String?, x: Double = 0.0, dim: String = "minecraft:overworld") = Pos(ids[i], country, dim, x, 64.0, 0.0)

    private fun enemy(a: String, b: String) = setOf(a, b) == setOf("a", "b")

    private val parents = mapOf("p" to "a", "q" to "a")

    private fun family(own: String, target: String) = parents[target] == own

    private fun select(viewer: Pos, targets: List<Pos>, opAll: Boolean = false, share: Boolean = true, hide: Boolean = true, cap: Int = PositionFilter.CAP, enemies: (String, String) -> Boolean = ::enemy) =
        PositionFilter.select(viewer, opAll, targets, share, hide, enemies, ::family, cap)

    @Test
    fun matrix() {
        assertEquals(Seen.ALWAYS, Visibility.see(true, false, true))
        assertEquals(Seen.ALWAYS, Visibility.see(false, true, false))
        assertEquals(Seen.HIDDEN, Visibility.see(false, false, true))
        assertEquals(Seen.RANGE, Visibility.see(false, false, false))
    }

    @Test
    fun warringFromDeclarationToEnd() {
        Realm.reset(Data())
        val a = country("a")
        val b = country("b")
        val c = country("c")
        assertFalse(Visibility.warring("a"))
        Wars.declare(a, b, stakes = Stakes(money = 100))
        val war = Wars.of(a)!!
        assertEquals(WarPhase.DECLARATION, war.phase)
        assertTrue(Visibility.warring("a") && Visibility.warring("b"))
        assertFalse(Visibility.warring(c.id))
        war.phase = WarPhase.ENDED
        assertFalse(Visibility.warring("a"))
    }

    @Test
    fun membersSeeEachOtherOutOfRangeEnemiesGetNothing() {
        val viewer = pos(0, "a")
        val targets = listOf(viewer, pos(1, "a", 500.0), pos(2, "b", 500.0), pos(3, "c", 500.0), pos(4, "a", 10.0))
        val r = select(viewer, targets)
        assertEquals(listOf(ids[4], ids[1]), r.entries.map { it.id })
        assertEquals(setOf(ids[2]), r.hidden)
        assertEquals(emptyList(), select(pos(2, "b"), targets.filter { it.country == "a" }).entries)
    }

    @Test
    fun overlordSeesProvinceMembersButNotTheOtherWay() {
        val overlord = pos(0, "a")
        val members = listOf(pos(1, "p", 500.0), pos(2, "q", 5.0), pos(3, "r", 5.0))
        assertEquals(listOf(ids[2], ids[1]), select(overlord, members).entries.map { it.id })
        assertTrue(select(pos(1, "p"), listOf(pos(0, "a", 500.0), pos(2, "q", 500.0))).entries.isEmpty())
    }

    @Test
    fun enemyBeatsFamily() {
        val overlord = pos(0, "a")
        val rebel = pos(1, "p", 300.0)
        val r = select(overlord, listOf(rebel), enemies = { x, y -> setOf(x, y) == setOf("a", "p") })
        assertTrue(r.entries.isEmpty())
        assertEquals(setOf(ids[1]), r.hidden)
    }

    @Test
    fun otherDimensionIsNotInRange() {
        val viewer = pos(0, "a")
        assertEquals(1, select(viewer, listOf(pos(1, "a", 1.0, "minecraft:the_nether"))).entries.size)
    }

    @Test
    fun opViewerGetsEveryoneAndHidesNothing() {
        val viewer = pos(0, "a")
        val targets = (1..5).map { pos(it, if (it % 2 == 0) "b" else null, 300.0 + it) }
        val r = select(viewer, targets, opAll = true)
        assertEquals(5, r.entries.size)
        assertTrue(r.hidden.isEmpty())
    }

    @Test
    fun togglesAndCountrylessViewer() {
        val viewer = pos(0, "a")
        val targets = listOf(pos(1, "a", 500.0), pos(2, "b", 500.0))
        assertTrue(select(viewer, targets, share = false).entries.isEmpty())
        assertTrue(select(viewer, targets, hide = false).hidden.isEmpty())
        val r = select(pos(0, null), targets)
        assertTrue(r.entries.isEmpty() && r.hidden.isEmpty())
    }

    @Test
    fun capKeepsNearest() {
        val viewer = pos(0, "a")
        val targets = (1..7).map { pos(it, "a", 100.0 + (8 - it)) }
        val r = select(viewer, targets, cap = 3)
        assertEquals(listOf(ids[7], ids[6], ids[5]), r.entries.map { it.id })
    }

    @Test
    fun deltaSuppression() {
        fun sel(x: Int, hidden: Set<UUID> = emptySet()) = Selection(listOf(Entry(ids[1], "d", x, 64, 0)), hidden)
        assertFalse(PositionFilter.changed(null, Selection(emptyList(), emptySet())))
        assertTrue(PositionFilter.changed(null, sel(0)))
        assertFalse(PositionFilter.changed(sel(0), sel(1)))
        assertTrue(PositionFilter.changed(sel(0), sel(2)))
        assertTrue(PositionFilter.changed(sel(0), sel(0, setOf(ids[2]))))
        assertTrue(PositionFilter.changed(sel(0), Selection(emptyList(), emptySet())))
    }

    @Test
    fun teamDecision() {
        val own = NameTagTeams.teamName("a")
        assertEquals(TeamStep.JOIN, NameTagTeams.decide(own, null))
        assertEquals(TeamStep.KEEP, NameTagTeams.decide(null, null))
        assertEquals(TeamStep.KEEP, NameTagTeams.decide(own, own))
        assertEquals(TeamStep.LEAVE, NameTagTeams.decide(null, own))
        assertEquals(TeamStep.JOIN, NameTagTeams.decide(own, NameTagTeams.teamName("b")))
        assertEquals(TeamStep.SKIP, NameTagTeams.decide(own, "red"))
        assertEquals(TeamStep.SKIP, NameTagTeams.decide(null, "red"))
    }

    @Test
    fun teamNamesStayShortAndUnique() {
        assertEquals("kc_aurelia", NameTagTeams.teamName("aurelia"))
        val a = NameTagTeams.teamName("a_very_long_country_name_one")
        val b = NameTagTeams.teamName("a_very_long_country_name_two")
        assertTrue(a.length <= 16 && b.length <= 16 && a != b)
    }
}
