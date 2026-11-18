package com.fakelocation.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Pt(val lat: Double, val lng: Double)

enum class RouteType(val label: String, val server: String?) {
    ROADS("Roads (car)", "routed-car"),
    WALK("Walking", "routed-foot"),
    BIKE("Cycling", "routed-bike"),
    STRAIGHT("Straight line", null)
}

object Geo {
    private const val R = 6371000.0

    fun dist(a: Pt, b: Pt): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * R * atan2(sqrt(h), sqrt(1 - h))
    }

    fun bearing(a: Pt, b: Pt): Float {
        val la1 = Math.toRadians(a.lat)
        val la2 = Math.toRadians(b.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val y = sin(dLng) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLng)
        return ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
    }

    fun formatKm(m: Double): String = if (m < 1000) "%.0f m".format(m) else "%.1f km".format(m / 1000)

    fun formatTime(sec: Double): String {
        val s = sec.toLong()
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min"
            else -> "%d h %02d min".format(s / 3600, (s % 3600) / 60)
        }
    }
}

/** A path made of points, with fast lookup of "where am I after travelling d metres". */
class Route(val pts: List<Pt>) {
    private val cum = DoubleArray(pts.size)

    init {
        for (i in 1 until pts.size) cum[i] = cum[i - 1] + Geo.dist(pts[i - 1], pts[i])
    }

    val length: Double get() = if (pts.isEmpty()) 0.0 else cum.last()

    /** Position and heading after [d] metres from the start. */
    fun at(d: Double): Pair<Pt, Float> {
        if (pts.size < 2) return pts.first() to 0f
        val dd = d.coerceIn(0.0, length)
        var lo = 0
        var hi = pts.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (cum[mid] <= dd) lo = mid else hi = mid
        }
        val seg = cum[hi] - cum[lo]
        val t = if (seg <= 0.0) 0.0 else (dd - cum[lo]) / seg
        val a = pts[lo]
        val b = pts[hi]
        val p = Pt(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
        return p to Geo.bearing(a, b)
    }
}

/** Gets a real road route from OpenStreetMap routing (OSRM, free, no key). Call off the main thread. */
object Router {
    fun fetch(type: RouteType, a: Pt, b: Pt): Route {
        val server = type.server ?: return Route(listOf(a, b))
        val coords = "${a.lng},${a.lat};${b.lng},${b.lat}"
        val urls = buildList {
            add("https://routing.openstreetmap.de/$server/route/v1/driving/$coords?overview=full&geometries=geojson")
            if (type == RouteType.ROADS)
                add("https://router.project-osrm.org/route/v1/driving/$coords?overview=full&geometries=geojson")
        }
        var last: Exception? = null
        for (u in urls) {
            try {
                return parse(get(u), a, b)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: Exception("No route")
    }

    private fun get(u: String): String {
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "FakeLocationApp/1.0 (Android)")
        conn.connectTimeout = 10000
        conn.readTimeout = 20000
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(text: String, a: Pt, b: Pt): Route {
        val root = JSONObject(text)
        if (root.optString("code") != "Ok") throw Exception(root.optString("message", "No route"))
        val coords = root.getJSONArray("routes").getJSONObject(0)
            .getJSONObject("geometry").getJSONArray("coordinates")
        val pts = ArrayList<Pt>(coords.length() + 2)
        pts += a
        for (i in 0 until coords.length()) {
            val c = coords.getJSONArray(i)
            pts += Pt(c.getDouble(1), c.getDouble(0))
        }
        pts += b
        return Route(pts)
    }
}
