package com.arin.civicreport.data

import com.arin.civicreport.model.AuthorityRole
import com.arin.civicreport.model.AuthorityRole.*
import com.arin.civicreport.model.IncidentType

/**
 * Which departments must be told about each kind of problem, from the local body that
 * fixes it up to the central ministry that oversees it. Higher offices that apply to
 * every complaint are in [ALWAYS].
 */
object AuthorityRules {

    /** Added to every complaint: the escalation chain up to the PMO and the courts. */
    val ALWAYS: List<AuthorityRole> = listOf(
        DISTRICT, STATE_CM, CHIEF_SECRETARY, CPGRAMS, PMO, HIGH_COURT, SUPREME_COURT
    )

    fun rolesFor(type: IncidentType): List<AuthorityRole> = when (type) {
        IncidentType.POTHOLE ->
            listOf(PWD, AUTONOMOUS_COUNCIL, MUNICIPAL, DISTRICT, MORTH)
        IncidentType.DEFORESTATION ->
            listOf(FOREST, AUTONOMOUS_COUNCIL, POLICE, DISTRICT, POLLUTION, MOEFCC, NGT)
        IncidentType.SAND_MINING ->
            listOf(MINES, DISTRICT, POLICE, FOREST, AUTONOMOUS_COUNCIL, POLLUTION, MINISTRY_MINES, MOEFCC, NGT)
        IncidentType.ILLEGAL_CONSTRUCTION ->
            listOf(MUNICIPAL, AUTONOMOUS_COUNCIL, DISTRICT, PWD, MOHUA)
        IncidentType.GARBAGE_DUMPING ->
            listOf(MUNICIPAL, AUTONOMOUS_COUNCIL, POLLUTION, DISTRICT, CPCB, MOHUA, NGT)
        IncidentType.WATERLOGGING ->
            listOf(MUNICIPAL, AUTONOMOUS_COUNCIL, WATER, PWD, DISTRICT, MOHUA)
        IncidentType.STREET_LIGHT ->
            listOf(POWER, MUNICIPAL, AUTONOMOUS_COUNCIL)
        IncidentType.ENCROACHMENT ->
            listOf(MUNICIPAL, AUTONOMOUS_COUNCIL, DISTRICT, POLICE, TRAFFIC, PWD)
        IncidentType.NOISE_POLLUTION ->
            listOf(POLICE, POLLUTION, DISTRICT, CPCB)
        IncidentType.WATER_POLLUTION ->
            listOf(POLLUTION, WATER, DISTRICT, AUTONOMOUS_COUNCIL, CPCB, MOEFCC, NGT)
        IncidentType.AIR_POLLUTION ->
            listOf(POLLUTION, DISTRICT, CPCB, MOEFCC, NGT)
        IncidentType.WILDLIFE_CRIME ->
            listOf(WILDLIFE, FOREST, POLICE, DISTRICT, WCCB, MOEFCC)
        IncidentType.WATER_SUPPLY ->
            listOf(WATER, MUNICIPAL, AUTONOMOUS_COUNCIL, DISTRICT)
        IncidentType.CORRUPTION ->
            listOf(ANTI_CORRUPTION, LOKAYUKTA, DISTRICT, CVC, NHRC)
    }

    fun allRolesFor(types: Collection<IncidentType>): Set<AuthorityRole> =
        (types.flatMap { rolesFor(it) } + ALWAYS).toSet()
}
