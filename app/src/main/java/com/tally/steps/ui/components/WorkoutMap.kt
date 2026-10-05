package com.tally.steps.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.tally.steps.engine.TrackPoint
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

/**
 * S04 live route map (osmdroid, Apache-2.0 — OpenTracks pattern).
 * Default tile cache on; follow mode centers the FIRST fix only, then
 * yields to the user until the Recenter button is tapped.
 *
 * Honest offline note: map tiles need internet, tracking does not — the
 * polyline and step count keep recording with zero connectivity. Without
 * GPS the workout stays step-only (distance = steps × step length).
 */
@Composable
@Suppress("DEPRECATION") // setMapListener is the touch-gated follow switch on 6.1.20
fun WorkoutMap(
    points: List<TrackPoint>,
    active: Boolean,
    onLocation: (lat: Double, lon: Double, accuracyM: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    // Re-checked every composition: permission can flip mid-workout.
    val hasLoc = hasLocation(context)

    // Stable holder: the AndroidView factory runs once, so listeners must
    // touch the holder — never a captured value.
    val mapHolder = remember { object { var view: MapView? = null } }
    val touchRef = remember { object { var down: Boolean = false } }
    var follow by remember { mutableStateOf(true) }
    var firstFixCentered by remember { mutableStateOf(false) }

    // MapView has no Compose awareness: forward lifecycle or tiles never load.
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> runCatching { mapHolder.view?.onResume() }
                Lifecycle.Event.ON_PAUSE -> runCatching { mapHolder.view?.onPause() }
                Lifecycle.Event.ON_DESTROY -> runCatching { mapHolder.view?.onDetach() }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Feed raw fixes up to the ViewModel's WorkoutTracker, which applies the
    // arch §5 filter (accuracy > 20m / speed > 3.5 m/s dropped).
    DisposableEffect(active, hasLoc) {
        if (!active || !hasLoc) return@DisposableEffect onDispose {}
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = LocationListener { loc ->
            onLocation(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else 0f)
        }
        runCatching {
            requestFixes(lm, listener)
        }
        onDispose {
            runCatching { lm.removeUpdates(listener) }
        }
    }

    // New workout clears points: let the next first fix center again.
    LaunchedEffect(points.isEmpty()) {
        if (points.isEmpty()) firstFixCentered = false
    }

    val statusText = when {
        !hasLoc -> "Location permission needed — workout stays step-only until granted."
        points.isEmpty() -> "No GPS yet — steps still count."
        else -> "GPS locked · ${points.size} points"
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.fillMaxWidth().height(240.dp),
        ) {
            AndroidView(
                factory = { ctx ->
                    Configuration.getInstance().userAgentValue = ctx.packageName
                    MapView(ctx).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        // Launch on last-known fix; world view when there is none —
                        // never the (0,0) ocean at street zoom.
                        val known = lastKnownPoint(ctx.applicationContext)
                        if (known != null) {
                            controller.setZoom(LOCKED_ZOOM)
                            controller.setCenter(known)
                        } else {
                            controller.setZoom(WORLD_ZOOM)
                            controller.setCenter(WORLD_CENTER)
                        }
                        mapHolder.view = this
                        // Touch gate: only finger drags clear follow, so the
                        // programmatic first-fix/Recenter moves never fight back.
                        setOnTouchListener { _, ev ->
                            when (ev.action) {
                                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                                    touchRef.down = true
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                                    touchRef.down = false
                            }
                            false // never consume: map still pans/zooms
                        }
                        setMapListener(
                            object : MapListener {
                                override fun onScroll(event: ScrollEvent?): Boolean {
                                    if (touchRef.down) follow = false
                                    return false
                                }

                                override fun onZoom(event: ZoomEvent?): Boolean = false
                            },
                        )
                    }
                },
                update = { map ->
                    map.overlays.removeAll { it is Polyline }
                    if (points.size >= 2) {
                        val line = Polyline().apply {
                            setPoints(points.map { GeoPoint(it.lat, it.lon) })
                        }
                        map.overlays.add(line)
                    }
                    // FIRST fix only — never on every recomposition.
                    if (follow && !firstFixCentered && points.isNotEmpty()) {
                        val last = points.last()
                        map.controller.animateTo(GeoPoint(last.lat, last.lon))
                        firstFixCentered = true
                    }
                    map.invalidate()
                },
                onRelease = { map ->
                    if (mapHolder.view === map) mapHolder.view = null
                    runCatching { map.onDetach() }
                },
            )
            if (hasLoc && points.isEmpty()) {
                Text(
                    text = "Waiting for GPS — go outside",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Map tiles need internet — tracking does not. " +
                "No GPS? This workout stays step-only " +
                "(distance = steps × step length).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = {
                follow = true
                val target = points.lastOrNull()?.let { GeoPoint(it.lat, it.lon) }
                    ?: lastKnownPoint(context)
                val ctrl = mapHolder.view?.controller ?: return@Button
                if (target != null) {
                    ctrl.setZoom(LOCKED_ZOOM)
                    ctrl.animateTo(target)
                } else {
                    ctrl.setZoom(WORLD_ZOOM)
                    ctrl.setCenter(WORLD_CENTER)
                }
            },
            modifier = Modifier.size(48.dp)
                .semantics { contentDescription = "Recenter map on my location" },
        ) {
            Text(text = "⌖")
        }
    }
}

/** Neutral world view: zoomed out, not the (0,0) ocean at street zoom. */
private val WORLD_CENTER = GeoPoint(20.0, 0.0)
private const val LOCKED_ZOOM = 17.0
private const val WORLD_ZOOM = 2.0

private fun hasLocation(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

/**
 * GPS last-known first, network fallback. Null when permission is missing,
 * providers are off, ranges are insane, or the fix is exactly (0,0).
 */
@SuppressLint("MissingPermission")
private fun lastKnownPoint(appContext: Context): GeoPoint? {
    if (!hasLocation(appContext)) return null
    return runCatching {
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gps = runCatching {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            } else {
                null
            }
        }.getOrNull()
        val net = runCatching {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } else {
                null
            }
        }.getOrNull()
        val fix = gps ?: net ?: return null
        if (fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0) return null
        if (fix.latitude == 0.0 && fix.longitude == 0.0) return null
        GeoPoint(fix.latitude, fix.longitude)
    }.getOrNull()
}

@SuppressLint("MissingPermission")
private fun requestFixes(lm: LocationManager, listener: LocationListener) {
    // 5s / 5m cadence matches the tracker's 5s filter window; GPS first,
    // network as an indoor fallback (its fixes usually fail the 20m gate).
    if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5_000L, 5f, listener)
    }
    if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
        lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5_000L, 5f, listener)
    }
}
