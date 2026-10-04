package com.tally.steps.engine

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Period
import java.time.ZoneId

/**
 * Thin Health Connect wrapper (connect-client 1.1.0): aggregate reads with
 * own-write echo subtraction, replace-own write-back. Gating on hcMode and
 * gap logic live in [StepRepository]; this class never decides, only IO.
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

    /** Today's steps from everyone except us (echo subtraction via double aggregate). */
    suspend fun readDayExclOurs(context: Context, date: LocalDate): Long {
        if (!isAvailable(context)) return 0
        return runCatching {
            val client = HealthConnectClient.getOrCreate(context)
            val all = dayTotal(client, date, emptySet())
            val own = dayTotal(client, date, setOf(DataOrigin(context.packageName)))
            (all - own).coerceAtLeast(0)
        }.getOrDefault(0)
    }

    private suspend fun dayTotal(
        client: HealthConnectClient,
        date: LocalDate,
        origins: Set<DataOrigin>,
    ): Long {
        val groups = client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(
                    date.atStartOfDay(),
                    date.atTime(LocalTime.MAX),
                ),
                timeRangeSlicer = Period.ofDays(1),
                dataOriginFilter = origins,
            ),
        )
        return groups.sumOf { it.result[StepsRecord.COUNT_TOTAL] ?: 0L }
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
