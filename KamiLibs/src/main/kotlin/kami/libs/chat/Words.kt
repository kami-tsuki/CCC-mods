package kami.libs.chat

fun spur(n: Number) = "%,d spur".format(n.toLong())
fun plural(n: Number, one: String, many: String = "${one}s") = "%,d %s".format(n.toLong(), if (n.toLong() == 1L) one else many)
fun every(days: Int) = if (days <= 1) "a day" else "every $days days"
