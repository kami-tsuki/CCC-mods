package kami.claims.client.app

internal inline fun <reified E : Enum<E>> wireEnum(name: String, fallback: E): E = enumValues<E>().firstOrNull { it.name.equals(name, true) } ?: fallback
