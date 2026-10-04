package com.tally.steps.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.tally.steps.engine.TrackPoint
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

/**
 * S04 live route map (osmdroid, Apache-2.0 — OpenTracks pattern).
 * Default tile cache on; follow mode centers the latest fix while active.
 *
 * Honest offline note: map tiles need internet, tracking does not — the
 * polyline and step count keep recording with zero connectivity.
 */
@Composable
fun WorkoutMap(
    points: List<TrackPoint>,
    active: Boolean,
    onLocation: (lat: Double, lon: Double, accuracyM: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    // Re-checked every composition: permission can flip mid-workout.
    val hasLoc = hasLocation(context)

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

    Column(modifier = modifier.fillMaxWidth()) {
        AndroidView(
            factory = { ctx ->
                Configuration.getInstance().userAgentValue = ctx.packageName
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(17.0)
                    controller.setCenter(GROVE_START)
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
                if (active && points.isNotEmpty()) {
                    val last = points.last()
                    map.controller.animateTo(GeoPoint(last.lat, last.lon))
                } else if (points.isEmpty()) {
                    map.controller.setCenter(GROVE_START)
                }
                map.invalidate()
            },
            modifier = Modifier.fillMaxWidth().height(240.dp),
        )
        Text(
            text = if (points.isEmpty()) {
                "Map tiles need internet — tracking does not. " +
                    "Your route draws here once GPS locks."
            } else {
                "Map tiles need internet — tracking does not. " +
                    "${points.size} GPS points recorded."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val GROVE_START = GeoPoint(0.0, 0.0)

private fun hasLocation(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

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
