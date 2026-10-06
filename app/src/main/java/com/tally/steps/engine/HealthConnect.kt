package com.tally.steps.engine

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Thin Health Connect wrapper (connect-client 1.1.0): aggregate reads with
 * own-write echo subtraction, replace-own write-back. Gating on hcMode and
 * gap logic live in [StepRepository]; this class never decides, only IO.
 *
 * Docs (developer.android.com/health-and-fitness): cumulative types such as
 * StepsRecord MUST be read with aggregate(), never readRecords(), to avoid
 * double counting across sources. This file uses only aggregate().
 */
class HealthConnect {

    val readPermissions: Set<String> =
        setOf(HealthPermission.getReadPermission(StepsRecord::class))

    val writePermissions: Set<String> =
        setOf(HealthPermission.getWritePermission(StepsRecord::class))

    fun isAvailable(context: Context): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    suspend fun hasPermissions(context: Context): Boolean {
        if (!isAvailable(context)) return false
        return runCatching {
            HealthConnectClient.getOrCreate(context).permissionController
                .getGrantedPermissions()
                .containsAll(readPermissions)
        }.getOrDefault(false)
    }

    /** Write grant, checked separately: RW mode must not assume it from the read grant. */
    suspend fun hasWritePermission(context: Context): Boolean {
        if (!isAvailable(context)) return false
        return runCatching {
            HealthConnectClient.getOrCreate(context).permissionController
                .getGrantedPermissions()
                .containsAll(writePermissions)
        }.getOrDefault(false)
    }

    /** Background-read permission, declared plainly in the manifest (no maxSdkVersion).
     * Without this grant, background reads return only our own records, so the
     * caller must skip honestly and keep the local sensor count. */
    companion object {
        const val BACKGROUND_PERMISSION =
            "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
    }

    /** True when background reads are allowed to see other apps' data. Never throws. */
    fun hasBackgroundPermission(context: Context): Boolean = runCatching {
        ContextCompat.checkSelfPermission(context, BACKGROUND_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * Worker entry: on API 36+ background reads need the background grant,
     * so a missing grant honestly skips (null) instead of merging a self-only
     * read. Below 36 the platform has no such gate — read directly.
     */
    suspend fun readDayForWorker(context: Context, date: LocalDate): Long? {
        if (android.os.Build.VERSION.SDK_INT >= 36) return readDayInBackground(context, date)
        return readDayExclOurs(context, date)
    }

    /**
     * Today's steps from everyone except us (echo subtraction via double aggregate).
     *
     * Note for the UI: Health Connect syncs with roughly a 15-minute delay, so
     * merged counts shown on screen can lag the live sensor by that much. This
     * is a Health Connect sync delay, not missing steps.
     *
     * The unfiltered "all" aggregate already includes on-device phone steps
     * automatically (no DataOrigin filter = no June-2026 SPN migration work
     * needed for the total). Our own write-back is attributed to our own
     * package name, so subtracting DataOrigin(packageName) removes exactly the
     * echo and nothing else.
     */
    suspend fun readDayExclOurs(context: Context, date: LocalDate): Long {
        if (!isAvailable(context)) return 0
        return runCatching {
            val client = HealthConnectClient.getOrCreate(context)
            val all = dayTotal(client, date, emptySet())
            val own = dayTotal(client, date, setOf(DataOrigin(context.packageName)))
            (all - own).coerceAtLeast(0)
        }.getOrDefault(0)
    }

    /**
     * Background-safe variant: returns null when [BACKGROUND_PERMISSION] is not
     * granted, so the caller honestly skips and keeps the sensor value instead
     * of merging a self-only (echo-only) background read. Foreground code must
     * keep calling [readDayExclOurs] directly; this gate is background-only.
     */
    suspend fun readDayInBackground(context: Context, date: LocalDate): Long? {
        if (!hasBackgroundPermission(context)) return null
        return readDayExclOurs(context, date)
    }

    /**
     * On-device phone-step origins: DataOrigin("android") for history recorded
     * before June 2026, plus the device SPN (e.g. com.android.healthconnect
     * .phone.<id>, app-scoped and device-specific, never hardcode it) via
     * HealthConnectManager.getCurrentDeviceDataSource() afterwards. Used only
     * for explicit on-device breakdowns such as [readDeviceSteps]; the merged
     * total intentionally uses NO filter so platform steps stay included.
     */
    fun platformOrigins(context: Context): Set<DataOrigin> {
        val origins = mutableSetOf(DataOrigin("android"))
        // Post-June-2026 on-device steps carry the device SPN, not "android".
        // No stable SDK accessor exists in connect-client 1.1.0, so look it up
        // reflectively; any failure keeps just "android" (merged total uses no
        // filter anyway, so this only narrows the attribution breakdown).
        runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return@runCatching
            val mgrClass = Class.forName("android.health.connect.HealthConnectManager")
            val manager = context.getSystemService(mgrClass) ?: return@runCatching
            val source = mgrClass.getMethod("getCurrentDeviceDataSource").invoke(manager)
                ?: return@runCatching
            val origin = source.javaClass.getMethod("getDeviceDataOrigin").invoke(source)
                ?: return@runCatching
            val pkg = origin.javaClass.getMethod("getPackageName").invoke(origin) as? String
            if (!pkg.isNullOrEmpty()) origins.add(DataOrigin(pkg))
        }
        return origins
    }

    /** On-device phone steps only (android + current SPN), for attribution UI. */
    suspend fun readDeviceSteps(context: Context, date: LocalDate): Long {
        if (!isAvailable(context)) return 0
        return runCatching {
            dayTotal(HealthConnectClient.getOrCreate(context), date, platformOrigins(context))
        }.getOrDefault(0)
    }

    private suspend fun dayTotal(
        client: HealthConnectClient,
        date: LocalDate,
        origins: Set<DataOrigin>,
    ): Long {
        val response = client.aggregate(
            AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(
                    date.atStartOfDay(),
                    date.atTime(LocalTime.MAX),
                ),
                dataOriginFilter = origins,
            ),
        )
        return response[StepsRecord.COUNT_TOTAL] ?: 0L
    }

