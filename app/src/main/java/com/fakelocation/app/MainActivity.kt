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
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import kotlin.math.roundToInt

private val SPEED_PRESETS = listOf(
    "🚶" to 5, "🚲" to 15, "🛵" to 35, "🚗" to 60, "🛣️" to 100
)

class MainActivity : ComponentActivity() {

    private val hasPerm = mutableStateOf(false)
    private val devOn = mutableStateOf(false)
    private val mockOn = mutableStateOf(false)
    private var mapRef: MapView? = null

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
        refresh()
        if (!hasPerm.value) askPermissions()
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

    private class MapLayers(mv: MapView, ctx: Context) {
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
        val all get() = listOf(line, single, a, b, cur)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AppScreen() {
        val ctx = this
        val prefs = remember { Prefs(ctx) }
        var mode by rememberSaveable { mutableIntStateOf(0) } // 0 = one place, 1 = travel A → B
        var lat by rememberSaveable { mutableDoubleStateOf(prefs.lastLat) }
        var lng by rememberSaveable { mutableDoubleStateOf(prefs.lastLng) }
        var pointA by remember { mutableStateOf(prefs.getPt("a")) }
        var pointB by remember { mutableStateOf(prefs.getPt("b")) }
        var active by remember { mutableIntStateOf(if (prefs.getPt("a") == null) 0 else 1) }
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
        var searching by remember { mutableStateOf(false) }
        var favorites by remember { mutableStateOf(prefs.favorites()) }
        var showCoords by remember { mutableStateOf(false) }
        var showSave by remember { mutableStateOf(false) }
        var showSpeed by remember { mutableStateOf(false) }
        var mapView by remember { mutableStateOf<MapView?>(null) }
        var layers by remember { mutableStateOf<MapLayers?>(null) }
        val scope = rememberCoroutineScope()
        val keyboard = LocalSoftwareKeyboardController.current
        val ready = hasPerm.value && mockOn.value
        val travelling = running && trip != null

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
        }

        fun center(la: Double, lo: Double) {
            mapView?.let {
                if (it.zoomLevelDouble < 14.0) it.controller.setZoom(15.0)
                it.controller.animateTo(GeoPoint(la, lo))
            }
        }

        fun moveTo(la: Double, lo: Double, c: Boolean) {
            lat = la; lng = lo
            prefs.saveLast(la, lo)
            if (c) center(la, lo)
            if (MockLocationService.running.value && MockLocationService.trip.value == null)
                MockLocationService.start(ctx, la, lo, prefs.drift)
        }

        fun setPoint(p: Pt, c: Boolean) {
            if (MockLocationService.running.value && MockLocationService.trip.value != null) {
                toast("Stop the trip to change A or B"); return
            }
            if (active == 0) {
                pointA = p; prefs.putPt("a", p)
                if (pointB == null) active = 1
            } else {
                pointB = p; prefs.putPt("b", p)
            }
            if (c) center(p.lat, p.lng)
        }

        fun pick(la: Double, lo: Double, c: Boolean) {
            if (mode == 0) moveTo(la, lo, c) else setPoint(Pt(la, lo), c)
        }

        fun doSearch() {
            val q = query.trim()
            if (q.isEmpty()) return
            keyboard?.hide()
            PlaceSearch.parseCoords(q)?.let { (a, o) -> pick(a, o, true); return }
            searching = true
            scope.launch {
                try {
                    results = withContext(Dispatchers.IO) { PlaceSearch.search(q) }
                    if (results.isEmpty()) toast("No place found")
                } catch (_: Exception) {
                    toast("Search failed. Check your internet.")
                }
                searching = false
            }
        }

