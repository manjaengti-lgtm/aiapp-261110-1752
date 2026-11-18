package com.fakelocation.app

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs

/**
 * Finds places using three sources at once and merges them:
 *  1. Android's own geocoder (Google on most phones — knows shops, villages, addresses)
 *  2. Photon (OpenStreetMap, typo tolerant, partial names)
 *  3. Nominatim (OpenStreetMap)
 */
object PlaceSearch {

    suspend fun search(ctx: Context, query: String, near: Pt?): List<Place> = coroutineScope {
        val google = async(Dispatchers.IO) { safe { phoneGeocoder(ctx, query) } }
        val photon = async(Dispatchers.IO) { safe { photon(query, near) } }
        val osm = async(Dispatchers.IO) { safe { nominatim(query) } }
        val lists = listOf(google.await(), photon.await(), osm.await())
        if (lists.all { it == null }) throw Exception("All searches failed")
        val out = ArrayList<Place>()
        for (list in lists) for (p in list.orEmpty()) {
            // Skip places within ~100 m of one already listed.
            if (out.none { abs(it.lat - p.lat) < 0.001 && abs(it.lng - p.lng) < 0.001 }) out += p
        }
        out.take(30)
    }

    private suspend fun safe(block: () -> List<Place>): List<Place>? =
        withTimeoutOrNull(15000) {
            try { block() } catch (_: Exception) { null }
        }

    @Suppress("DEPRECATION")
    private fun phoneGeocoder(ctx: Context, q: String): List<Place> {
        if (!Geocoder.isPresent()) return emptyList()
        val list = Geocoder(ctx, Locale.getDefault()).getFromLocationName(q, 10) ?: return emptyList()
        return list.filter { it.hasLatitude() && it.hasLongitude() }.map { a ->
            val name = (0..a.maxAddressLineIndex).mapNotNull { a.getAddressLine(it) }.joinToString(", ")
                .ifBlank {
                    listOfNotNull(a.featureName, a.subLocality, a.locality, a.adminArea, a.countryName)
                        .distinct().joinToString(", ")
                }
            Place(name.ifBlank { "%.5f, %.5f".format(a.latitude, a.longitude) }, a.latitude, a.longitude)
        }
    }

    private fun photon(q: String, near: Pt?): List<Place> {
        var url = "https://photon.komoot.io/api/?limit=15&q=" + URLEncoder.encode(q, "UTF-8")
        if (near != null) url += "&lat=%.4f&lon=%.4f".format(Locale.US, near.lat, near.lng)
        val feats = JSONObject(get(url)).getJSONArray("features")
        return (0 until feats.length()).mapNotNull { i ->
            val f = feats.optJSONObject(i) ?: return@mapNotNull null
            val c = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
            val pr = f.optJSONObject("properties") ?: JSONObject()
            val street = listOf(pr.optString("housenumber"), pr.optString("street"))
                .filter { it.isNotBlank() }.joinToString(" ")
            val name = listOf(
                pr.optString("name"), street, pr.optString("locality"), pr.optString("district"),
                pr.optString("city"), pr.optString("county"), pr.optString("state"), pr.optString("country")
            ).filter { it.isNotBlank() }.distinct().joinToString(", ")
            Place(name.ifBlank { "%.5f, %.5f".format(c.getDouble(1), c.getDouble(0)) }, c.getDouble(1), c.getDouble(0))
        }
    }

    private fun nominatim(q: String): List<Place> {
        val arr = JSONArray(
            get("https://nominatim.openstreetmap.org/search?format=json&limit=15&q=" + URLEncoder.encode(q, "UTF-8"))
        )
        return (0 until arr.length()).mapNotNull {
            val o = arr.optJSONObject(it) ?: return@mapNotNull null
            val la = o.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
            val lo = o.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
            Place(o.optString("display_name", "%.5f, %.5f".format(la, lo)), la, lo)
        }
    }

    private fun get(u: String): String {
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "FakeLocationApp/1.0 (Android)")
        conn.setRequestProperty("Accept-Language", "en")
        conn.connectTimeout = 10000
        conn.readTimeout = 12000
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ---------------- Coordinates ----------------

