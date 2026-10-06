package kami.libs.admin

fun List<String>.text(i: Int) = getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }

fun List<String>.long(i: Int) = text(i)?.toLongOrNull()

fun List<String>.int(i: Int) = text(i)?.toIntOrNull()

fun List<String>.flag(i: Int) = text(i)?.toBooleanStrictOrNull() == true

fun List<String>.page(i: Int) = long(i)?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0
