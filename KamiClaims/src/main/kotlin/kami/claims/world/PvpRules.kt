package kami.claims.world

object PvpRules {
    const val TARGET_CLASS = "com.tacz.guns.entity.TargetMinecart"
    const val TARGET_INTERFACE = "com.tacz.guns.api.entity.ITargetEntity"

    fun allowed(war: Boolean?, claimed: Boolean, claimPvp: Boolean, wildPvp: Boolean) = war ?: if (claimed) claimPvp else wildPvp

    fun trainingTarget(typeNames: Set<String>, tagged: Boolean) = tagged || TARGET_CLASS in typeNames || TARGET_INTERFACE in typeNames
}
