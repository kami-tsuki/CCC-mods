package kami.libs.db

import kotlinx.serialization.KSerializer

object Verify {
    fun <T> sameJson(what: String, serializer: KSerializer<T>, expected: T, actual: T) =
        equal(what, Db.json.encodeToString(serializer, expected), Db.json.encodeToString(serializer, actual))

    fun <T> equal(what: String, expected: T, actual: T) = check(expected == actual) { "$what: expected $expected, actual $actual" }
}
