package kami.claims.client

import kami.claims.Rank

fun rankOf(name: String): Rank? = Rank.entries.firstOrNull { it.name.equals(name, true) }
