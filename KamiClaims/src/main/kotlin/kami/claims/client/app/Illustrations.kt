package kami.claims.client.app

import kami.claims.KamiClaims
import net.minecraft.resources.ResourceLocation

object Illustrations {
    private fun of(name: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(KamiClaims.ID, "illustration/$name")

    val NOMANSLAND = of("nomansland")
    val FOUND = of("found")
    val TREASURY = of("treasury")
    val CITIZENS = of("citizens")
    val JOBS = of("jobs")
    val LAW = of("law")
    val DIPLOMACY = of("diplomacy")
    val PROVINCE = of("province")
    val INDEPENDENCE = of("independence")
    val FOG = of("fog")
    val DEBT = of("debt")
    val SUCCESS = of("success")
    val LOCKED = of("locked")
}
