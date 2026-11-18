package com.fakelocation.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.random.Random

/**
 * Feeds a fixed (fake) position into Android's GPS, network and Google fused
 * location providers every second, so every app — WhatsApp live location,
 * Google Maps, etc. — sees the chosen place.
 */
class MockLocationService : Service() {

    companion object {
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LNG = "lng"
        private const val EXTRA_DRIFT = "drift"
        private const val EXTRA_TRAVEL = "travel"
        private const val ACTION_STOP = "com.fakelocation.app.STOP"
        private const val CHANNEL = "fake_location"
        private const val NOTIF_ID = 7

        val running = MutableStateFlow(false)
        val error = MutableStateFlow<String?>(null)

        /** Travel mode: live speed (km/h) and pause, changeable while travelling. */
        val speedKmh = MutableStateFlow(40.0)
        val paused = MutableStateFlow(false)
        /** Progress of the current trip, or null when faking a single place. */
        val trip = MutableStateFlow<TripProgress?>(null)

        @Volatile
        private var pendingRoute: Route? = null

        fun startTravel(ctx: Context, route: Route, kmh: Double, natural: Boolean) {
            pendingRoute = route
            speedKmh.value = kmh
            paused.value = false
            val i = Intent(ctx, MockLocationService::class.java)
                .putExtra(EXTRA_TRAVEL, true)
                .putExtra(EXTRA_DRIFT, natural)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun start(ctx: Context, lat: Double, lng: Double, drift: Boolean) {
            val i = Intent(ctx, MockLocationService::class.java)
                .putExtra(EXTRA_LAT, lat)
                .putExtra(EXTRA_LNG, lng)
                .putExtra(EXTRA_DRIFT, drift)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MockLocationService::class.java))
        }
    }

    private lateinit var lm: LocationManager
    private var fused: FusedLocationProviderClient? = null
    private val handler = Handler(Looper.getMainLooper())
    private val providers = mutableListOf<String>()
    private var lat = 0.0
    private var lng = 0.0
    private var drift = true
    private var ready = false
    private var route: Route? = null
    private var covered = 0.0
    private var lastTick = 0L
    private var speedFactor = 1.0
    private var tickCount = 0
    private var curBearing = 0f
    private var curSpeed = 0f

    private val tick = object : Runnable {
        override fun run() {
            push()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val prefs = Prefs(this)
        lat = intent?.getDoubleExtra(EXTRA_LAT, prefs.lastLat) ?: prefs.lastLat
        lng = intent?.getDoubleExtra(EXTRA_LNG, prefs.lastLng) ?: prefs.lastLng
        drift = intent?.getBooleanExtra(EXTRA_DRIFT, prefs.drift) ?: prefs.drift
        if (intent?.getBooleanExtra(EXTRA_TRAVEL, false) == true && pendingRoute != null) {
            route = pendingRoute
            pendingRoute = null
            covered = 0.0
            speedFactor = 1.0
            val start = route!!.pts.first()
            lat = start.lat; lng = start.lng
            trip.value = TripProgress(start, 0.0, route!!.length, false)
        } else {
            route = null
            trip.value = null
        }
        lastTick = SystemClock.elapsedRealtime()

        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            )
        } catch (e: Exception) {
            error.value = "Please allow the Location permission first."
            stopSelf()
            return START_NOT_STICKY
        }

        if (!ready) {
            try {
                setupProviders()
                ready = true
            } catch (e: SecurityException) {
                error.value = "Select \"Fake Location\" as the mock location app in Developer options first."
                stopSelf()
                return START_NOT_STICKY
            }
            handler.post(tick)
        } else {
            push()
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        }
        error.value = null
        running.value = true
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun setupProviders() {
        providers.clear()
        val wanted = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (p in wanted) {
            try { lm.removeTestProvider(p) } catch (_: Exception) { }
            try {
                lm.addTestProvider(
                    p, false, p == LocationManager.GPS_PROVIDER, false, false,
                    true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE
                )
                lm.setTestProviderEnabled(p, true)
                providers += p
            } catch (e: SecurityException) {
                throw e
            } catch (_: Exception) {
                // Some phones don't allow mocking one of the providers; skip it.
            }
        }
        if (providers.isEmpty()) throw SecurityException("no providers")

        // Google Play Services fused location (used by WhatsApp, Maps, most apps).
        try {
            val client = LocationServices.getFusedLocationProviderClient(this)
            client.setMockMode(true)
                .addOnSuccessListener { fused = client }
                .addOnFailureListener { fused = null }
        } catch (_: Exception) {
            fused = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun push() {
        val now = SystemClock.elapsedRealtime()
        val dt = ((now - lastTick) / 1000.0).coerceIn(0.0, 5.0)
        lastTick = now
        curBearing = 0f
        curSpeed = 0f
        val r = route
        if (r != null) {
            var mps = 0.0
            if (!paused.value && covered < r.length) {
                var kmh = speedKmh.value
                if (drift) {
                    // Real driving/walking speed is never perfectly constant: vary ±10% smoothly.
                    speedFactor = (speedFactor + (Random.nextDouble() - 0.5) * 0.04).coerceIn(0.9, 1.1)
                    kmh *= speedFactor
                }
                mps = kmh / 3.6
                covered = (covered + mps * dt).coerceAtMost(r.length)
            }
            val (p, br) = r.at(covered)
            val arrived = covered >= r.length
            lat = p.lat; lng = p.lng
            curBearing = br
            curSpeed = if (arrived) 0f else mps.toFloat()
            trip.value = TripProgress(p, covered, r.length, arrived)
            if (tickCount++ % 3 == 0) {
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
            }
        }
        var la = lat
        var lo = lng
        if (drift && curSpeed == 0f) {
            // ~1–2 m of natural GPS wobble so a live location looks real.
            la += (Random.nextDouble() - 0.5) * 0.00003
            lo += (Random.nextDouble() - 0.5) * 0.00003
        }
        for (p in providers) {
            try { lm.setTestProviderLocation(p, makeLocation(p, la, lo)) } catch (_: Exception) { }
        }
        fused?.let { c ->
            try { c.setMockLocation(makeLocation("fused", la, lo)) } catch (_: Exception) { }
        }
    }

    private fun makeLocation(provider: String, la: Double, lo: Double) = Location(provider).apply {
        latitude = la
        longitude = lo
        altitude = 40.0 + Random.nextDouble() * 2
        accuracy = 3f + Random.nextFloat() * 3f
        speed = curSpeed
        bearing = curBearing
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        if (Build.VERSION.SDK_INT >= 26) {
            verticalAccuracyMeters = 3f
            speedAccuracyMetersPerSecond = 0.5f
            bearingAccuracyDegrees = 10f
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Fake location running", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MockLocationService::class.java).setAction(ACTION_STOP), flags
        )
        val t = trip.value
        val title: String
        val text: String
        if (route != null && t != null) {
            title = when {
                t.arrived -> "Arrived at B"
                paused.value -> "Trip paused"
                else -> "Travelling at %.0f km/h".format(speedKmh.value)
            }
            val left = t.total - t.covered
            text = if (t.arrived) "%.5f, %.5f".format(lat, lng)
            else "${Geo.formatKm(left)} left · about ${Geo.formatTime(left / (speedKmh.value / 3.6))}"
        } else {
            title = "Fake location is ON"
            text = "%.5f, %.5f".format(lat, lng)
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_pin)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        handler.removeCallbacks(tick)
        for (p in providers) {
            try { lm.setTestProviderEnabled(p, false) } catch (_: Exception) { }
            try { lm.removeTestProvider(p) } catch (_: Exception) { }
        }
        providers.clear()
        try { fused?.setMockMode(false) } catch (_: Exception) { }
        fused = null
        ready = false
        route = null
        trip.value = null
        paused.value = false
        running.value = false
        super.onDestroy()
    }
}

data class TripProgress(val pos: Pt, val covered: Double, val total: Double, val arrived: Boolean)