        fun checkReady(): Boolean = when {
            !hasPerm.value -> { askPermissions(); false }
            !mockOn.value -> { toast("Finish the setup first: select Fake Location as mock location app."); false }
            else -> true
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
                    Tab(selected = mode == 0, enabled = !running, onClick = { mode = 0 },
                        text = { Text("📍 One place") })
                    Tab(selected = mode == 1, enabled = !running, onClick = { mode = 1 },
                        text = { Text("🚗 Travel A → B") })
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                    singleLine = true,
                    placeholder = {
                        Text(
                            if (mode == 0) "Search any place or paste lat, lng"
                            else "Search place for ${if (active == 0) "A (start)" else "B (destination)"}"
                        )
                    },
                    trailingIcon = {
                        if (searching) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        else IconButton(onClick = { doSearch() }) { Icon(Icons.Default.Search, "Search") }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { doSearch() })
                )

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { c ->
                            MapView(c).apply {
                                setTileSource(TileSourceFactory.MAPNIK)
                                setMultiTouchControls(true)
                                controller.setZoom(15.0)
                                val start = trip?.pos ?: pointA ?: Pt(lat, lng)
                                controller.setCenter(GeoPoint(start.lat, start.lng))
                                overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                                    override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                                        pick(p.latitude, p.longitude, false)
                                        return true
                                    }
                                    override fun longPressHelper(p: GeoPoint): Boolean = false
                                }))
                                val l = MapLayers(this, c)
                                overlays.addAll(l.all)
                                layers = l
                                mapView = this
                                mapRef = this
                            }
                        },
                        update = { mv ->
                            val l = layers
                            if (l != null) {
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
                                    // Keep the moving dot on screen.
                                    if (mode == 1 && !mv.boundingBox.contains(gp)) mv.controller.animateTo(gp)
                                }
                                mv.invalidate()
                            }
                        }
                    )

                    if (results.isNotEmpty()) {
                        Card(
                            Modifier.padding(10.dp).fillMaxWidth().heightIn(max = 300.dp),
                            elevation = CardDefaults.cardElevation(6.dp)
                        ) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Results", Modifier.weight(1f).padding(12.dp), fontWeight = FontWeight.Bold)
                                    IconButton(onClick = { results = emptyList() }) { Icon(Icons.Default.Close, "Close") }
                                }
                                results.forEach { p ->
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clickable { results = emptyList(); pick(p.lat, p.lng, true) }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.LocationOn, null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(8.dp))
                                        Text(p.name, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    }

                    if (running) {
                        Text(
                            if (travelling) "● LIVE TRIP ON" else "● FAKE LOCATION ON",
                            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                                .background(Color(0xFF2E7D32), RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                // Bottom panel
                Surface(shadowElevation = 8.dp) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (mode == 0) {
                            Text("Tap the map to choose a place", fontSize = 12.sp, color = Color.Gray)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "%.6f, %.6f".format(lat, lng),
                                    Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold
                                )
                                IconButton(onClick = { showCoords = true }) { Icon(Icons.Default.Edit, "Enter coordinates") }
                                IconButton(onClick = { showSave = true }) { Icon(Icons.Default.Star, "Save place") }
                            }
                        } else {
                            Text(
                                if (travelling) "Trip in progress" else "Tap A or B, then tap the map or search",
                                fontSize = 12.sp, color = Color.Gray
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Slot("A  Start", Color(0xFF2E7D32), pointA, active == 0, Modifier.weight(1f)) { active = 0 }
                                TextButton(onClick = {
                                    if (!travelling) {
                                        val a = pointA; pointA = pointB; pointB = a
                                        prefs.putPt("a", pointA); prefs.putPt("b", pointB)
                                    }
                                }) { Text("⇄", fontSize = 20.sp) }
                                Slot("B  Destination", Color(0xFFC62828), pointB, active == 1, Modifier.weight(1f)) { active = 1 }
                                IconButton(onClick = { showCoords = true }) { Icon(Icons.Default.Edit, "Enter coordinates") }
                            }
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
                                    "%.0f km/h".format(speed), fontWeight = FontWeight.Bold,
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
                                Text(
                                    when {
                                        pointA == null || pointB == null -> "Set A and B to plan the trip"
                                        routing -> "Finding route…"
                                        r != null -> "Distance ${Geo.formatKm(r.length)} · takes about " +
                                            Geo.formatTime(r.length / (speed / 3.6)) + " at %.0f km/h".format(speed)
                                        else -> ""
                                    },
                                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                                )
                                routeNote?.let { Text(it, fontSize = 12.sp, color = Color(0xFFE65100)) }
                            }
                        }

                        if (favorites.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(favorites) { f ->
                                    AssistChip(
                                        onClick = { pick(f.lat, f.lng, true) },
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
                                Modifier.weight(1f), fontSize = 14.sp
                            )
                            Switch(checked = drift, onCheckedChange = {
                                drift = it; prefs.drift = it
                                if (running && !travelling) MockLocationService.start(ctx, lat, lng, it)
                            })
                        }

                        if (mode == 0) {
                            Button(
                                onClick = {
                                    if (running) MockLocationService.stop(ctx)
                                    else if (checkReady()) {
                                        MockLocationService.error.value = null
                                        MockLocationService.start(ctx, lat, lng, drift)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
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
                                        modifier = Modifier.weight(1f).height(52.dp)
                                    ) { Text(if (paused) "RESUME" else "PAUSE", fontSize = 16.sp) }
                                }
                                Button(
                                    onClick = { MockLocationService.stop(ctx) },
                                    modifier = Modifier.weight(1f).height(52.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                                ) { Text("STOP TRIP", fontSize = 16.sp) }
                            }
                        } else {
                            Button(
                                onClick = {
                                    val r = route
                                    when {
                                        pointA == null || pointB == null -> toast("Set both A and B first")
                                        routing || r == null -> toast("Please wait, finding route…")
                                        checkReady() -> {
                                            MockLocationService.error.value = null
                                            MockLocationService.startTravel(ctx, r, speed, drift)
                                            pointA?.let { center(it.lat, it.lng) }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(52.dp)
                            ) { Text("START TRIP  A → B", fontSize = 16.sp) }
                        }
                    }
                }
            }
        }

        if (showCoords) {
            val cur = if (mode == 0) Pt(lat, lng) else (if (active == 0) pointA else pointB)
            var text by remember { mutableStateOf(cur?.let { "%.6f, %.6f".format(it.lat, it.lng) } ?: "") }
            AlertDialog(
                onDismissRequest = { showCoords = false },
                title = {
                    Text(
                        if (mode == 0) "Enter coordinates"
                        else "Coordinates for ${if (active == 0) "A (start)" else "B (destination)"}"
                    )
                },
                text = {
                    Column {
                        Text("Format: latitude, longitude (you can paste from Google Maps)", fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(text, { text = it }, singleLine = true)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val c = PlaceSearch.parseCoords(text)
                        if (c == null) toast("Invalid coordinates") else {
                            pick(c.first, c.second, true); showCoords = false
                        }
                    }) { Text("Go") }
                },
                dismissButton = { TextButton(onClick = { showCoords = false }) { Text("Cancel") } }
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
    private fun Slot(label: String, color: Color, p: Pt?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
        Surface(
            modifier = modifier.clickable(onClick = onClick),
            shape = RoundedCornerShape(10.dp),
            color = if (selected) color.copy(alpha = 0.08f) else Color.Transparent,
            border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) color else Color.LightGray)
        ) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(label, color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(
                    p?.let { "%.4f, %.4f".format(it.lat, it.lng) } ?: "Not set",
                    fontSize = 12.sp, maxLines = 1
                )
            }
        }
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
                SetupStep(
                    2, "Turn on Developer options: About phone → tap \"Build number\" 7 times",
                    devOn.value, "Open"
                ) { openSettings(Settings.ACTION_DEVICE_INFO_SETTINGS) }
                SetupStep(
                    3, "Developer options → \"Select mock location app\" → Fake Location",
                    mockOn.value, "Open"
                ) { openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }
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
