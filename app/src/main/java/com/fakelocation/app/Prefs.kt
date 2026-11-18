package com.fakelocation.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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
