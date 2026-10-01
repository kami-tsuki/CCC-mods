package kami.claims.research

import kami.claims.Country

inline fun <reified T : Unlock> Research.unlocks(country: Country): List<T> =
    country.research.done.keys.flatMap { defs.node(it)?.unlocks.orEmpty() }.filterIsInstance<T>()
