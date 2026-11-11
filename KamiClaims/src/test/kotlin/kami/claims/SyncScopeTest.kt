package kami.claims

import kami.claims.gov.PRESIDENT
import kami.claims.gov.Standing
import kami.claims.net.Sync
import kami.claims.service.Scope
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncScopeTest {
    @Test
    fun citizenshipsLimitedToScopeExceptOwn() {
        Realm.reset(Data())
        val a = Country("Alpha").also { Realm.data.countries[it.id] = it }
        val b = Country("Beta").also { Realm.data.countries[it.id] = it }
        a.mixed()
        Realm.join(a, "me", PRESIDENT)
        b.mixed()
        Realm.join(b, "bob", PRESIDENT)
        a.autoAllies += "bob"
        a.outsiders["bob"] = Standing.ALLIED
        val scope = Scope.of("me", false)
        assertEquals(listOf("Alpha"), Sync.citizenships("bob", scope).map { it.country })
        assertEquals(listOf("Alpha", "Beta").sorted(), Sync.citizenships("bob") { true }.map { it.country }.sorted())
        assertEquals("", Sync.citizenships("bob") { it == a.id }.single().via)
        assertEquals("Beta", Sync.citizenships("bob") { true }.first { it.country == "Alpha" }.via)
    }
}