    /**
     * Replace-own write-back of the sensor delta. Deletes our records for the
     * day first so re-syncs can't stack; a zero day is delete-only (HC rejects
     * zero-count records).
     */
    suspend fun writeDay(
        context: Context,
        date: LocalDate,
        steps: Int,
        distanceM: Float,
        kcal: Float,
    ): Boolean {
        if (!isAvailable(context) || steps <= 0) return false
        return runCatching {
            val client = HealthConnectClient.getOrCreate(context)
            val zone = ZoneId.systemDefault()
            val start = date.atStartOfDay(zone).toInstant()
            // End 1ms before midnight so HC's window scaling reads the exact count.
            val end = minOf(
                date.plusDays(1).atStartOfDay(zone).toInstant().minusMillis(1),
                Instant.now(),
            )
            if (!end.isAfter(start)) return@runCatching false
            val range = TimeRangeFilter.between(start, end)
            // Re-check the write grant immediately before the destructive
            // half: a revoke between syncHealth's check and this delete would
            // otherwise wipe our HC courtesy copy with no insert after it.
            // (Local Room data is never at risk — HC is a copy, not the truth.)
            if (!hasWritePermission(context)) return@runCatching false
            client.deleteRecords(StepsRecord::class, range)
            val device = Device(
                type = Device.TYPE_PHONE,
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
            )
            client.insertRecords(
                listOf(
                    StepsRecord(
                        startTime = start,
                        startZoneOffset = zone.rules.getOffset(start),
                        endTime = end,
                        endZoneOffset = zone.rules.getOffset(end),
                        count = steps.toLong(),
                        metadata = Metadata.autoRecorded(device),
                    ),
                ),
            )
            // distanceM/kcal intentionally not written: Day estimates are
            // derived, HC owns its own distance/calorie records from our steps.
            true
        }.getOrDefault(false)
    }
}
