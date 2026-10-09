package kami.claims.service

import kami.claims.Realm
import kami.claims.war.Wars

object Scope {
    fun compute(home: String?, parent: String?, provinces: Set<String>, op: Boolean, enemy: (String) -> Boolean): (String) -> Boolean {
        if (op) return { true }
        if (home == null) return { false }
        val set = (if (parent == null) provinces + home else setOf(home)).filterNot { it != home && enemy(it) }.toSet()
        return { it in set }
    }

    fun of(viewer: String, op: Boolean): (String) -> Boolean {
        val home = Realm.of(viewer)
        return compute(home?.id, home?.parent, home?.provinces.orEmpty(), op) { Wars.enemies(home!!.id, it) }
    }
}
