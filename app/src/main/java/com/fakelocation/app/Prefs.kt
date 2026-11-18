package com.fakelocation.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Place(val name: String, val lat: Double, val lng: Double)

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("fake_location", Context.MODE_PRIVATE)

    // Default: India Gate, New Delhi
    val lastLat: Double get() = sp.getString("lat", null)?.toDoubleOrNull() ?: 28.6129
    val lastLng: Double get() = sp.getString("lng", null)?.toDoubleOrNull() ?: 77.2295

    fun saveLast(lat: Double, lng: Double) {
        sp.edit().putString("lat", lat.toString()).putString("lng", lng.toString()).apply()
    }

    var drift: Boolean
        get() = sp.getBoolean("drift", true)
        set(v) = sp.edit().putBoolean("drift", v).apply()

    fun getPt(key: String): Pt? {
        val a = sp.getString("${key}_lat", null)?.toDoubleOrNull() ?: return null
        val o = sp.getString("${key}_lng", null)?.toDoubleOrNull() ?: return null
        return Pt(a, o)
    }

    fun putPt(key: String, p: Pt?) {
        val e = sp.edit()
        if (p == null) e.remove("${key}_lat").remove("${key}_lng")
        else e.putString("${key}_lat", p.lat.toString()).putString("${key}_lng", p.lng.toString())
        e.apply()
    }

    fun getName(key: String): String? = sp.getString("${key}_name", null)

    fun putName(key: String, name: String?) {
        sp.edit().putString("${key}_name", name).apply()
    }

    var speed: Double
        get() = sp.getFloat("speed", 40f).toDouble()
        set(v) = sp.edit().putFloat("speed", v.toFloat()).apply()

    var routeType: RouteType
        get() = RouteType.entries.getOrElse(sp.getInt("rtype", 0)) { RouteType.ROADS }
        set(v) = sp.edit().putInt("rtype", v.ordinal).apply()

    var mapStyle: MapStyle
        get() = MapStyle.entries.getOrElse(sp.getInt("style", 0)) { MapStyle.DETAILED }
        set(v) = sp.edit().putInt("style", v.ordinal).apply()

    fun favorites(): List<Place> {
        val arr = JSONArray(sp.getString("favs", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Place(o.getString("n"), o.getDouble("a"), o.getDouble("o"))
        }
    }

    fun saveFavorites(list: List<Place>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("n", it.name).put("a", it.lat).put("o", it.lng)) }
        sp.edit().putString("favs", arr.toString()).apply()
    }
}

/** Place search using OpenStreetMap Nominatim (free, no key). Call off the main thread. */
object PlaceSearch {
    fun search(query: String): List<Place> {
        var last: Exception? = null
        repeat(2) {
            try {
                return nominatim(query)
            } catch (e: Exception) {
                last = e
                Thread.sleep(1200) // Nominatim allows ~1 request per second
            }
        }
        throw last ?: Exception("Search failed")
    }

    private fun nominatim(query: String): List<Place> {
        val url = URL(
            "https://nominatim.openstreetmap.org/search?format=json&limit=15&q=" +
                URLEncoder.encode(query, "UTF-8")
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "FakeLocationApp/1.0 (Android)")
        conn.setRequestProperty("Accept-Language", "en")
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        try {
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(text)
            return (0 until arr.length()).mapNotNull {
                val o = arr.optJSONObject(it) ?: return@mapNotNull null
                val la = o.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
                val lo = o.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
                Place(o.optString("display_name", "%.5f, %.5f".format(la, lo)), la, lo)
            }.distinctBy { "%.4f,%.4f".format(it.lat, it.lng) }
        } finally {
            conn.disconnect()
        }
    }

    /** Accepts "12.34, 56.78" (the format Google Maps copies) or "12.34 56.78". */
    fun parseCoords(text: String): Pair<Double, Double>? {
        val parts = text.trim().split(Regex("[,\\s]+")).filter { it.isNotBlank() }
        if (parts.size != 2) return null
        val a = parts[0].toDoubleOrNull() ?: return null
        val o = parts[1].toDoubleOrNull() ?: return null
        if (a !in -90.0..90.0 || o !in -180.0..180.0) return null
        return a to o
    }
}
