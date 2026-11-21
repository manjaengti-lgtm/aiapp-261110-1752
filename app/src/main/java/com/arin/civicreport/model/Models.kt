package com.arin.civicreport.model

import kotlinx.serialization.Serializable

/** Every kind of incident the app can report. */
enum class IncidentType(val label: String, val shortLabel: String, val emoji: String, val blurb: String) {
    POTHOLE("Pothole / bad road", "Pothole", "\uD83D\uDEA7", "Broken road surface, dangerous for traffic"),
    DEFORESTATION("Deforestation / tree cutting", "Deforestation", "\uD83C\uDF32", "Illegal felling of trees or forest clearing"),
    SAND_MINING("Illegal sand mining", "Sand mining", "\u26F1", "Unauthorised extraction of sand from river beds"),
    ILLEGAL_CONSTRUCTION("Illegal construction", "Construction", "\uD83C\uDFD7", "Construction without sanction or in a restricted zone"),
    GARBAGE_DUMPING("Garbage dumping", "Garbage", "\uD83D\uDDD1", "Waste dumped on roads, drains or open plots"),
    WATERLOGGING("Waterlogging", "Waterlogging", "\uD83D\uDCA7", "Standing water after rain, blocked drains"),
    STREET_LIGHT("Street light not working", "Street light", "\uD83D\uDCA1", "Dark street, faulty or missing lamp"),
    ENCROACHMENT("Encroachment", "Encroachment", "\uD83D\uDEA7", "Footpath or public land illegally occupied"),
    NOISE_POLLUTION("Noise pollution", "Noise", "\uD83D\uDD0A", "Loudspeakers, construction noise, honking"),
    WATER_POLLUTION("Water / river pollution", "Water pollution", "\uD83C\uDF0A", "Sewage, effluent or waste in a river, pond or drain"),
    AIR_POLLUTION("Air pollution / burning", "Air pollution", "\uD83C\uDFED", "Smoke, open burning, factory or brick-kiln emissions"),
    WILDLIFE_CRIME("Poaching / wildlife crime", "Wildlife crime", "\uD83D\uDC18", "Hunting, trapping or trade of protected animals"),
    WATER_SUPPLY("No drinking water", "Water supply", "\uD83D\uDEB0", "Broken pipeline, dry tap, dirty supply water"),
    CORRUPTION("Bribe / corruption", "Corruption", "\uD83D\uDCB0", "An official demanding money or misusing public funds"),
    HILL_CUTTING("Illegal hill / earth cutting", "Hill cutting", "\u26F0", "Hills or land dug and earth carried away without permission"),
    ILLEGAL_LIQUOR("Illegal liquor", "Illegal liquor", "\uD83C\uDF76", "Brewing or selling liquor without a licence"),
    DRUGS("Drug peddling", "Drugs", "\uD83D\uDC8A", "Sale, trafficking or cultivation of narcotic drugs"),
    ILLEGAL_FISHING("Illegal fishing", "Illegal fishing", "\uD83D\uDC1F", "Poison, electric or small-mesh fishing, fishing in the ban period"),
    FOOD_ADULTERATION("Food adulteration", "Food adulteration", "\uD83E\uDD6B", "Adulterated, spoiled or mislabelled food being sold"),
    CHILD_LABOUR("Child labour", "Child labour", "\uD83E\uDDD2", "A child made to work in a shop, home, factory or site")
}

/** How directly an office is responsible for a particular complaint. */
@Serializable
enum class AuthorityTier(val label: String, val hint: String, val order: Int) {
    RESPONSIBLE("Responsible department", "The office that must act on this by law. Complain here first.", 0),
    SUPPORTING("Supporting departments", "Offices that share the job or enforce related laws.", 1),
    ESCALATION("Higher authorities", "Escalate here if the responsible office does not act.", 2),
    COURTS("Courts and tribunals", "They take petitions, not ordinary complaints, so these start unticked.", 3)
}

/** How high up the chain an office sits, from the spot itself to the courts. */
@Serializable
enum class AuthorityLevel(val label: String, val order: Int) {
    LOCAL("Local body", 0),
    DISTRICT("District", 1),
    STATE("State", 2),
    NATIONAL("Central government", 3),
    JUDICIARY("Courts and tribunals", 4)
}

