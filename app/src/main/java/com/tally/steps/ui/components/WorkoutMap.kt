package com.tally.steps.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay

/**
 * S04 live route map (osmdroid, Apache-2.0 — OpenTracks pattern).
 * Display-only: fixes arrive from [com.tally.steps.engine.WorkoutGps], fed by
 * the step service inside a location-type foreground session, so the route
 * keeps recording with the screen off. Default tile cache on.
 *
 * Follow mode tracks the runner on every new fix and yields the moment a
 * finger drags the map; the overlay Recenter button resumes it. The route
 * draws in the theme accent at glanceable width with a start dot; a single
 * fix draws a dot, not a blank map. Overlays redraw only when the point
 * count changes — the per-second timer no longer rebuilds them.
 *
 * Honest offline note: map tiles need internet, tracking does not.
 */
@Composable
@Suppress("DEPRECATION") // setMapListener is the touch-gated follow switch on 6.1.20
fun WorkoutMap(
    points: List<TrackPoint>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    // Re-checked every composition: permission can flip mid-workout.
    val hasLoc = hasLocation(context)
    val accent = MaterialTheme.colorScheme.primary

    // Stable holder: the AndroidView factory runs once, so listeners must
    // touch the holder — never a captured value.
    val mapHolder = remember { object { var view: MapView? = null } }
    val drawHolder = remember {
        object {
            var drawn: Int = -1
            var centered: GeoPoint? = null
            var knownAt: Long = 0L
        }
    }
    val touchRef = remember { object { var down: Boolean = false } }
    var follow by remember { mutableStateOf(true) }

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

    // New workout clears points: reset follow state for the next first fix.
    LaunchedEffect(points.isEmpty()) {
        if (points.isEmpty()) {
            drawHolder.drawn = -1
            drawHolder.centered = null
        }
    }

    val statusText = when {
        !hasLoc -> "Route off — grant location to draw it. Steps still count."
        points.isEmpty() -> "No GPS yet — steps still count."
        points.size == 1 -> "GPS found — move to draw the route."
        else -> "Recording route · ${points.size} points"
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.fillMaxWidth().height(280.dp),
        ) {
            AndroidView(
                factory = { ctx ->
                    // OSM tile policy: identify the app with contact, not a bare package name.
                    Configuration.getInstance().userAgentValue =
                        "${ctx.packageName};https://github.com/omersusin/tally"
                    MapView(ctx).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        // Free scale bar; tiles already cache on disk (offline story).
                        overlays.add(ScaleBarOverlay(this))
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
                        // programmatic follow/Recenter moves never fight back.
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

                                override fun onZoom(event: ZoomEvent?): Boolean {
                                    if (touchRef.down) follow = false
                                    return false
                                }
                            },
                        )
                    }
                },
                update = { map ->
                    val accentArgb = accent.toArgb()
                    // Redraw overlays only when the route actually grew.
                    if (points.size != drawHolder.drawn) {
                        drawHolder.drawn = points.size
                        map.overlays.removeAll { it is Polyline || it is Marker }
                        if (points.size >= 2) {
                            val line = Polyline().apply {
                                setPoints(points.map { GeoPoint(it.lat, it.lon) })
                                color = accentArgb
                                width = 12f
                            }
                            map.overlays.add(line)
                            val start = Marker(map).apply {
                                position = GeoPoint(points.first().lat, points.first().lon)
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                title = "Start"
                            }
                            map.overlays.add(start)
                        } else if (points.size == 1) {
                            val dot = Marker(map).apply {
                                position = GeoPoint(points.first().lat, points.first().lon)
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                title = "Current position"
                            }
                            map.overlays.add(dot)
                        }
                    }
                    // Follow the runner on every new fix until a finger drags.
                    val last = points.lastOrNull()?.let { GeoPoint(it.lat, it.lon) }
                    if (follow && last != null && last != drawHolder.centered) {
                        drawHolder.centered = last
                        map.controller.animateTo(last)
                    }
                    // No fix yet: refresh last-known at most every 30s (cheap,
                    // no recomposition storm — guarded by the timestamp).
                    if (last == null && hasLoc && drawHolder.centered == null) {
                        val now = System.currentTimeMillis()
                        if (now - drawHolder.knownAt > 30_000L) {
                            drawHolder.knownAt = now
                            lastKnownPoint(context)?.let {
                                map.controller.setZoom(LOCKED_ZOOM)
                                map.controller.setCenter(it)
                            }
                        }
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
            Button(
                onClick = {
                    follow = true
                    val target = points.lastOrNull()?.let { GeoPoint(it.lat, it.lon) }
                        ?: lastKnownPoint(context)
                    val ctrl = mapHolder.view?.controller ?: return@Button
                    if (target != null) {
                        drawHolder.centered = target
                        ctrl.setZoom(LOCKED_ZOOM)
                        ctrl.animateTo(target)
                    } else {
                        ctrl.setZoom(WORLD_ZOOM)
                        ctrl.setCenter(WORLD_CENTER)
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "Recenter map on my location" },
            ) {
                Icon(Icons.Filled.MyLocation, contentDescription = null)
                Text(
                    text = "Recenter",
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                text = "© OpenStreetMap",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomStart)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Text(
            text = "$statusText Tiles need internet — tracking does not.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Neutral world view: zoomed out, not the (0,0) ocean at street zoom. */
private val WORLD_CENTER = GeoPoint(20.0, 0.0)
private const val LOCKED_ZOOM = 17.0
private const val WORLD_ZOOM = 2.0

private fun hasLocation(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

/**
 * GPS last-known first, network fallback. Null when permission is missing,
 * providers are off, ranges are insane, or the fix is exactly (0,0).
 */
@SuppressLint("MissingPermission")
private fun lastKnownPoint(appContext: Context): GeoPoint? {
    if (!hasLocation(appContext)) return null
    return runCatching {
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
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
