package kami.claims.gov

import kotlinx.serialization.Serializable

@Serializable
enum class PermGroup { LAND, TREASURY, PEOPLE, RANKS, JOBS, USE, COUNTRY, NEWS }

@Serializable
enum class Risk { LOW, MEDIUM, HIGH, CRITICAL }

@Serializable
enum class Perm(val group: PermGroup, val risk: Risk) {
    CLAIM(PermGroup.LAND, Risk.MEDIUM),
    UNCLAIM(PermGroup.LAND, Risk.HIGH),
    CHUNK_TYPE(PermGroup.LAND, Risk.MEDIUM),
    CAPITAL(PermGroup.LAND, Risk.CRITICAL),
    PROVINCES(PermGroup.LAND, Risk.CRITICAL),
    PLOTS(PermGroup.LAND, Risk.MEDIUM),
    SECTORS(PermGroup.LAND, Risk.MEDIUM),
    CHUNK_RULES(PermGroup.LAND, Risk.HIGH),

    TREASURY_VIEW(PermGroup.TREASURY, Risk.LOW),
    DEPOSIT(PermGroup.TREASURY, Risk.LOW),
    WITHDRAW(PermGroup.TREASURY, Risk.CRITICAL),
    TAXES(PermGroup.TREASURY, Risk.HIGH),
    SPEND(PermGroup.TREASURY, Risk.MEDIUM),
    TRADE(PermGroup.TREASURY, Risk.MEDIUM),

    INVITE(PermGroup.PEOPLE, Risk.LOW),
    KICK(PermGroup.PEOPLE, Risk.HIGH),
    BANISH(PermGroup.PEOPLE, Risk.HIGH),
    PARDON(PermGroup.PEOPLE, Risk.MEDIUM),
    ALLIES(PermGroup.PEOPLE, Risk.MEDIUM),
    HOUSING(PermGroup.PEOPLE, Risk.MEDIUM),
    VIEW_PEOPLE(PermGroup.PEOPLE, Risk.LOW),

    ASSIGN_RANKS(PermGroup.RANKS, Risk.HIGH),
    EDIT_RANKS(PermGroup.RANKS, Risk.CRITICAL),
    CREATE_RANKS(PermGroup.RANKS, Risk.CRITICAL),
    REORDER_RANKS(PermGroup.RANKS, Risk.HIGH),
    EDIT_GROUPS(PermGroup.RANKS, Risk.HIGH),

    VIEW_JOBS(PermGroup.JOBS, Risk.LOW),
    ASSIGN_JOBS(PermGroup.JOBS, Risk.MEDIUM),
    DEFINE_JOBS(PermGroup.JOBS, Risk.HIGH),
    ASSIGN_CHUNKS(PermGroup.JOBS, Risk.MEDIUM),
    SCOPE_JOBS(PermGroup.JOBS, Risk.MEDIUM),

    BREAK(PermGroup.USE, Risk.MEDIUM),
    PLACE(PermGroup.USE, Risk.MEDIUM),
    INTERACT(PermGroup.USE, Risk.LOW),
    CONTAINERS(PermGroup.USE, Risk.MEDIUM),
    BYPASS_RULES(PermGroup.USE, Risk.CRITICAL),

    DETAILS(PermGroup.COUNTRY, Risk.LOW),
    RESEARCH(PermGroup.COUNTRY, Risk.MEDIUM),
    DIPLOMACY(PermGroup.COUNTRY, Risk.HIGH),
    WAR(PermGroup.COUNTRY, Risk.CRITICAL),
    AUDIT(PermGroup.COUNTRY, Risk.LOW),
    DELEGATED(PermGroup.COUNTRY, Risk.HIGH),

    NEWS_WRITE(PermGroup.NEWS, Risk.LOW),
    NEWS_PUBLISH(PermGroup.NEWS, Risk.MEDIUM);

    val node get() = name.lowercase()

    companion object {
        val outsiderAllowed = setOf(DEPOSIT, VIEW_PEOPLE, BREAK, PLACE, INTERACT, CONTAINERS)
    }
}
