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
import java.io.File

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

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AppScreen() {
        val ctx = this
        val prefs = remember { Prefs(ctx) }
        var lat by rememberSaveable { mutableDoubleStateOf(prefs.lastLat) }
        var lng by rememberSaveable { mutableDoubleStateOf(prefs.lastLng) }
        var drift by remember { mutableStateOf(prefs.drift) }
        val running by MockLocationService.running.collectAsState()
        val error by MockLocationService.error.collectAsState()
        var query by remember { mutableStateOf("") }
        var results by remember { mutableStateOf<List<Place>>(emptyList()) }
        var searching by remember { mutableStateOf(false) }
        var favorites by remember { mutableStateOf(prefs.favorites()) }
        var showCoords by remember { mutableStateOf(false) }
        var showSave by remember { mutableStateOf(false) }
        var mapView by remember { mutableStateOf<MapView?>(null) }
        val marker = remember { mutableStateOf<Marker?>(null) }
        val scope = rememberCoroutineScope()
        val keyboard = LocalSoftwareKeyboardController.current
        val ready = hasPerm.value && mockOn.value

        fun moveTo(la: Double, lo: Double, center: Boolean) {
            lat = la; lng = lo
            prefs.saveLast(la, lo)
            if (center) mapView?.let {
                it.controller.setZoom(16.0)
                it.controller.animateTo(GeoPoint(la, lo))
            }
            if (MockLocationService.running.value) MockLocationService.start(ctx, la, lo, prefs.drift)
        }

        fun doSearch() {
            val q = query.trim()
            if (q.isEmpty()) return
            keyboard?.hide()
            PlaceSearch.parseCoords(q)?.let { (a, o) -> moveTo(a, o, true); return }
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

                // Search
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    singleLine = true,
                    placeholder = { Text("Search any place or paste lat, lng") },
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
                                controller.setCenter(GeoPoint(lat, lng))
                                val m = Marker(this).apply {
                                    position = GeoPoint(lat, lng)
                                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                    title = "Fake location"
                                }
                                overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                                    override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                                        moveTo(p.latitude, p.longitude, false)
                                        return true
                                    }
                                    override fun longPressHelper(p: GeoPoint): Boolean = false
                                }))
                                overlays.add(m)
                                marker.value = m
                                mapView = this
                                mapRef = this
                            }
                        },
                        update = { mv ->
                            marker.value?.position = GeoPoint(lat, lng)
                            mv.invalidate()
                        }
                    )

                    // Search results over the map
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
                                            .clickable { results = emptyList(); moveTo(p.lat, p.lng, true) }
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
                            "● FAKE LOCATION ON",
                            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                                .background(Color(0xFF2E7D32), RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                // Bottom panel
                Surface(shadowElevation = 8.dp) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("Tap the map to choose a place", fontSize = 12.sp, color = Color.Gray)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "%.6f, %.6f".format(lat, lng),
                                Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold
                            )
                            IconButton(onClick = { showCoords = true }) { Icon(Icons.Default.Edit, "Enter coordinates") }
                            IconButton(onClick = { showSave = true }) { Icon(Icons.Default.Star, "Save place") }
                        }

                        if (favorites.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(favorites) { f ->
                                    AssistChip(
                                        onClick = { moveTo(f.lat, f.lng, true) },
                                        label = { Text(f.name, maxLines = 1) },
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
                            Text("Natural movement (tiny GPS wobble)", Modifier.weight(1f), fontSize = 14.sp)
                            Switch(checked = drift, onCheckedChange = {
                                drift = it; prefs.drift = it
                                if (running) MockLocationService.start(ctx, lat, lng, it)
                            })
                        }

                        Button(
                            onClick = {
                                when {
                                    running -> MockLocationService.stop(ctx)
                                    !hasPerm.value -> askPermissions()
                                    !mockOn.value -> toast("Finish the setup first: select Fake Location as mock location app.")
                                    else -> {
                                        MockLocationService.error.value = null
                                        MockLocationService.start(ctx, lat, lng, drift)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (running) Color(0xFFC62828) else MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(if (running) "STOP FAKE LOCATION" else "START FAKE LOCATION", fontSize = 16.sp)
                        }
                    }
                }
            }
        }

        if (showCoords) {
            var text by remember { mutableStateOf("%.6f, %.6f".format(lat, lng)) }
            AlertDialog(
                onDismissRequest = { showCoords = false },
                title = { Text("Enter coordinates") },
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
                            moveTo(c.first, c.second, true); showCoords = false
                        }
                    }) { Text("Go") }
                },
                dismissButton = { TextButton(onClick = { showCoords = false }) { Text("Cancel") } }
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
