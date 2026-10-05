package com.tally.steps.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One accepted GPS fix. Time/accuracy feed the 5s-window filter. */
data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val timeMs: Long,
    val accuracyM: Float,
)

/**
 * Workout GPS engine (FOSS pattern: OpenTracks/RunnerUp-F-Droid).
 *
 * Framework-free: feeds on `onLocation()` calls from whoever holds the
 * location permission — designed to be driven from a foreground service with
 * the `location` type (StepCounterService declares `health|location` for
 * this). Uses the platform LocationManager, no Play Services dependency.
 *
 * Filter per arch §5: 5s-windowed gate, drops fixes with `accuracy > 20m` or
 * implied walk speed `> 3.5 m/s` (car-count/GPS-jump fix). GPS denied →
 * tracker stays empty and the workout is step-only (honest fallback).
 * Treadmill sessions never feed locations: distance = steps × step length.
 *
 * Pause/resume mirrors the session state machine; auto-break fires after
 * 60s with no steps. [polylineString] is the Room column format;
 * [toGpxString] builds a GPX 1.1 export.
 */
class WorkoutTracker {

    private val _pointsFlow = MutableStateFlow<List<TrackPoint>>(emptyList())
    val pointsFlow: StateFlow<List<TrackPoint>> = _pointsFlow.asStateFlow()

    val points: List<TrackPoint> get() = _pointsFlow.value

    private var started = false
    private var paused = false
    private var pauseStartMs = 0L
    private var pausedMsTotal = 0L
    private var lastProgressAtMs = 0L

    fun start(nowMs: Long) {
        started = true
        paused = false
        pauseStartMs = 0L
        pausedMsTotal = 0L
        lastProgressAtMs = nowMs
        _pointsFlow.value = emptyList()
    }

    /**
     * Returns true when the fix was accepted. Dropped when paused, when
     * accuracy > 20m, or when speed vs the last accepted fix exceeds 3.5 m/s.
     */
    /** Speed of the last judged window in m/s; 0 when no window was judged yet. */
    var lastSpeedMs: Float = 0f
        private set

    fun onLocation(lat: Double, lon: Double, timeMs: Long, accuracyM: Float): Boolean {
        if (!started || paused) return false
        if (accuracyM > MAX_ACCURACY_M) return false
        val last = _pointsFlow.value.lastOrNull()
        if (last != null) {
            val dtS = (timeMs - last.timeMs) / 1000.0
            if (dtS <= 0) return false
            // 5s-windowed speed gate: only judge fixes spanning a real window,
            // otherwise sub-second jitter would false-trigger on noise.
            if (dtS >= WINDOW_S) {
                val speed = haversineM(last.lat, last.lon, lat, lon) / dtS
                lastSpeedMs = speed.toFloat()
                if (speed > MAX_WALK_SPEED_MS) return false
            }
        }
        _pointsFlow.value = _pointsFlow.value + TrackPoint(lat, lon, timeMs, accuracyM)
        return true
    }

    /** Call whenever session steps advance; resets the auto-break clock. */
    fun onSteps(nowMs: Long) {
        lastProgressAtMs = nowMs
    }

    /** True when active, unpaused, has steps, and nothing moved for 60s. */
    fun shouldAutoBreak(nowMs: Long, hasSteps: Boolean): Boolean =
        started && !paused && hasSteps && nowMs - lastProgressAtMs >= AUTO_BREAK_MS

    fun pause(nowMs: Long) {
        if (!started || paused) return
        paused = true
        pauseStartMs = nowMs
    }

    fun resume(nowMs: Long) {
        if (!paused) return
        paused = false
        pausedMsTotal += (nowMs - pauseStartMs).coerceAtLeast(0L)
        pauseStartMs = 0L
        lastProgressAtMs = nowMs
    }

    /** Total paused ms, including an ongoing pause up to [nowMs]. */
    fun pausedMs(nowMs: Long): Long =
        pausedMsTotal + if (paused) (nowMs - pauseStartMs).coerceAtLeast(0L) else 0L

    fun isPaused(): Boolean = paused

    /** Null when step-only (< 2 fixes): DB stores NULL, not an empty string. */
    fun polylineString(): String? {
        val pts = _pointsFlow.value
        if (pts.size < 2) return null
        return pts.joinToString(";") { "${it.lat},${it.lon}" }
    }

    fun gpsDistanceM(): Float {
        val pts = _pointsFlow.value
        var total = 0.0
        for (i in 1 until pts.size) {
            total += haversineM(pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon)
        }
        return total.toFloat()
    }

    fun toGpxString(type: String, startMs: Long): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Tally\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("<metadata><time>${Instant.ofEpochMilli(startMs)}</time></metadata>\n")
        sb.append("<trk><name>Tally $type</name><trkseg>\n")
        for (p in _pointsFlow.value) {
            sb.append("<trkpt lat=\"${p.lat}\" lon=\"${p.lon}\">")
            sb.append("<time>${Instant.ofEpochMilli(p.timeMs)}</time></trkpt>\n")
        }
        sb.append("</trkseg></trk>\n</gpx>")
        return sb.toString()
    }

    fun clear() {
        started = false
        paused = false
        pausedMsTotal = 0L
        _pointsFlow.value = emptyList()
    }

    companion object {
        const val MAX_ACCURACY_M = 20f
        const val MAX_WALK_SPEED_MS = 3.5f
        /** Speed gate evaluated over windows of at least this length. */
        const val WINDOW_S = 5.0
        const val AUTO_BREAK_MS = 60_000L

        fun decodePolyline(polyline: String?): List<TrackPoint> {
            if (polyline.isNullOrBlank()) return emptyList()
            return polyline.split(";").mapNotNull { seg ->
                val parts = seg.split(",")
                val lat = parts.getOrNull(0)?.toDoubleOrNull()
                val lon = parts.getOrNull(1)?.toDoubleOrNull()
                if (lat != null && lon != null) TrackPoint(lat, lon, 0L, 0f) else null
            }
        }

        fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
            return 2 * r * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}
