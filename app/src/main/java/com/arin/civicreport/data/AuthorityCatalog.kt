package com.arin.civicreport.data

import com.arin.civicreport.model.AuthorityRole
import com.arin.civicreport.model.AuthorityRole.*
import com.arin.civicreport.model.IncidentType

/**
 * Which department is legally responsible for each kind of complaint (researched for
 * Assam / India), which departments support it, and the law that applies.
 */
object AuthorityRules {

    data class Info(
        /** Must act on this by law: the complaint goes here first. */
        val responsible: List<AuthorityRole>,
        /** Share the job or enforce a related law. */
        val supporting: List<AuthorityRole>,
        /** One line for people: who handles it. */
        val handledBy: String,
        /** The main law or rule. */
        val law: String
    )

    /** Escalation chain added to every complaint, up to the PMO and the courts. */
    val ESCALATION: List<AuthorityRole> = listOf(
        DISTRICT, STATE_CM, CHIEF_SECRETARY, CPGRAMS, PMO, HIGH_COURT, SUPREME_COURT
    )

    fun info(type: IncidentType): Info = when (type) {
        IncidentType.POTHOLE -> Info(
            responsible = listOf(PWD, AUTONOMOUS_COUNCIL, MUNICIPAL),
            supporting = listOf(MORTH, PANCHAYAT, DISTRICT),
            handledBy = "PWD (Roads) Executive Engineer for state & district roads; the municipal board for town roads; NHAI / PWD (NH) for national highways; KAAC in Karbi Anglong",
            law = "Road owner's duty of maintenance; NH grievances via NHAI 1033"
        )
        IncidentType.DEFORESTATION -> Info(
            responsible = listOf(FOREST),
            supporting = listOf(AUTONOMOUS_COUNCIL, POLICE, DISTRICT, MOEFCC, NGT),
            handledBy = "Divisional Forest Officer / Range Officer of the Forest Department",
            law = "Assam Forest Regulation, 1891; Forest (Conservation) Act, 1980"
        )
        IncidentType.SAND_MINING -> Info(
            responsible = listOf(FOREST, MINES),
            supporting = listOf(REVENUE, DISTRICT, POLICE, POLLUTION, AUTONOMOUS_COUNCIL, MINISTRY_MINES, MOEFCC, NGT),
            handledBy = "In Assam river sand, stone and gravel are mostly under the Forest Department (DFO), with the Directorate of Geology & Mining for other minerals; district task forces led by Circle Officers",
            law = "Assam Minor Mineral Concession Rules, 2013; MMDR Act, 1957"
        )
        IncidentType.ILLEGAL_CONSTRUCTION -> Info(
            responsible = listOf(MUNICIPAL, TOWN_PLANNING, AUTONOMOUS_COUNCIL),
            supporting = listOf(REVENUE, DISTRICT, URBAN_DEV, MOHUA),
            handledBy = "Municipal board / GMC and the development authority (GMDA, Town & Country Planning) that gives building permission",
            law = "Assam Municipal Act, 1956; Assam Town & Country Planning Act, 1959; GMDA Act, 1985"
        )
        IncidentType.GARBAGE_DUMPING -> Info(
            responsible = listOf(MUNICIPAL, PANCHAYAT, AUTONOMOUS_COUNCIL),
            supporting = listOf(POLLUTION, URBAN_DEV, DISTRICT, CPCB, NGT),
            handledBy = "The municipal board in towns, the Gaon Panchayat in villages; the Pollution Control Board enforces the rules",
            law = "Solid Waste Management Rules, 2016 (Rules 15 & 16)"
        )
        IncidentType.WATERLOGGING -> Info(
            responsible = listOf(MUNICIPAL, WATER_RESOURCES, AUTONOMOUS_COUNCIL),
            supporting = listOf(TOWN_PLANNING, PWD, URBAN_DEV, DISTRICT),
            handledBy = "Municipal board for town drains; Water Resources Department for drainage congestion and floods; GMDA in Guwahati",
            law = "Assam Municipal Act, 1956; State flood & drainage works"
        )
        IncidentType.STREET_LIGHT -> Info(
            responsible = listOf(MUNICIPAL, PANCHAYAT),
            supporting = listOf(POWER, AUTONOMOUS_COUNCIL, URBAN_DEV),
            handledBy = "The municipal board in towns, the Gaon Panchayat in villages; APDCL for the power supply and poles",
            law = "Municipal / Panchayat civic duties"
        )
        IncidentType.ENCROACHMENT -> Info(
            responsible = listOf(REVENUE, MUNICIPAL),
            supporting = listOf(PWD, FOREST, POLICE, TRAFFIC, AUTONOMOUS_COUNCIL, DISTRICT),
            handledBy = "Circle Officer (Revenue) under the District Commissioner for government land; the municipal board for footpaths; PWD for road land; Forest for forest land",
            law = "Assam Land & Revenue Regulation, 1886; Assam Land Policy, 2019"
        )
        IncidentType.NOISE_POLLUTION -> Info(
            responsible = listOf(POLICE, DISTRICT),
            supporting = listOf(POLLUTION, CPCB),
            handledBy = "Police (officer not below DSP) and the District Magistrate are the designated authorities",
            law = "Noise Pollution (Regulation & Control) Rules, 2000"
        )
        IncidentType.WATER_POLLUTION -> Info(
            responsible = listOf(POLLUTION),
            supporting = listOf(MUNICIPAL, WATER, FISHERY, DISTRICT, CPCB, MOEFCC, NGT),
            handledBy = "State Pollution Control Board (Assam: PCBA regional office)",
            law = "Water (Prevention & Control of Pollution) Act, 1974"
        )
        IncidentType.AIR_POLLUTION -> Info(
            responsible = listOf(POLLUTION),
            supporting = listOf(MUNICIPAL, DISTRICT, CPCB, MOEFCC, NGT),
            handledBy = "State Pollution Control Board; the municipal board for open garbage burning",
            law = "Air (Prevention & Control of Pollution) Act, 1981; SWM Rules, 2016"
        )
        IncidentType.WILDLIFE_CRIME -> Info(
            responsible = listOf(WILDLIFE, FOREST),
            supporting = listOf(POLICE, WCCB, DISTRICT, MOEFCC),
            handledBy = "Chief Wildlife Warden / DFO of the Forest Department; WCCB for organised wildlife trade",
            law = "Wild Life (Protection) Act, 1972"
        )
        IncidentType.WATER_SUPPLY -> Info(
            responsible = listOf(WATER),
            supporting = listOf(MUNICIPAL, AUTONOMOUS_COUNCIL, PANCHAYAT, DISTRICT),
            handledBy = "Public Health Engineering Department (PHED) – Jal Jeevan Mission; the municipal water works in towns",
            law = "Jal Jeevan Mission service norms"
        )
        IncidentType.CORRUPTION -> Info(
            responsible = listOf(ANTI_CORRUPTION),
            supporting = listOf(LOKAYUKTA, CVC, NHRC),
            handledBy = "Directorate of Vigilance & Anti-Corruption (state staff); Lokayukta (public servants incl. ministers); CVC (central staff, banks, PSUs)",
            law = "Prevention of Corruption Act, 1988; Assam Lokayukta Act, 1985"
        )
        IncidentType.HILL_CUTTING -> Info(
            responsible = listOf(REVENUE, DISTRICT, FOREST),
            supporting = listOf(TOWN_PLANNING, MINES, POLICE, POLLUTION, AUTONOMOUS_COUNCIL, NGT),
            handledBy = "District Commissioner and the Circle Officer's task force; Forest Department on forest land; GMDA / GMC in Guwahati",
            law = "District prohibitory orders; Assam Minor Mineral Concession Rules, 2013"
        )
        IncidentType.ILLEGAL_LIQUOR -> Info(
            responsible = listOf(EXCISE),
            supporting = listOf(POLICE, DISTRICT),
            handledBy = "Superintendent / Inspector of Excise of the district (Assam Excise Department)",
            law = "Assam Excise Act and Rules"
        )
        IncidentType.DRUGS -> Info(
            responsible = listOf(NARCOTICS, POLICE),
            supporting = listOf(EXCISE, DISTRICT),
            handledBy = "Police (district SP) and the Narcotics Control Bureau – MANAS helpline 1933",
            law = "NDPS Act, 1985"
        )
        IncidentType.ILLEGAL_FISHING -> Info(
            responsible = listOf(FISHERY),
            supporting = listOf(POLLUTION, POLICE, DISTRICT),
            handledBy = "District Fishery Development Officer (Directorate of Fisheries)",
            law = "Assam Fishery Rules, 1953; Indian Fisheries Act, 1897"
        )
        IncidentType.FOOD_ADULTERATION -> Info(
            responsible = listOf(FOOD_SAFETY),
            supporting = listOf(MUNICIPAL, DISTRICT),
            handledBy = "Commissioner of Food Safety and the district Food Safety Officer",
            law = "Food Safety & Standards Act, 2006"
        )
        IncidentType.CHILD_LABOUR -> Info(
            responsible = listOf(CHILD_PROTECTION, POLICE),
            supporting = listOf(DISTRICT, NHRC),
            handledBy = "Childline 1098 and the Labour Department (rescue); the local police station",
            law = "Child & Adolescent Labour (Prohibition & Regulation) Act, 1986"
        )
    }

    fun rolesFor(type: IncidentType): List<AuthorityRole> = info(type).let { it.responsible + it.supporting }

    fun responsibleFor(types: Collection<IncidentType>): Set<AuthorityRole> =
        types.flatMap { info(it).responsible }.toSet()

    fun supportingFor(types: Collection<IncidentType>): Set<AuthorityRole> =
        types.flatMap { info(it).supporting }.toSet() - responsibleFor(types)

    fun allRolesFor(types: Collection<IncidentType>): Set<AuthorityRole> =
        (types.flatMap { rolesFor(it) } + ESCALATION).toSet()
}