    /**
     * Understands almost any way of writing a position, e.g.
     *  26.1445, 91.7362 · 26.1445 91.7362 · 26.1445° N, 91.7362° E · N 26.1445 E 91.7362
     *  26°08'40.2"N 91°44'10.3"E · Google Maps links (…@26.14,91.73… or …q=26.14,91.73…)
     */
    fun parseCoords(input: String): Pair<Double, Double>? {
        val t = input.trim()
            .replace('′', '\'').replace('’', '\'').replace('″', '"').replace('”', '"').replace("''", "\"")
            .replace('º', '°')
        if (t.isEmpty()) return null

        // Map links: @lat,lng  q=lat,lng  ll=lat,lng  query=lat,lng
        Regex("""(?:@|[?&](?:q|ll|query|destination|center)=)(-?\d{1,2}(?:\.\d+)?),\s*(-?\d{1,3}(?:\.\d+)?)""")
            .find(t)?.let { m -> return check(m.groupValues[1].toDouble(), m.groupValues[2].toDouble()) }

        // Degrees minutes seconds with N/S/E/W
        val dms = Regex("""(\d{1,3})\s*°\s*(\d{1,2}(?:\.\d+)?)\s*'\s*(?:(\d{1,2}(?:\.\d+)?)\s*"?)?\s*([NSEWnsew])""")
            .findAll(t).toList()
        if (dms.size == 2) {
            var lat: Double? = null
            var lng: Double? = null
            for (m in dms) {
                val v = m.groupValues[1].toDouble() + m.groupValues[2].toDouble() / 60 +
                    (m.groupValues[3].toDoubleOrNull() ?: 0.0) / 3600
                when (m.groupValues[4].uppercase()) {
                    "N" -> lat = v
                    "S" -> lat = -v
                    "E" -> lng = v
                    "W" -> lng = -v
                }
            }
            if (lat != null && lng != null) return check(lat, lng)
        }

        // Decimal numbers, optionally with ° and N/S/E/W before or after
        val dec = Regex("""([NSEWnsew])?\s*(-?\d{1,3}(?:\.\d+)?)\s*°?\s*([NSEWnsew](?![a-zA-Z]))?""")
            .findAll(t).filter { it.groupValues[2].isNotEmpty() }.toList()
        if (dec.size != 2) return null
        // Leftover words mean it's a place name like "Sector 21", not coordinates.
        val leftover = t.replace(Regex("""[\d\s.,;:°\-+NSEWnsew]|lat(itude)?|lo?ng(itude)?|lon""", RegexOption.IGNORE_CASE), "")
        if (leftover.isNotEmpty()) return null
        val hasHint = t.contains('.') || t.contains('°') || dec.any { it.groupValues[1].isNotEmpty() || it.groupValues[3].isNotEmpty() }
        if (!hasHint) return null

        var lat: Double? = null
        var lng: Double? = null
        val plain = ArrayList<Double>()
        for (m in dec) {
            val v = m.groupValues[2].toDouble()
            when ((m.groupValues[1] + m.groupValues[3]).uppercase().firstOrNull()) {
                'N' -> lat = abs(v)
                'S' -> lat = -abs(v)
                'E' -> lng = abs(v)
                'W' -> lng = -abs(v)
                else -> plain += v
            }
        }
        if (lat == null && plain.isNotEmpty()) lat = plain.removeAt(0)
        if (lng == null && plain.isNotEmpty()) lng = plain.removeAt(0)
        return if (lat != null && lng != null) check(lat, lng) else null
    }

    private fun check(lat: Double, lng: Double): Pair<Double, Double>? =
        if (lat in -90.0..90.0 && lng in -180.0..180.0) lat to lng else null
}

/** Reverse lookup: a readable name for a point (used for A/B labels). Call off the main thread. */
object PlaceName {
    @Suppress("DEPRECATION")
    fun of(ctx: Context, p: Pt): String? = try {
        if (!Geocoder.isPresent()) null
        else Geocoder(ctx, Locale.getDefault()).getFromLocation(p.lat, p.lng, 1)?.firstOrNull()?.let { a ->
            listOfNotNull(a.featureName?.takeIf { f -> f.any { it.isLetter() } }, a.subLocality, a.locality)
                .distinct().joinToString(", ").ifBlank { null }
        }
    } catch (_: Exception) {
        null
    }

    suspend fun ofAsync(ctx: Context, p: Pt): String? = withContext(Dispatchers.IO) { of(ctx, p) }
}
