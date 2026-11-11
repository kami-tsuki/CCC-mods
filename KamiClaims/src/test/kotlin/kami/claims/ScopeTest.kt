package kami.claims

import kami.claims.gov.PRESIDENT
import kami.claims.service.Scope
import kami.claims.war.War
import kami.claims.war.WarKind
import kami.claims.war.WarSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScopeTest {
    private val none: (String) -> Boolean = { false }

    @Test
    fun opSeesEverything() {
        val s = Scope.compute(null, null, emptySet(), true, none)
        assertTrue(s("x") && s("y"))
    }

    @Test
    fun noCountrySeesNothing() {
        assertFalse(Scope.compute(null, null, setOf("p"), false, none)("p"))
    }

    @Test
    fun overlordSeesProvincesButNotInTheOtherDirection() {
        val top = Scope.compute("kingdom", null, setOf("p1", "p2"), false, none)
        assertTrue(top("kingdom") && top("p1") && top("p2"))
        assertFalse(top("other"))
        val province = Scope.compute("p1", "kingdom", emptySet(), false, none)
        assertTrue(province("p1"))
        assertFalse(province("kingdom") || province("p2"))
    }

    @Test
    fun enemiesLeaveTheScopeButHomeStays() {
        val s = Scope.compute("kingdom", null, setOf("p1", "p2"), false) { it == "p2" || it == "kingdom" }
        assertTrue(s("kingdom") && s("p1"))
        assertFalse(s("p2"))
    }

    @Test
    fun realmBackedScopeDropsWarEnemiesAndRebels() {
        Realm.reset(Data())
        val king = Country("King").also { Realm.data.countries[it.id] = it }
        val rebel = Country("Rebel").also { it.parent = king.id; Realm.data.countries[it.id] = it }
        val loyal = Country("Loyal").also { it.parent = king.id; Realm.data.countries[it.id] = it }
        val foe = Country("Foe").also { Realm.data.countries[it.id] = it }
        king.provinces += listOf(rebel.id, loyal.id)
        king.mixed()
        Realm.join(king, "k", PRESIDENT)
        rebel.mixed()
        Realm.join(rebel, "r", PRESIDENT)
        loyal.mixed()
        Realm.join(loyal, "l", PRESIDENT)
        Realm.data.wars += War("w1", WarKind.REGULAR, WarSide(king.id), WarSide(foe.id), 0L)
        Realm.data.wars += War("w2", WarKind.REGULAR, WarSide(king.id), WarSide(rebel.id), 0L)
        val s = Scope.of("k", false)
        assertTrue(s(king.id) && s(loyal.id))
        assertFalse(s(foe.id) || s(rebel.id))
        assertFalse(Scope.of("l", false)(king.id))
        assertFalse(Scope.of("r", false)(king.id))
        assertEquals(true, Scope.of("nobody", true)(foe.id))
    }
}
