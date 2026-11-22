package com.fakelocation.app

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.util.BoundingBox
import java.io.File
import android.location.LocationManager
import androidx.activity.result.IntentSenderRequest
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.tan
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

private val SPEED_PRESETS = listOf(
    "🚶" to 5, "🚲" to 15, "🛵" to 35, "🚗" to 60, "🛣️" to 100
)

class MainActivity : ComponentActivity() {

    private val hasPerm = mutableStateOf(false)
    private val devOn = mutableStateOf(false)
    private val mockOn = mutableStateOf(false)
    private var mapRef: MapView? = null

    private val locOn = mutableStateOf(true)
    private var afterLocationOn: (() -> Unit)? = null

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refresh()
            if (hasPerm.value && !locOn.value) requestLocationOn(null)
        }

    /** Result of Google's "Turn on location?" dialog. */
    private val locationOnLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            refresh()
            val next = afterLocationOn
            afterLocationOn = null
            if (locOn.value) next?.invoke() else toast("Location is still off")
        }

    /** Turns device Location ON with one tap (Google's system dialog), then runs [then]. */
    private fun requestLocationOn(then: (() -> Unit)?) {
        if (isLocationOn()) { then?.invoke(); return }
        afterLocationOn = then
        val req = LocationSettingsRequest.Builder()
            .addLocationRequest(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000).build())
            .setAlwaysShow(true)
            .build()
        LocationServices.getSettingsClient(this).checkLocationSettings(req)
            .addOnSuccessListener { refresh(); afterLocationOn = null; then?.invoke() }
            .addOnFailureListener { e ->
                if (e is ResolvableApiException) {
                    try {
                        locationOnLauncher.launch(IntentSenderRequest.Builder(e.resolution).build())
                        return@addOnFailureListener
                    } catch (_: Exception) {
                    }
                }
                afterLocationOn = null
                toast("Please turn on Location")
                openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            }
    }

    private fun isLocationOn(): Boolean = try {
        LocationManagerCompat.isLocationEnabled(getSystemService(LOCATION_SERVICE) as LocationManager)
    } catch (_: Exception) { true }

    /**
     * Makes this app the mock location app as automatically as Android allows:
     *  - rooted phone: done silently with "appops"
     *  - otherwise: opens Developer options right at "Select mock location app"
     *    (or About phone if Developer options are still off).
     */
    private fun autoEnableMock() {
        Thread {
            val rooted = try {
                val p = Runtime.getRuntime().exec(
                    arrayOf("su", "-c", "appops set $packageName android:mock_location allow")
                )
                p.waitFor() == 0
            } catch (_: Exception) { false }
            runOnUiThread {
                refresh()
                when {
                    rooted && mockOn.value -> toast("Mock location enabled automatically ✓")
                    !devOn.value -> {
                        toast("Tap \"Build number\" 7 times to turn on Developer options, then come back")
                        openSettings(Settings.ACTION_DEVICE_INFO_SETTINGS)
                    }
                    else -> {
                        toast("Tap \"Select mock location app\" → Fake Location, then come back")
                        openDevMockSetting()
                    }
                }
            }
        }.start()
    }

    /** Opens Developer options and highlights the "Select mock location app" row where supported. */
    private fun openDevMockSetting() {
        try {
            val i = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .putExtra(":settings:fragment_args_key", "mock_location_app")
                .putExtra(":settings:show_fragment_args", Bundle().apply {
                    putString(":settings:fragment_args_key", "mock_location_app")
                })
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (_: Exception) {
            openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashCatcher()
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
        refresh()
        if (!hasPerm.value) askPermissions() else if (!locOn.value) requestLocationOn(null)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF1565C0))) {
                AppScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        mapRef?.onResume()
    }

    override fun onPause() {
        mapRef?.onPause()
        super.onPause()
    }

    private val crashFile get() = File(filesDir, "last_crash.txt")

    /** Saves any crash so it can be shown (and copied) the next time the app opens. */
    private fun installCrashCatcher() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val file = crashFile
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                file.writeText(
                    "Fake Location crash (Android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER} ${Build.MODEL})\n" +
                        android.util.Log.getStackTraceString(e)
                )
            } catch (_: Exception) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    private fun askPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(perms.toTypedArray())
    }

    private fun refresh() {
        hasPerm.value = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        devOn.value = try {
            Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        } catch (_: Exception) { false }
        mockOn.value = isMockAppSelected(this)
        locOn.value = isLocationOn()
    }

    @Suppress("DEPRECATION")
    private fun isMockAppSelected(ctx: Context): Boolean = try {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), ctx.packageName)
        else
            ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), ctx.packageName)
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private class MapLayers(private val mv: MapView, private val ctx: Context) {
        private fun pin(res: Int, title: String, center: Boolean) = Marker(mv).apply {
            icon = ContextCompat.getDrawable(ctx, res)
            setAnchor(Marker.ANCHOR_CENTER, if (center) Marker.ANCHOR_CENTER else Marker.ANCHOR_BOTTOM)
            this.title = title
            infoWindow = null
            setOnMarkerClickListener { _, _ -> false }
        }

        val line = Polyline(mv).apply {
            outlinePaint.color = 0xFF1565C0.toInt()
            outlinePaint.strokeWidth = 12f
            setOnClickListener { _, _, _ -> false }
        }
        val single = Marker(mv).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            infoWindow = null
            setOnMarkerClickListener { _, _ -> false }
        }
        val a = pin(R.drawable.pin_a, "A", false)
        val b = pin(R.drawable.pin_b, "B", false)
        val cur = pin(R.drawable.dot, "You", true)
        var shownRoute: Route? = null
        val labels = mutableListOf<TilesOverlay>()
        val resultMarkers = mutableListOf<Marker>()
        var shownResults: List<Place>? = null
        val all get() = listOf(line, single, a, b, cur)
    }

    private fun applyStyle(mv: MapView, l: MapLayers, style: MapStyle) {
        mv.setTileSource(style.base())
        l.labels.forEach { mv.overlays.remove(it); it.onDetach(mv) }
        l.labels.clear()
        style.labels().forEachIndexed { i, src ->
            val o = TilesOverlay(MapTileProviderBasic(applicationContext, src), applicationContext)
            o.setLoadingBackgroundColor(android.graphics.Color.TRANSPARENT)
            o.setLoadingLineColor(android.graphics.Color.TRANSPARENT)
            mv.overlays.add(i, o)
            l.labels += o
        }
        mv.invalidate()
    }

    /**
     * Shows all [pts] on the map. Works out the zoom by itself and guards against a tiny map
     * (e.g. while the keyboard is still open), which used to give an invalid zoom and freeze the app.
     */
    private fun fit(mv: MapView?, pts: List<Pt>) {
        if (mv == null || pts.isEmpty()) return
        mv.postDelayed({
            try {
                val minLat = pts.minOf { it.lat }.coerceIn(-85.0, 85.0)
                val maxLat = pts.maxOf { it.lat }.coerceIn(-85.0, 85.0)
                val minLng = pts.minOf { it.lng }
                val maxLng = pts.maxOf { it.lng }
                val w = mv.width.toDouble()
                val h = mv.height.toDouble()
                var zoom = 16.0
                val lngSpan = maxLng - minLng
                fun merc(l: Double) = ln(tan(PI / 4 + Math.toRadians(l) / 2))
                val ySpan = abs(merc(maxLat) - merc(minLat))
                if (w > 80 && h > 80 && (lngSpan > 1e-6 || ySpan > 1e-6)) {
                    val tile = 256.0 * mv.resources.displayMetrics.density
                    val zx = if (lngSpan > 1e-9) log2(w * 0.75 / tile * 360.0 / lngSpan) else 18.0
                    val zy = if (ySpan > 1e-9) log2(h * 0.75 / tile * 2 * PI / ySpan) else 18.0
                    zoom = min(zx, zy)
                }
                if (zoom.isNaN() || zoom.isInfinite()) zoom = 15.0
                mv.controller.setZoom(zoom.coerceIn(3.0, 17.0))
                mv.controller.setCenter(GeoPoint((minLat + maxLat) / 2, (minLng + maxLng) / 2))
            } catch (_: Exception) {
            }
        }, 400)
    }

    @Composable
    private fun MapBtn(label: String, onClick: () -> Unit) {
        SmallFloatingActionButton(onClick = onClick, containerColor = Color.White, contentColor = Color(0xFF1565C0)) {
            Text(label, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AppScreen() {
        val ctx = this
        val prefs = remember { Prefs(ctx) }
        val screenH = LocalConfiguration.current.screenHeightDp
        var mode by rememberSaveable { mutableIntStateOf(0) } // 0 = one place, 1 = travel A → B
        var lat by rememberSaveable { mutableDoubleStateOf(prefs.lastLat) }
        var lng by rememberSaveable { mutableDoubleStateOf(prefs.lastLng) }
        var pointA by remember { mutableStateOf(prefs.getPt("a")) }
        var pointB by remember { mutableStateOf(prefs.getPt("b")) }
        var textA by remember { mutableStateOf(prefs.getName("a") ?: "") }
        var textB by remember { mutableStateOf(prefs.getName("b") ?: "") }
        var routeType by remember { mutableStateOf(prefs.routeType) }
        var route by remember { mutableStateOf<Route?>(null) }
        var routing by remember { mutableStateOf(false) }
        var routeNote by remember { mutableStateOf<String?>(null) }
        var drift by remember { mutableStateOf(prefs.drift) }
        val running by MockLocationService.running.collectAsState()
        val error by MockLocationService.error.collectAsState()
        val speed by MockLocationService.speedKmh.collectAsState()
        val paused by MockLocationService.paused.collectAsState()
        val trip by MockLocationService.trip.collectAsState()
        var query by remember { mutableStateOf("") }
        var results by remember { mutableStateOf<List<Place>>(emptyList()) }
        var resultsFor by remember { mutableIntStateOf(0) } // 0 = single place, 1 = A, 2 = B
        var searching by remember { mutableIntStateOf(-1) }
        var favorites by remember { mutableStateOf(prefs.favorites()) }
        var coordTarget by remember { mutableStateOf<Int?>(null) } // 0 single, 1 A, 2 B
        var showSave by remember { mutableStateOf(false) }
        var showSpeed by remember { mutableStateOf(false) }
        var showPresent by remember { mutableStateOf(false) }
        var showLive by remember { mutableStateOf(false) }
        var tapPoint by remember { mutableStateOf<Pt?>(null) }
        var favPick by remember { mutableStateOf<Place?>(null) }
        var panelOpen by remember { mutableStateOf(true) }
        var mapView by remember { mutableStateOf<MapView?>(null) }
        var layers by remember { mutableStateOf<MapLayers?>(null) }
        var mapStyle by remember { mutableStateOf(prefs.mapStyle) }
        var styleMenu by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val keyboard = LocalSoftwareKeyboardController.current
        val ready = hasPerm.value && mockOn.value && locOn.value
        val travelling = running && trip != null
        var crash by remember {
            mutableStateOf(try { crashFile.takeIf { it.exists() }?.readText() } catch (_: Exception) { null })
        }

        LaunchedEffect(Unit) {
            if (!MockLocationService.running.value) MockLocationService.speedKmh.value = prefs.speed
            if (MockLocationService.trip.value != null) mode = 1
        }

        // Work out the route whenever A, B or the route type changes.
        LaunchedEffect(mode, pointA, pointB, routeType) {
            val a = pointA
            val b = pointB
            if (mode != 1 || a == null || b == null) {
                route = null; routeNote = null
                return@LaunchedEffect
            }
            routing = true
            routeNote = null
            route = try {
                withContext(Dispatchers.IO) { Router.fetch(routeType, a, b) }
            } catch (_: Exception) {
                if (routeType != RouteType.STRAIGHT) routeNote = "Road route not found, using a straight line"
                Route(listOf(a, b))
            }
            routing = false
            if (!(MockLocationService.running.value && MockLocationService.trip.value != null))
                route?.let { fit(mapView, it.pts) }
        }

        fun center(la: Double, lo: Double) {
            mapView?.let {
                if (it.zoomLevelDouble < 14.0) it.controller.setZoom(15.0)
                it.controller.animateTo(GeoPoint(la, lo))
            }
        }

        fun coordText(p: Pt) = "%.5f, %.5f".format(p.lat, p.lng)

        fun moveTo(la: Double, lo: Double, c: Boolean) {
            lat = la; lng = lo
            prefs.saveLast(la, lo)
            if (c) center(la, lo)
            if (MockLocationService.running.value && MockLocationService.trip.value == null)
                MockLocationService.start(ctx, la, lo, prefs.drift)
        }

        fun tripLocked(): Boolean {
            val locked = MockLocationService.running.value && MockLocationService.trip.value != null
            if (locked) toast("Stop the trip to change A or B")
            return locked
        }

        fun mapCenter(): Pt? = mapView?.mapCenter?.let { Pt(it.latitude, it.longitude) }

        /** which: 1 = A (start), 2 = B (destination). */
        fun setAB(which: Int, p: Pt, name: String?, c: Boolean) {
            if (tripLocked()) return
            val label = name?.substringBefore(",")?.trim()?.ifEmpty { null } ?: coordText(p)
            if (which == 1) {
                pointA = p; textA = label
                prefs.putPt("a", p); prefs.putName("a", label)
            } else {
                pointB = p; textB = label
                prefs.putPt("b", p); prefs.putName("b", label)
            }
            if (c && (pointA == null || pointB == null)) center(p.lat, p.lng)
            if (name == null || PlaceSearch.parseCoords(name) != null) {
                // Look up a readable name for a point picked on the map / typed as coordinates.
                scope.launch {
                    val n = PlaceName.ofAsync(ctx, p) ?: return@launch
                    if (which == 1 && pointA == p) { textA = n; prefs.putName("a", n) }
                    if (which == 2 && pointB == p) { textB = n; prefs.putName("b", n) }
                }
            }
        }

        fun choose(target: Int, p: Place) {
            results = emptyList()
            if (target == 0) moveTo(p.lat, p.lng, true) else setAB(target, Pt(p.lat, p.lng), p.name, true)
        }

        fun doSearch(target: Int, q0: String) {
            val q = q0.trim()
            if (q.isEmpty()) { toast("Type a place name first"); return }
            keyboard?.hide()
            PlaceSearch.parseCoords(q)?.let { (a, o) -> choose(target, Place(coordText(Pt(a, o)), a, o)); return }
            searching = target
            scope.launch {
                try {
                    val r = PlaceSearch.search(ctx, q, mapCenter())
                    resultsFor = target
                    results = r
                    if (r.isEmpty()) toast("No place found")
                    else fit(mapView, r.map { Pt(it.lat, it.lng) })
                } catch (_: Exception) {
                    toast("Search failed. Check your internet.")
                }
                searching = -1
            }
        }

        fun onMapTap(p: GeoPoint) {
            if (mode == 0) moveTo(p.latitude, p.longitude, false)
            else if (!tripLocked()) tapPoint = Pt(p.latitude, p.longitude)
        }

        /** Makes sure permission, mock app and device Location are all ON, then runs [action]. */
        fun withReady(action: () -> Unit) {
            refresh()
            when {
                !hasPerm.value -> askPermissions()
                !mockOn.value -> autoEnableMock()
                !locOn.value -> requestLocationOn(action)
                else -> action()
            }
        }

        fun startTrip(after: () -> Unit = {}) {
            val r = route
            when {
                pointA == null || pointB == null -> toast("Set both A (start) and B (destination) first")
                routing || r == null -> toast("Please wait, finding route…")
                else -> withReady {
                    MockLocationService.error.value = null
                    MockLocationService.startTravel(ctx, r, MockLocationService.speedKmh.value, prefs.drift)
                    pointA?.let { center(it.lat, it.lng) }
                    after()
                }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Fake Location", fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = Color.White
                    )
                )
            }
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {

                if (!ready) SetupCard()

                error?.let {
                    Text(
                        it, color = Color.White, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().background(Color(0xFFC62828)).padding(10.dp)
                    )
                }

                TabRow(selectedTabIndex = mode) {
                    Tab(selected = mode == 0, enabled = !running, onClick = { mode = 0; results = emptyList() },
                        text = { Text("📍 One place") })
                    Tab(selected = mode == 1, enabled = !running, onClick = { mode = 1; results = emptyList() },
                        text = { Text("🚗 Travel A → B") })
                }

                if (mode == 0) {
                    SearchField(
                        tag = null, value = query, onChange = { query = it },
                        hint = "Search any place or paste lat, lng",
                        busy = searching == 0, enabled = true, onCoords = { coordTarget = 0 }
                    ) { doSearch(0, query) }
                } else {
                    SearchField(
                        tag = "A", color = Color(0xFF2E7D32), value = textA, onChange = { textA = it },
                        hint = "From: search start place",
                        busy = searching == 1, enabled = !travelling, onCoords = { coordTarget = 1 }
                    ) { doSearch(1, textA) }
                    SearchField(
                        tag = "B", color = Color(0xFFC62828), value = textB, onChange = { textB = it },
                        hint = "To: search destination",
                        busy = searching == 2, enabled = !travelling, onCoords = { coordTarget = 2 }
                    ) { doSearch(2, textB) }
                }

                // ---------------- Map ----------------
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize().clipToBounds(),
                        factory = { c ->
                            MapView(c).apply {
                                setMultiTouchControls(true)
                                isTilesScaledToDpi = true // bigger, readable labels
                                setMaxZoomLevel(20.0)
                                setMinZoomLevel(3.0)
                                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                                controller.setZoom(15.0)
                                val start = trip?.pos ?: pointA ?: Pt(lat, lng)
                                controller.setCenter(GeoPoint(start.lat, start.lng))
                                overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                                    override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                                        onMapTap(p)
                                        return true
                                    }
                                    override fun longPressHelper(p: GeoPoint): Boolean {
                                        onMapTap(p)
                                        return true
                                    }
                                }))
                                val l = MapLayers(this, c)
                                overlays.addAll(l.all)
                                overlays.add(ScaleBarOverlay(this).apply {
                                    setAlignBottom(true)
                                    setScaleBarOffset(20, 20)
                                })
                                applyStyle(this, l, mapStyle)
                                layers = l
                                mapView = this
                                mapRef = this
                            }
                        },
                        update = { mv ->
                            val l = layers
                            if (l != null) try {
                                l.single.isEnabled = mode == 0
                                l.single.position = GeoPoint(lat, lng)
                                val a = pointA
                                val b = pointB
                                l.a.isEnabled = mode == 1 && a != null
                                if (a != null) l.a.position = GeoPoint(a.lat, a.lng)
                                l.b.isEnabled = mode == 1 && b != null
                                if (b != null) l.b.position = GeoPoint(b.lat, b.lng)
                                val r = route
                                l.line.isEnabled = mode == 1 && r != null
                                if (r != null && r !== l.shownRoute) {
                                    l.line.setPoints(r.pts.map { GeoPoint(it.lat, it.lng) })
                                    l.shownRoute = r
                                }
                                val t = trip
                                l.cur.isEnabled = mode == 1 && t != null
                                if (t != null) {
                                    val gp = GeoPoint(t.pos.lat, t.pos.lng)
                                    l.cur.position = gp
                                    if (mode == 1 && !mv.boundingBox.contains(gp)) mv.controller.animateTo(gp)
                                }
                                // Every search result as an orange pin; tap a pin to choose it.
                                val res = results
                                if (res !== l.shownResults) {
                                    l.resultMarkers.forEach { mv.overlays.remove(it) }
                                    l.resultMarkers.clear()
                                    res.forEach { p ->
                                        val m = Marker(mv).apply {
                                            position = GeoPoint(p.lat, p.lng)
                                            icon = ContextCompat.getDrawable(ctx, R.drawable.pin_r)
                                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                            title = p.name
                                            infoWindow = null
                                            setOnMarkerClickListener { _, _ ->
                                                choose(resultsFor, p)
                                                true
                                            }
                                        }
                                        mv.overlays.add(m)
                                        l.resultMarkers += m
                                    }
                                    l.shownResults = res
                                }
                                mv.invalidate()
                            } catch (_: Exception) {
                            }
                        }
                    )

                    // Crosshair at the centre of the map
                    if (!travelling) {
                        Box(Modifier.align(Alignment.Center).size(30.dp, 2.dp).background(Color(0xCC000000)))
                        Box(Modifier.align(Alignment.Center).size(2.dp, 30.dp).background(Color(0xCC000000)))
                    }

                    if (results.isNotEmpty()) {
                        Card(
                            Modifier.align(Alignment.TopStart)
                                .padding(start = 8.dp, end = 60.dp, top = 8.dp)
                                .fillMaxWidth().heightIn(max = 190.dp),
                            elevation = CardDefaults.cardElevation(6.dp)
                        ) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        when (resultsFor) {
                                            1 -> "Choose start (A)"
                                            2 -> "Choose destination (B)"
                                            else -> "Choose a place"
                                        } + " · ${results.size} found",
                                        Modifier.weight(1f).padding(start = 12.dp),
                                        fontWeight = FontWeight.Bold, fontSize = 13.sp
                                    )
                                    IconButton(onClick = { results = emptyList() }) { Icon(Icons.Default.Close, "Close") }
                                }
                                results.forEach { p ->
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clickable { choose(resultsFor, p) }
                                            .padding(horizontal = 12.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.LocationOn, null, tint = Color(0xFFEF6C00))
                                        Spacer(Modifier.width(8.dp))
                                        Text(p.name, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    }

                    // Map controls
                    Column(
                        Modifier.align(Alignment.CenterEnd).padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box {
                            MapBtn("🗺") { styleMenu = true }
                            DropdownMenu(expanded = styleMenu, onDismissRequest = { styleMenu = false }) {
                                MapStyle.entries.forEach { st ->
                                    DropdownMenuItem(
                                        text = { Text(st.label + if (st == mapStyle) "  ✓" else "") },
                                        onClick = {
                                            mapStyle = st; prefs.mapStyle = st; styleMenu = false
                                            val mv = mapView
                                            val l = layers
                                            if (mv != null && l != null) applyStyle(mv, l, st)
                                        }
                                    )
                                }
                            }
                        }
                        MapBtn("+") { mapView?.controller?.zoomIn() }
                        MapBtn("−") { mapView?.controller?.zoomOut() }
                        if (mode == 1 && route != null) MapBtn("⤢") { route?.let { fit(mapView, it.pts) } }
                    }

                    // "Set here" buttons for the crosshair
                    if (!travelling && results.isEmpty()) {
                        Row(
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (mode == 0) {
                                Pill("📍 Set here", MaterialTheme.colorScheme.primary) {
                                    mapCenter()?.let { moveTo(it.lat, it.lng, false) }
                                }
                            } else {
                                Pill("Set A here", Color(0xFF2E7D32)) { mapCenter()?.let { setAB(1, it, null, false) } }
                                Pill("Set B here", Color(0xFFC62828)) { mapCenter()?.let { setAB(2, it, null, false) } }
                            }
                        }
                    }

                    Text(
                        mapStyle.attribution, fontSize = 9.sp, color = Color.DarkGray,
                        modifier = Modifier.align(Alignment.BottomEnd)
                            .background(Color.White.copy(alpha = 0.7f)).padding(horizontal = 4.dp)
                    )

                    if (running) {
                        Text(
                            if (travelling) "● LIVE TRIP ON" else "● FAKE LOCATION ON",
                            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                                .background(Color(0xFF2E7D32), RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                // ---------------- Bottom panel ----------------
                Surface(shadowElevation = 8.dp) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            if (panelOpen) "▼  Hide controls" else "▲  Show controls",
                            fontSize = 12.sp, color = Color.Gray,
                            modifier = Modifier.fillMaxWidth().clickable { panelOpen = !panelOpen }
                                .padding(vertical = 4.dp),
                            textAlign = TextAlign.Center
                        )
                        Column(
                            Modifier.fillMaxWidth()
                                .heightIn(max = (screenH * 0.42f).dp)
                                .verticalScroll(rememberScrollState())
                                .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (panelOpen) {
                                if (mode == 0) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "%.6f, %.6f".format(lat, lng),
                                            Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                                        )
                                        IconButton(onClick = { coordTarget = 0 }) { Icon(Icons.Default.Edit, "Enter latitude and longitude") }
                                        IconButton(onClick = { showSave = true }) { Icon(Icons.Default.Star, "Save place") }
                                    }
                                } else {
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        items(RouteType.entries) { t ->
                                            FilterChip(
                                                selected = routeType == t,
                                                enabled = !travelling,
                                                onClick = { routeType = t; prefs.routeType = t },
                                                label = { Text(t.label) }
                                            )
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Speed", fontSize = 14.sp)
                                        Slider(
                                            value = speed.toFloat().coerceIn(1f, 200f),
                                            onValueChange = { MockLocationService.speedKmh.value = it.roundToInt().toDouble() },
                                            onValueChangeFinished = { prefs.speed = MockLocationService.speedKmh.value },
                                            valueRange = 1f..200f,
                                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                                        )
                                        Text(
                                            "%.0f km/h ✎".format(speed), fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.clickable { showSpeed = true }
                                        )
                                    }
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        items(SPEED_PRESETS) { (label, kmh) ->
                                            AssistChip(
                                                onClick = {
                                                    MockLocationService.speedKmh.value = kmh.toDouble()
                                                    prefs.speed = kmh.toDouble()
                                                },
                                                label = { Text("$label $kmh") }
                                            )
                                        }
                                    }

                                    val t = trip
                                    if (travelling && t != null) {
                                        LinearProgressIndicator(
                                            progress = { if (t.total > 0) (t.covered / t.total).toFloat() else 1f },
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                        )
                                        val left = t.total - t.covered
                                        Text(
                                            if (t.arrived) "✅ Arrived at B · ${Geo.formatKm(t.total)} travelled"
                                            else "${Geo.formatKm(t.covered)} of ${Geo.formatKm(t.total)} · " +
                                                "${Geo.formatTime(left / (speed / 3.6))} left" +
                                                if (paused) " · PAUSED" else "",
                                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                                        )
                                    } else {
                                        val r = route
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                when {
                                                    pointA == null && pointB == null -> "Set A (start) and B (destination): search above, tap the map, or use Set A / B here"
                                                    pointA == null -> "Now set A (start)"
                                                    pointB == null -> "Now set B (destination)"
                                                    routing -> "Finding route…"
                                                    r != null -> "${Geo.formatKm(r.length)} · about " +
                                                        Geo.formatTime(r.length / (speed / 3.6)) + " at %.0f km/h".format(speed)
                                                    else -> ""
                                                },
                                                Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                                            )
                                            if (pointA != null || pointB != null) {
                                                TextButton(onClick = {
                                                    val a = pointA; val ta = textA
                                                    pointA = pointB; textA = textB
                                                    pointB = a; textB = ta
                                                    prefs.putPt("a", pointA); prefs.putName("a", textA)
                                                    prefs.putPt("b", pointB); prefs.putName("b", textB)
                                                }) { Text("⇅ Swap") }
                                            }
                                        }
                                        routeNote?.let { Text(it, fontSize = 12.sp, color = Color(0xFFE65100)) }
                                    }
                                }

                                if (favorites.isNotEmpty()) {
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        items(favorites) { f ->
                                            AssistChip(
                                                onClick = {
                                                    if (mode == 0) moveTo(f.lat, f.lng, true)
                                                    else if (!tripLocked()) favPick = f
                                                },
                                                label = { Text("⭐ " + f.name, maxLines = 1) },
                                                trailingIcon = {
                                                    Icon(
                                                        Icons.Default.Close, "Remove",
                                                        Modifier.size(16.dp).clickable {
                                                            favorites = favorites - f
                                                            prefs.saveFavorites(favorites)
                                                        }
                                                    )
                                                }
                                            )
                                        }
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (mode == 0) "Natural movement (tiny GPS wobble)"
                                        else "Natural driving (speed varies ±10%)",
                                        Modifier.weight(1f), fontSize = 13.sp
                                    )
                                    Switch(checked = drift, onCheckedChange = {
                                        drift = it; prefs.drift = it
                                        if (running && !travelling) MockLocationService.start(ctx, lat, lng, it)
                                    })
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { showPresent = true },
                                        modifier = Modifier.weight(1f).height(48.dp),
                                        contentPadding = PaddingValues(horizontal = 6.dp)
                                    ) { Text("📍 Send present\nlocation", fontSize = 13.sp, lineHeight = 15.sp, textAlign = TextAlign.Center) }
                                    OutlinedButton(
                                        onClick = {
                                            if (mode == 0) {
                                                mode = 1
                                                toast("Live location: set A and B, then tap Send live location")
                                            } else showLive = true
                                        },
                                        modifier = Modifier.weight(1f).height(48.dp),
                                        contentPadding = PaddingValues(horizontal = 6.dp),
                                        enabled = !(running && !travelling)
                                    ) { Text("🔴 Send live location\n(moving A → B)", fontSize = 13.sp, lineHeight = 15.sp, textAlign = TextAlign.Center) }
                                }
                            }

                            // Main action button (always visible)
                            if (mode == 0) {
                                Button(
                                    onClick = {
                                        if (running) MockLocationService.stop(ctx)
                                        else withReady {
                                            MockLocationService.error.value = null
                                            MockLocationService.start(ctx, lat, lng, prefs.drift)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().height(50.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (running) Color(0xFFC62828) else MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Text(if (running) "STOP FAKE LOCATION" else "START FAKE LOCATION", fontSize = 16.sp)
                                }
                            } else if (travelling) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (trip?.arrived != true) {
                                        Button(
                                            onClick = { MockLocationService.paused.value = !paused },
                                            modifier = Modifier.weight(1f).height(50.dp)
                                        ) { Text(if (paused) "RESUME" else "PAUSE", fontSize = 16.sp) }
                                    }
                                    Button(
                                        onClick = { MockLocationService.stop(ctx) },
                                        modifier = Modifier.weight(1f).height(50.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                                    ) { Text("STOP TRIP", fontSize = 16.sp) }
                                }
                            } else {
                                Button(
                                    onClick = { startTrip() },
                                    modifier = Modifier.fillMaxWidth().height(50.dp)
                                ) { Text("START TRIP  A → B", fontSize = 16.sp) }
                            }
                        }
                    }
                }
            }
        }

        // ---------------- Dialogs ----------------

        crash?.let { text ->
            val clipboard = LocalClipboardManager.current
            AlertDialog(
                onDismissRequest = { crashFile.delete(); crash = null },
                title = { Text("The app closed last time") },
                text = {
                    Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                        Text("Copy this and send it to the developer:", fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(text, fontSize = 10.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(text))
                        toast("Copied")
                        crashFile.delete(); crash = null
                    }) { Text("Copy") }
                },
                dismissButton = { TextButton(onClick = { crashFile.delete(); crash = null }) { Text("Close") } }
            )
        }

        tapPoint?.let { p ->
            AlertDialog(
                onDismissRequest = { tapPoint = null },
                title = { Text("Set this point as") },
                text = { Text(coordText(p)) },
                confirmButton = {
                    TextButton(onClick = { setAB(2, p, null, false); tapPoint = null }) {
                        Text("B  Destination", color = Color(0xFFC62828), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { setAB(1, p, null, false); tapPoint = null }) {
                        Text("A  Start", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        favPick?.let { f ->
            AlertDialog(
                onDismissRequest = { favPick = null },
                title = { Text("Use \"${f.name}\" as") },
                confirmButton = {
                    TextButton(onClick = { setAB(2, Pt(f.lat, f.lng), f.name, true); favPick = null }) {
                        Text("B  Destination", color = Color(0xFFC62828), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { setAB(1, Pt(f.lat, f.lng), f.name, true); favPick = null }) {
                        Text("A  Start", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        coordTarget?.let { target ->
            val cur: Pt? = when (target) { 0 -> Pt(lat, lng); 1 -> pointA; else -> pointB }
            var latText by remember(target) { mutableStateOf(cur?.let { "%.6f".format(Locale.US, it.lat) } ?: "") }
            var lngText by remember(target) { mutableStateOf(cur?.let { "%.6f".format(Locale.US, it.lng) } ?: "") }
            var paste by remember(target) { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { coordTarget = null },
                title = {
                    Text(
                        when (target) {
                            1 -> "A (start): latitude & longitude"
                            2 -> "B (destination): latitude & longitude"
                            else -> "Latitude & longitude"
                        }
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            latText, { latText = it }, singleLine = true,
                            label = { Text("Latitude (−90 to 90)") },
                            placeholder = { Text("e.g. 26.144518") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                        )
                        OutlinedTextField(
                            lngText, { lngText = it }, singleLine = true,
                            label = { Text("Longitude (−180 to 180)") },
                            placeholder = { Text("e.g. 91.736237") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                        )
                        Text("…or paste any format / Google Maps link:", fontSize = 12.sp, color = Color.Gray)
                        OutlinedTextField(
                            paste,
                            {
                                paste = it
                                PlaceSearch.parseCoords(it)?.let { (a, o) ->
                                    latText = "%.6f".format(Locale.US, a)
                                    lngText = "%.6f".format(Locale.US, o)
                                }
                            },
                            singleLine = true,
                            placeholder = { Text("26°08'40\"N 91°44'10\"E", fontSize = 13.sp) }
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val a = latText.trim().replace(',', '.').toDoubleOrNull()
                        val o = lngText.trim().replace(',', '.').toDoubleOrNull()
                        val c = if (a != null && o != null && a in -90.0..90.0 && o in -180.0..180.0) a to o
                        else PlaceSearch.parseCoords("${latText.trim()} ${lngText.trim()}")
                        if (c == null) toast("Invalid latitude / longitude") else {
                            coordTarget = null
                            if (target == 0) moveTo(c.first, c.second, true)
                            else {
                                setAB(target, Pt(c.first, c.second), null, false)
                                center(c.first, c.second)
                            }
                        }
                    }) { Text("Go") }
                },
                dismissButton = { TextButton(onClick = { coordTarget = null }) { Text("Cancel") } }
            )
        }

        if (showPresent) {
            // Where the person is right now: the chosen place, or the current point of the trip.
            val now: Pt? = when {
                mode == 0 -> Pt(lat, lng)
                trip != null -> trip?.pos
                else -> pointA
            }
            AlertDialog(
                onDismissRequest = { showPresent = false },
                title = { Text("Send present location") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(now?.let { coordText(it) } ?: "Set a place first", fontWeight = FontWeight.Bold)
                        Text(
                            "Sends a Google Maps pin of this place. Or, with fake location ON, open the " +
                                "WhatsApp chat → 📎 → Location → \"Send your current location\".",
                            fontSize = 13.sp
                        )
                        Text(
                            "WhatsApp can't find location or shows the real one? Give WhatsApp Location " +
                                "permission (Allow all the time) and close & reopen WhatsApp.",
                            fontSize = 12.sp, color = Color(0xFFE65100)
                        )
                        TextButton(
                            onClick = { Share.openWhatsAppPermissions(ctx) },
                            contentPadding = PaddingValues(0.dp)
                        ) { Text("Open WhatsApp permissions") }
                    }
                },
                confirmButton = {
                    TextButton(enabled = now != null, onClick = {
                        showPresent = false
                        if (now != null && !Share.sendLocation(ctx, now, true)) toast("Could not open WhatsApp")
                    }) { Text("WhatsApp") }
                },
                dismissButton = {
                    TextButton(enabled = now != null, onClick = {
                        showPresent = false
                        if (now != null) Share.sendLocation(ctx, now, false)
                    }) { Text("Other apps") }
                }
            )
        }

        if (showLive) {
            AlertDialog(
                onDismissRequest = { showLive = false },
                title = { Text("Send live location (A → B)") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (travelling) "Your trip is running. Now share it in WhatsApp:"
                            else "The trip will start from A at %.0f km/h, then WhatsApp opens:".format(speed),
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                        )
                        Text("1. Open the person's chat", fontSize = 13.sp)
                        Text("2. Tap 📎 → Location → Share live location", fontSize = 13.sp)
                        Text("3. Choose how long (15 min / 1 h / 8 h) → Send", fontSize = 13.sp)
                        Text(
                            "They will see you moving from A to B at your speed. Keep the trip running; " +
                                "WhatsApp updates their map every few seconds.",
                            fontSize = 12.sp, color = Color.Gray
                        )
                        Text(
                            "WhatsApp can't find location or shows the real one? Give WhatsApp Location " +
                                "permission (Allow all the time) and close & reopen WhatsApp.",
                            fontSize = 12.sp, color = Color(0xFFE65100)
                        )
                        TextButton(
                            onClick = { Share.openWhatsAppPermissions(ctx) },
                            contentPadding = PaddingValues(0.dp)
                        ) { Text("Open WhatsApp permissions") }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showLive = false
                        val open = {
                            // Give WhatsApp a moment to receive the fake position before opening it.
                            mapView?.postDelayed({
                                if (!Share.openWhatsApp(ctx)) toast("WhatsApp is not installed")
                            }, 1500) ?: Unit
                        }
                        if (travelling) withReady { open() } else startTrip { open() }
                    }) { Text(if (travelling) "Open WhatsApp" else "Start trip & open WhatsApp") }
                },
                dismissButton = { TextButton(onClick = { showLive = false }) { Text("Cancel") } }
            )
        }

        if (showSpeed) {
            var text by remember { mutableStateOf("%.0f".format(speed)) }
            AlertDialog(
                onDismissRequest = { showSpeed = false },
                title = { Text("Set speed (km/h)") },
                text = {
                    OutlinedTextField(
                        text, { text = it }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val v = text.trim().toDoubleOrNull()
                        if (v == null || v <= 0 || v > 1000) toast("Enter a speed between 1 and 1000") else {
                            MockLocationService.speedKmh.value = v
                            prefs.speed = v
                            showSpeed = false
                        }
                    }) { Text("Set") }
                },
                dismissButton = { TextButton(onClick = { showSpeed = false }) { Text("Cancel") } }
            )
        }

        if (showSave) {
            var name by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showSave = false },
                title = { Text("Save this place") },
                text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Name, e.g. Office") }) },
                confirmButton = {
                    TextButton(onClick = {
                        val n = name.trim().ifEmpty { "Place ${favorites.size + 1}" }
                        favorites = favorites + Place(n, lat, lng)
                        prefs.saveFavorites(favorites)
                        showSave = false
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { showSave = false }) { Text("Cancel") } }
            )
        }
    }

    @Composable
    private fun SearchField(
        tag: String?,
        color: Color = Color.Unspecified,
        value: String,
        onChange: (String) -> Unit,
        hint: String,
        busy: Boolean,
        enabled: Boolean,
        onCoords: () -> Unit,
        onSearch: () -> Unit
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
            singleLine = true,
            enabled = enabled,
            placeholder = { Text(hint, fontSize = 14.sp) },
            leadingIcon = tag?.let {
                {
                    Text(
                        it, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.size(26.dp).background(color, RoundedCornerShape(13.dp))
                            .wrapContentHeight()
                    )
                }
            },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = onCoords, enabled = enabled,
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) { Text("Lat/Lng", fontSize = 11.sp) }
                    if (busy) CircularProgressIndicator(Modifier.padding(end = 12.dp).size(22.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = onSearch, enabled = enabled) { Icon(Icons.Default.Search, "Search") }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() })
        )
    }

    @Composable
    private fun Pill(label: String, color: Color, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = color),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            elevation = ButtonDefaults.buttonElevation(4.dp)
        ) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
    }

    @Composable
    private fun SetupCard() {
        Card(
            Modifier.fillMaxWidth().padding(10.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1))
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("One-time setup", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                SetupStep(1, "Allow location permission", hasPerm.value, "Allow") { askPermissions() }
                SetupStep(2, "Turn ON device Location", locOn.value, "Turn on") { requestLocationOn(null) }
                SetupStep(
                    3, "Turn on Developer options: About phone → tap \"Build number\" 7 times",
                    devOn.value, "Open"
                ) { openSettings(Settings.ACTION_DEVICE_INFO_SETTINGS) }
                SetupStep(
                    4, "Developer options → \"Select mock location app\" → Fake Location",
                    mockOn.value, "Auto"
                ) { autoEnableMock() }
                if (!mockOn.value) {
                    val clip = LocalClipboardManager.current
                    val cmd = "adb shell appops set $packageName android:mock_location allow"
                    Text(
                        "Have a PC? Run this once to enable it without menus (tap to copy):",
                        fontSize = 11.sp, color = Color.Gray
                    )
                    Text(
                        cmd, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { clip.setText(AnnotatedString(cmd)); toast("Copied") }
                    )
                }
            }
        }
    }

    @Composable
    private fun SetupStep(n: Int, text: String, done: Boolean, button: String, onClick: () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (done) Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF2E7D32))
            else Text("$n.", fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, Modifier.weight(1f), fontSize = 13.sp)
            if (!done) TextButton(onClick = onClick) { Text(button) }
        }
    }
}
