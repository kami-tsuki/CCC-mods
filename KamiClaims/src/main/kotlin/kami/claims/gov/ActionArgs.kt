package kami.claims.gov

import kami.claims.Config
import kami.claims.Country
import kami.claims.NameRules
import kami.claims.service.Fail
import kami.claims.service.Words.num
import kami.claims.service.Words.v
import kami.libs.util.toUuidOrNull
import java.util.UUID

internal object ActionArgs {
    const val NEW = "new"
    private const val COLOR_MASK = 0xFFFFFF

    fun arg(a: List<String>, i: Int) = a.getOrNull(i) ?: throw Fail("kami_claims.error.missing_argument")
    fun int(a: List<String>, i: Int) = arg(a, i).toIntOrNull() ?: throw Fail("kami_claims.error.number")
    fun color(a: List<String>, i: Int) = int(a, i) and COLOR_MASK
    fun uuid(a: List<String>, i: Int) = arg(a, i).toUuidOrNull()?.toString() ?: throw Fail("kami_claims.error.unknown_player")
    fun newId() = UUID.randomUUID().toString().take(8)

    inline fun <reified T : Enum<T>> choice(text: String): T =
        enumValues<T>().firstOrNull { it.name.equals(text, true) } ?: throw Fail("kami_claims.error.choice", v(enumValues<T>().joinToString { it.name.lowercase() }))

    fun label(text: String): String {
        val (min, max) = Config.s.nameLength
        val name = text.trim()
        if (name.length !in min..max || !name.all { NameRules.allows(it) || it == ' ' } || NameRules.key(name).isEmpty()) throw Fail("kami_claims.error.name", num(min), num(max))
        return name
    }

    fun member(c: Country, id: String) = c.members[id] ?: throw Fail("kami_claims.error.not_member")
    fun job(c: Country, id: String) = c.jobDefs[id] ?: throw Fail("kami_claims.error.unknown_job")
    fun sector(c: Country, id: String) = c.sectors[id] ?: throw Fail("kami_claims.error.sector_unknown")
}
