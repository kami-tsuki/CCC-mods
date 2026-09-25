package kami.libs.chat

fun spur(n: Number) = "%,d spur".format(n.toLong())

fun plural(n: Number, word: String) = "%,d %s%s".format(n.toLong(), word, if (n.toLong() == 1L) "" else "s")

fun every(days: Int) = if (days <= 1) "a day" else "every $days days"
