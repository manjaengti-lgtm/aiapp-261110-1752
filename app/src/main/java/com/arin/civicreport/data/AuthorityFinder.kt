package com.arin.civicreport.data

import android.content.Context
import com.arin.civicreport.model.Authority
import com.arin.civicreport.model.AuthorityLevel
import com.arin.civicreport.model.AuthorityRole
import com.arin.civicreport.model.IncidentType
import com.arin.civicreport.model.ReportLocation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Picks the offices that must receive a complaint, from the local body up to the
 * Prime Minister's Office and the Supreme Court, using the verified directory in
 * assets/authorities.json.
 */
class AuthorityFinder(private val context: Context) {

    @Serializable
    private data class Office(
        val id: String,
        val name: String,
        val roles: List<String> = emptyList(),
        val level: AuthorityLevel = AuthorityLevel.LOCAL,
        val email: String? = null,
        val phone: String? = null,
        val otherPhones: List<String> = emptyList(),
        val fax: String? = null,
        val address: String? = null,
        val url: String? = null,
        val note: String? = null,
        val defaultSelected: Boolean = true
    ) {
        val roleSet: Set<AuthorityRole>
            get() = roles.mapNotNull { r -> runCatching { AuthorityRole.valueOf(r) }.getOrNull() }.toSet()
    }

    @Serializable
    private data class StateEntry(val state: String, val offices: List<Office> = emptyList())

    @Serializable
    private data class DistrictEntry(
        val district: String,
        val state: String,
        val match: List<String> = emptyList(),
        val postcodes: List<String> = emptyList(),
        val offices: List<Office> = emptyList()
    )

    @Serializable
    private data class Directory(
        val verifiedOn: String = "",
        val national: List<Office> = emptyList(),
        val states: List<StateEntry> = emptyList(),
        val districts: List<DistrictEntry> = emptyList()
    )

    /** What the finder matched, so the screen can say which jurisdiction it used. */
    data class Result(
        val authorities: List<Authority>,
        val matchedDistrict: String?,
        val matchedState: String?,
        val verifiedOn: String
    )

    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var cache: Directory? = null

    private fun load(): Directory {
        cache?.let { return it }
        val text = runCatching {
            context.assets.open("authorities.json").bufferedReader().use { it.readText() }
        }.getOrNull()
        val dir = text?.let { runCatching { json.decodeFromString<Directory>(it) }.getOrNull() } ?: Directory()
        cache = dir
        return dir
    }

    val verifiedOn: String get() = load().verifiedOn

    private fun norm(s: String) = s.trim().lowercase()

    private fun findDistrict(dir: Directory, loc: ReportLocation): DistrictEntry? {
        val district = norm(loc.district)
        val city = norm(loc.city)
        val address = norm(loc.address)
        val postcode = loc.postcode.trim()
        // 1. Exact district name, 2. postcode, 3. any alias appearing in district/city/address.
        dir.districts.firstOrNull { district.isNotBlank() && norm(it.district) == district }?.let { return it }
        dir.districts.firstOrNull { postcode.isNotBlank() && postcode in it.postcodes }?.let { return it }
        val haystacks = listOf(district, city, address).filter { it.isNotBlank() }
        // Longer aliases first so "west karbi anglong" wins over "karbi anglong".
        val aliases = dir.districts.flatMap { d -> d.match.map { it to d } }.sortedByDescending { it.first.length }
        return aliases.firstOrNull { (alias, _) -> haystacks.any { it.contains(alias) } }?.second
    }

    private fun findState(dir: Directory, loc: ReportLocation, district: DistrictEntry?): StateEntry? {
        district?.let { d -> dir.states.firstOrNull { norm(it.state) == norm(d.state) }?.let { return it } }
        val state = norm(loc.state)
        if (state.isNotBlank()) {
            dir.states.firstOrNull { norm(it.state) == state }?.let { return it }
            // Geocoders sometimes return "National Capital Territory of Delhi" etc.
            dir.states.firstOrNull { state.contains(norm(it.state)) }?.let { return it }
        }
        val address = norm(loc.address)
        return dir.states.firstOrNull { address.contains(norm(it.state)) }
    }

    private fun Office.toAuthority(matched: Set<AuthorityRole>): Authority {
        val shown = roleSet.filter { it in matched }.ifEmpty { roleSet.toList() }
        return Authority(
            id = id,
            name = name,
            category = shown.joinToString(" · ") { it.label },
            email = email,
            phone = phone,
            url = url,
            level = level,
            fax = fax,
            address = address,
            note = note,
            otherPhones = otherPhones,
            defaultSelected = defaultSelected
        )
    }

    fun find(types: List<IncidentType>, location: ReportLocation, includeNational: Boolean): Result {
        val dir = load()
        val roles = AuthorityRules.allRolesFor(types)
        val district = findDistrict(dir, location)
        val state = findState(dir, location, district)

        val pool = buildList {
            district?.let { addAll(it.offices) }
            state?.let { addAll(it.offices) }
            addAll(dir.national)
        }
        val seen = mutableSetOf<String>()
        val picked = pool.filter { office ->
            office.roleSet.any { it in roles } && seen.add(office.id)
        }.filter { includeNational || it.level != AuthorityLevel.NATIONAL }
            .map { it.toAuthority(roles) }
            .filter { it.hasContact }
            .sortedBy { it.level.order }

        return Result(picked, district?.district, state?.state ?: district?.state, dir.verifiedOn)
    }

    /** Kept for callers that only need the list. */
    fun findAuthorities(types: List<IncidentType>, location: ReportLocation, includeNational: Boolean): List<Authority> =
        find(types, location, includeNational).authorities
}