/** Which department a report of a given type must reach. */
enum class AuthorityRole(val label: String) {
    MUNICIPAL("Municipal / town body"),
    AUTONOMOUS_COUNCIL("Autonomous council (Sixth Schedule)"),
    PANCHAYAT("Gaon Panchayat / Panchayat & Rural Development"),
    URBAN_DEV("Municipal administration (state)"),
    TOWN_PLANNING("Town planning / development authority"),
    REVENUE("Revenue (Circle Officer, land)"),
    WATER_RESOURCES("Water Resources (drainage, flood)"),
    EXCISE("Excise department"),
    NARCOTICS("Narcotics (NCB / MANAS 1933)"),
    FISHERY("Fisheries department"),
    FOOD_SAFETY("Food safety commissioner"),
    CHILD_PROTECTION("Child protection (1098 / Labour)"),
    PWD("Public Works (roads, engineers)"),
    TRAFFIC("Traffic / transport"),
    POLICE("Police"),
    DISTRICT("District Commissioner / Magistrate"),
    POWER("Electricity board"),
    WATER("Water supply / public health engineering"),
    FOREST("Forest department"),
    WILDLIFE("Wildlife wing"),
    MINES("Mines & geology"),
    POLLUTION("State pollution control board"),
    ANTI_CORRUPTION("Vigilance & anti-corruption"),
    LOKAYUKTA("Lokayukta"),
    STATE_CM("Chief Minister's office"),
    CHIEF_SECRETARY("Chief Secretary"),
    HIGH_COURT("High Court"),
    PMO("Prime Minister's Office"),
    CPGRAMS("Central grievance portal (CPGRAMS)"),
    CPCB("Central Pollution Control Board"),
    MOEFCC("Ministry of Environment, Forest & Climate Change"),
    MORTH("Ministry of Road Transport / NHAI"),
    MINISTRY_MINES("Ministry of Mines"),
    MOHUA("Ministry of Housing & Urban Affairs"),
    WCCB("Wildlife Crime Control Bureau"),
    CVC("Central Vigilance Commission"),
    NHRC("National Human Rights Commission"),
    NGT("National Green Tribunal"),
    SUPREME_COURT("Supreme Court of India")
}

/** A contactable office. */
@Serializable
data class Authority(
    val id: String,
    val name: String,
    val category: String,
    val email: String? = null,
    val phone: String? = null,
    val url: String? = null,
    val level: AuthorityLevel = AuthorityLevel.LOCAL,
    val fax: String? = null,
    val address: String? = null,
    val note: String? = null,
    /** Other numbers (helplines, mobiles) shown for calling; the first [phone] is used for SMS. */
    val otherPhones: List<String> = emptyList(),
    /** Ticked by default. Courts and tribunals start unticked: they take petitions, not complaints. */
    val defaultSelected: Boolean = true,
    val custom: Boolean = false,
    /** Where this contact was taken from, so the person can check it. */
    val source: String? = null,
    val tier: AuthorityTier = AuthorityTier.ESCALATION
) {
    val hasContact: Boolean
        get() = !email.isNullOrBlank() || !phone.isNullOrBlank() || !url.isNullOrBlank() ||
            otherPhones.isNotEmpty() || !address.isNullOrBlank()
}

/** A file of any type attached to a complaint. Kept as a reference, so its size does not matter. */
@Serializable
data class Attachment(
    val uri: String,
    val name: String,
    val mimeType: String = "*/*",
    val sizeBytes: Long = -1L
) {
    val sizeLabel: String
        get() = when {
            sizeBytes < 0 -> "size unknown"
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024L * 1024 -> "%.1f KB".format(sizeBytes / 1024.0)
            sizeBytes < 1024L * 1024 * 1024 -> "%.1f MB".format(sizeBytes / (1024.0 * 1024))
            else -> "%.2f GB".format(sizeBytes / (1024.0 * 1024 * 1024))
        }
}

/** The place of a report, with both raw coordinates and a readable address. */
@Serializable
data class ReportLocation(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracy: Float = 0f,
    val address: String = "",
    val city: String = "",
    val district: String = "",
    val state: String = "",
    val postcode: String = ""
) {
    val hasFix: Boolean get() = latitude != 0.0 || longitude != 0.0
    val oneLine: String
        get() = address.ifBlank {
            if (hasFix) "%.6f, %.6f".format(latitude, longitude) else "No location"
        }
}

/** One saved report. */
@Serializable
data class Report(
    val id: String,
    val types: List<IncidentType>,
    val createdAt: Long,
    val location: ReportLocation,
    val notes: String = "",
    val mediaUris: List<String> = emptyList(),
    val videoUri: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val authorities: List<Authority> = emptyList(),
    val contactEmail: String = "",
    val sendEmail: Boolean = true,
    val sendSms: Boolean = true,
    val status: ReportStatus = ReportStatus.DRAFT,
    val lastSentAt: Long = 0L,
    val sentChannels: List<String> = emptyList(),
    val mapSnapshot: String = ""
) {
    val title: String get() = types.joinToString(", ") { it.shortLabel }.ifBlank { "Untitled draft" }
    val primaryType: IncidentType? get() = types.firstOrNull()
}

@Serializable
enum class ReportStatus { DRAFT, SENT, PARTIAL }

/** Settings the person can change. */
data class AppSettings(
    val defaultChannel: SendChannel = SendChannel.BOTH,
    val notificationsEnabled: Boolean = true,
    val senderName: String = "",
    val senderEmail: String = "",
    val senderPhone: String = "",
    val highAccuracy: Boolean = true,
    val includeNationalAuthorities: Boolean = true,
    /** Also keep a copy of every photo and video in the phone's gallery. */
    val saveToGallery: Boolean = true
)

enum class SendChannel(val label: String) { EMAIL("Email"), SMS("SMS"), BOTH("Email and SMS") }
