package kami.claims.compat

import kami.claims.Action
import kami.claims.world.Guard
import kami.libs.sophisticated.Sophisticated as Bridge

object Sophisticated {
    fun register() = Bridge.guard(
        { p, pos -> p.level().isClientSide || Guard.reaches(p.level(), pos, p, Action.CONTAINER) },
        { p, e -> p.level().isClientSide || Guard.reaches(e.level(), e.blockPosition(), p, Action.INTERACT) }
    )
}
