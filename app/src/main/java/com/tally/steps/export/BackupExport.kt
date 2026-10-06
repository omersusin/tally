package com.tally.steps.export

import android.content.Context
import android.net.Uri
import com.tally.steps.data.Day
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * CSV export + ZIP backup + restore for Tally's Day rows.
 *
 * Everything here works on plain data — callers (SettingsViewModel) fetch
 * Days from the engine and hand them in, so this file never depends on Room,
 * DataStore, or TallyApp internals.
 */
object BackupExport {
    const val CSV_HEADER =
        "epochDay,date,steps,distanceM,kcal,activeMin,goal,manualDelta,source,updatedAt,restDay,floors"
    private const val CSV_HEADER_V2 =
        "epochDay,date,steps,distanceM,kcal,activeMin,goal,manualDelta,source,updatedAt,restDay"
    private const val CSV_HEADER_V1 =
        "epochDay,date,steps,distanceM,kcal,activeMin,goal,manualDelta,source,updatedAt"
    private const val WORKOUT_HEADER =
        "id,type,startMs,endMs,steps,distanceM,pausedMs,gpsPolyline"
    private const val BACKUP_VERSION = 3

    // ---------- export ----------

    fun daysToCsv(days: List<Day>): String = buildString {
        appendLine(CSV_HEADER)
        days.sortedBy { it.epochDay }.forEach { d ->
            val date = runCatching { LocalDate.ofEpochDay(d.epochDay).toString() }.getOrDefault("")
            appendLine(
                listOf(
                    d.epochDay.toString(),
                    date,
                    d.steps.toString(),
                    d.distanceM.toString(),
                    d.kcal.toString(),
                    d.activeMin.toString(),
                    d.goal.toString(),
                    d.manualDelta.toString(),
                    csvCell(sanitizeForCsv(d.source)),
                    d.updatedAt.toString(),
                    if (d.restDay) "1" else "0",
                    d.floors.toString(),
                ).joinToString(","),
            )
        }
    }

    fun workoutsToCsv(workouts: List<com.tally.steps.data.Workout>): String = buildString {
        appendLine(WORKOUT_HEADER)
        workouts.sortedBy { it.startMs }.take(MAX_WORKOUT_ROWS).forEach { w ->
            appendLine(
                listOf(
                    csvCell(sanitizeForCsv(w.id)),
                    csvCell(sanitizeForCsv(w.type)),
                    w.startMs.toString(),
                    w.endMs.toString(),
                    w.steps.toString(),
                    w.distanceM.toString(),
                    w.pausedMs.toString(),
                    csvCell(w.gpsPolyline ?: ""),
                ).joinToString(","),
            )
        }
    }

    suspend fun exportCsv(context: Context, days: List<Day>): File = withContext(Dispatchers.IO) {
        val dir = tallyDir(context)
        val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val file = File(dir, "tally-days-$stamp.csv")
        file.writeText(daysToCsv(days))
        file
    }

    // ---------- backup ----------

    suspend fun backup(
        context: Context,
        days: List<Day>,
        prefs: Map<String, String>,
        workouts: List<com.tally.steps.data.Workout> = emptyList(),
    ): File =
        withContext(Dispatchers.IO) {
            val dir = tallyDir(context)
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "tally-backup-$stamp.zip")
            val prefsJson = JSONObject(prefs).toString(2)
            val metaJson = JSONObject()
                .put("version", BACKUP_VERSION)
                .put("exportedAt", System.currentTimeMillis())
                .put("dayCount", days.size)
                .put("workoutCount", workouts.size.coerceAtMost(MAX_WORKOUT_ROWS))
                .put("app", context.packageName)
                .toString(2)
            ZipOutputStream(file.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("days.csv"))
                zip.write(daysToCsv(days).toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("workouts.csv"))
                zip.write(workoutsToCsv(workouts).toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("prefs.json"))
                zip.write(prefsJson.toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("meta.json"))
                zip.write(metaJson.toByteArray())
                zip.closeEntry()
            }
            file
        }

    // ---------- restore ----------

    sealed interface RestoreOutcome {
        val userMessage: String

        data class Success(
            val days: List<RestoredDay>,
            val prefs: Map<String, String>,
            val workouts: List<RestoredWorkout> = emptyList(),
        ) : RestoreOutcome {
            override val userMessage =
                "Backup checked: ${days.size} day(s)" +
                    (if (workouts.isNotEmpty()) " + ${workouts.size} workout(s)" else "") +
                    " look valid. See Settings for what was applied."
        }

        data class Invalid(val errors: List<String>) : RestoreOutcome {
            override val userMessage =
                "That file is not a valid Tally backup — nothing was changed. " +
                    errors.take(3).joinToString(" ")
        }
    }

    /** Validated day row, decoupled from the Room entity so restore never writes blind. */
    data class RestoredDay(
        val epochDay: Long,
        val steps: Int,
        val distanceM: Float,
        val kcal: Float,
        val activeMin: Int,
        val goal: Int,
        val manualDelta: Int,
        val source: String,
        val updatedAt: Long,
        /** v2+ only; v1 backups restore as false. */
        val restDay: Boolean,
        /** v3+ only; older backups restore as 0. */
        val floors: Int = 0,
    )

    /** Validated workout row (v3+ backups carry workouts.csv; older backups have none). */
    data class RestoredWorkout(
        val id: String,
        val type: String,
        val startMs: Long,
        val endMs: Long,
        val steps: Int,
        val distanceM: Float,
        val pausedMs: Long,
        val gpsPolyline: String?,
    )

    suspend fun restore(context: Context, uri: Uri): RestoreOutcome = withContext(Dispatchers.IO) {
        val tmp = File.createTempFile("tally-restore", ".zip", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyCapped(it, MAX_BACKUP_BYTES) }
            } ?: return@withContext RestoreOutcome.Invalid(
                listOf("Could not read the selected file."),
            )
            readBackup(tmp)
        } catch (e: SizeCapExceeded) {
            RestoreOutcome.Invalid(
                listOf("That file is too large (over 5 MB) — nothing was changed."),
            )
        } catch (e: Exception) {
            RestoreOutcome.Invalid(listOf("Could not open it as a ZIP file."))
        } finally {
            tmp.delete()
        }
    }

    private fun readBackup(zipFile: File): RestoreOutcome {
        val errors = mutableListOf<String>()
        try {
            ZipFile(zipFile).use { zip ->
                val entries = zip.entries().asSequence().toList()
                if (entries.size > MAX_ZIP_ENTRIES) {
                    return RestoreOutcome.Invalid(
                        listOf("That backup contains too many files — nothing was changed."),
                    )
                }
                // Declared sizes where known (-1 = unknown, enforced by the capped
                // stream below); reject obvious bombs before inflating anything.
                val declared = entries.sumOf { it.size.coerceAtLeast(0) }
                if (declared > MAX_BACKUP_BYTES) {
                    return RestoreOutcome.Invalid(
                        listOf("That backup would inflate past 5 MB — nothing was changed."),
                    )
                }
                val csvEntry = zip.getEntry("days.csv")
                    ?: return RestoreOutcome.Invalid(
                        listOf("days.csv is missing — is this a Tally backup?"),
                    )
                // v1 files predate rest days; v2 adds restDay; v3 adds floors + workouts.csv.
                var backupVersion = 1
                val metaEntry = zip.getEntry("meta.json")
                if (metaEntry != null) {
                    val meta = runCatching {
                        JSONObject(zip.getInputStream(metaEntry).use { it.readTextCapped(MAX_BACKUP_BYTES) })
                    }.getOrNull()
                    val version = meta?.optInt("version", -1) ?: -1
                    if (version !in 1..BACKUP_VERSION) {
                        return RestoreOutcome.Invalid(
                            listOf("Backup version $version is not supported (this app reads v1–v$BACKUP_VERSION)."),
                        )
                    }
                    backupVersion = version
                }
                val csvText = zip.getInputStream(csvEntry).use { it.readTextCapped(MAX_BACKUP_BYTES) }
                val days = parseAndValidateCsv(csvText, errors, backupVersion)
                if (errors.isNotEmpty() || days == null) {
                    return RestoreOutcome.Invalid(errors.ifEmpty { listOf("days.csv is empty.") })
                }
                val workouts = if (backupVersion >= 3) {
                    val wEntry = zip.getEntry("workouts.csv")
                    if (wEntry == null) {
                        emptyList()
                    } else {
                        val wText = zip.getInputStream(wEntry).use { it.readTextCapped(MAX_BACKUP_BYTES) }
                        parseAndValidateWorkouts(wText, errors) ?: return RestoreOutcome.Invalid(errors)
                    }
                } else {
                    emptyList()
                }
                val prefs = mutableMapOf<String, String>()
                zip.getEntry("prefs.json")?.let { entry ->
                    val obj = runCatching {
                        JSONObject(zip.getInputStream(entry).use { it.readTextCapped(MAX_BACKUP_BYTES) })
                    }.getOrNull()
                    if (obj == null) {
                        errors += "prefs.json is damaged — days are fine, settings were skipped."
                    } else {
                        for (key in KNOWN_PREF_KEYS) {
                            if (obj.has(key)) prefs[key] = obj.optString(key, "")
                        }
                    }
                }
                if (errors.isNotEmpty()) return RestoreOutcome.Invalid(errors)
                return RestoreOutcome.Success(days, prefs, workouts)
            }
        } catch (e: SizeCapExceeded) {
            return RestoreOutcome.Invalid(
                listOf("That backup would inflate past 5 MB — nothing was changed."),
            )
        } catch (e: Exception) {
            return RestoreOutcome.Invalid(listOf("Could not read the backup."))
        }
    }

    private val KNOWN_PREF_KEYS = setOf(
        "goal", "heightCm", "weightKg", "stepLenCm", "units",
        "theme", "sensitivity", "treadmill", "hcMode",
    )

    /**
     * All-or-nothing validation. Returns null when any row fails; every
     * failure is recorded with a line number so the user gets a real reason.
     */
    fun parseAndValidateCsv(
        text: String,
        errors: MutableList<String>,
        backupVersion: Int = BACKUP_VERSION,
    ): List<RestoredDay>? {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) {
            errors += "The file is empty."
            return null
        }
        val expectedHeader = when {
            backupVersion >= 3 -> CSV_HEADER
            backupVersion == 2 -> CSV_HEADER_V2
            else -> CSV_HEADER_V1
        }
        if (lines.first().trim() != expectedHeader) {
            errors += "First line must be the Tally header: $expectedHeader."
            return null
        }
        val today = LocalDate.now().toEpochDay()
        val out = mutableListOf<RestoredDay>()
        val seen = mutableSetOf<Long>()
        lines.drop(1).take(MAX_RESTORE_ROWS + 1).forEachIndexed { index, raw ->
            val lineNo = index + 2
            if (out.size > MAX_RESTORE_ROWS) {
                errors += "More than $MAX_RESTORE_ROWS days — file too large."
                return null
            }
            val cells = splitCsv(raw)
            val wantCols = when {
                backupVersion >= 3 -> 12
                backupVersion == 2 -> 11
                else -> 10
            }
            if (cells.size != wantCols) {
                errors += "Line $lineNo: expected $wantCols columns, found ${cells.size}."
                return@forEachIndexed
            }
            try {
                val epochDay = cells[0].toLong()
                val steps = cells[2].toInt()
                val distanceM = cells[3].toFloat()
                val kcal = cells[4].toFloat()
                val activeMin = cells[5].toInt()
                val goal = cells[6].toInt()
                val manualDelta = cells[7].toInt()
                // Undo the export-time formula-escape (a single leading quote added
                // when source started with =, +, - or @); see sanitizeForCsv.
                val source = cells[8].let { s ->
                    if (s.length >= 2 && s[0] == '\'' && s[1] in FORMULA_TRIGGERS) s.drop(1) else s
                }
                val updatedAt = cells[9].toLong()
                val restDay = if (backupVersion >= 2) {
                    when (cells[10].trim()) {
                        "1" -> true
                        "0" -> false
                        else -> {
                            errors += "Line $lineNo: rest day must be 0 or 1."
                            return@forEachIndexed
                        }
                    }
                } else {
                    false
                }
                val floors = if (backupVersion >= 3) {
                    val f = cells[11].toIntOrNull()
                    if (f == null || f < 0 || f > 10_000) {
                        errors += "Line $lineNo: $f floors is impossible."
                        return@forEachIndexed
                    }
                    f
                } else {
                    0
                }
                val expectedDate = runCatching { LocalDate.ofEpochDay(epochDay).toString() }
                    .getOrNull()
                when {
                    epochDay < 1 || epochDay > today + 1 ->
                        errors += "Line $lineNo: date $epochDay is impossible."
                    cells[1] != expectedDate ->
                        errors += "Line $lineNo: date text does not match epochDay."
                    steps < 0 || steps > 500_000 ->
                        errors += "Line $lineNo: $steps steps is impossible."
                    distanceM < 0 || distanceM > 500_000 ->
                        errors += "Line $lineNo: distance $distanceM m is impossible."
                    kcal < 0 || kcal > 200_000 ->
                        errors += "Line $lineNo: $kcal kcal is impossible."
                    activeMin < 0 || activeMin > 1440 ->
                        errors += "Line $lineNo: $activeMin active minutes is impossible."
                    goal < 0 || goal > 200_000 ->
                        errors += "Line $lineNo: goal $goal is impossible."
                    manualDelta < -MANUAL_DELTA_ABS_MAX || manualDelta > MANUAL_DELTA_ABS_MAX ->
                        errors += "Line $lineNo: manual correction $manualDelta is impossible."
                    updatedAt <= 0 ->
                        errors += "Line $lineNo: timestamp is missing."
                    !seen.add(epochDay) ->
                        errors += "Line $lineNo: day $epochDay appears twice."
                    else -> out += RestoredDay(
                        epochDay, steps, distanceM, kcal, activeMin,
                        goal, manualDelta, source, updatedAt, restDay, floors,
                    )
                }
            } catch (e: NumberFormatException) {
                errors += "Line $lineNo: a number is not a number (${e.message})."
            } catch (e: Exception) {
                errors += "Line $lineNo: unreadable (${e.message})."
            }
            if (errors.size >= MAX_ERRORS) return null
        }
        if (lines.size - 1 > MAX_RESTORE_ROWS) {
            errors += "More than $MAX_RESTORE_ROWS days — file too large."
            return null
        }
        if (errors.isEmpty() && out.isEmpty()) {
            errors += "No day rows found — only the header line."
            return null
        }
        return if (errors.isEmpty()) out else null
    }

    private const val MAX_RESTORE_ROWS = 5000
    private const val MAX_ERRORS = 20
    /** Workouts are capped separately: routes are the bulky part of a backup. */
    private const val MAX_WORKOUT_ROWS = 500
    /** Total inflated-size cap for a restore (zip-bomb guard), enforced before parse. */
    private const val MAX_BACKUP_BYTES = 5L * 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 64
    /** Absurd manual corrections are rejected; StepRepository clamps defensively too. */
    private const val MANUAL_DELTA_ABS_MAX = 100_000
    /** Longest route kept per workout: ~50k fixes, far past any real session. */
    private const val MAX_POLYLINE_CHARS = 1_000_000
    private val FORMULA_TRIGGERS = setOf('=', '+', '-', '@')

    /** Thrown when any restore stream exceeds [MAX_BACKUP_BYTES]. */
    private class SizeCapExceeded : java.io.IOException("backup exceeds 5 MB size cap")

    private fun java.io.InputStream.copyCapped(out: java.io.OutputStream, cap: Long) {
        var total = 0L
        val buf = ByteArray(8192)
        while (true) {
            val n = read(buf)
            if (n < 0) return
            total += n
            if (total > cap) throw SizeCapExceeded()
            out.write(buf, 0, n)
        }
    }

    private fun java.io.InputStream.readTextCapped(cap: Long): String {
        val bos = java.io.ByteArrayOutputStream()
        copyCapped(bos, cap)
        return bos.toString(Charsets.UTF_8.name())
    }

    /**
     * Formula-injection guard: a cell starting with =, +, - or @ would execute
     * as a formula when the CSV is opened in a spreadsheet. A single leading
     * quote neutralizes it (stripped again on import).
     */
    private fun sanitizeForCsv(value: String): String =
        if (value.firstOrNull() in FORMULA_TRIGGERS) "'$value" else value

    private fun tallyDir(context: Context): File {
        // App-specific storage: no storage permission needed on any API level.
        val dir = File(context.getExternalFilesDir(null), "Tally")
        if (!dir.isDirectory) dir.mkdirs()
        return dir
    }

    private fun csvCell(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    /**
     * All-or-nothing workout validation (v3+). Missing/empty workouts.csv is
     * NOT an error — callers treat that as "no workouts backed up".
     */
    fun parseAndValidateWorkouts(
        text: String,
        errors: MutableList<String>,
    ): List<RestoredWorkout>? {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        if (lines.first().trim() != WORKOUT_HEADER) {
            errors += "workouts.csv header is not a Tally workout header."
            return null
        }
        val out = mutableListOf<RestoredWorkout>()
        val seen = mutableSetOf<String>()
        val validTypes = setOf("walk", "run", "hike", "ride", "treadmill")
        lines.drop(1).take(MAX_WORKOUT_ROWS + 1).forEachIndexed { index, raw ->
            val lineNo = index + 2
            if (out.size > MAX_WORKOUT_ROWS) {
                errors += "More than $MAX_WORKOUT_ROWS workouts — file too large."
                return null
            }
            val cells = splitCsv(raw)
            if (cells.size != 8) {
                errors += "Workout line $lineNo: expected 8 columns, found ${cells.size}."
                return@forEachIndexed
            }
            try {
                val id = cells[0]
                val type = cells[1].lowercase()
                val startMs = cells[2].toLong()
                val endMs = cells[3].toLong()
                val steps = cells[4].toInt()
                val distanceM = cells[5].toFloat()
                val pausedMs = cells[6].toLong()
                val poly = cells[7].ifBlank { null }?.take(MAX_POLYLINE_CHARS)
                when {
                    id.isBlank() || id.length > 100 ->
                        errors += "Workout line $lineNo: bad id."
                    !seen.add(id) ->
                        errors += "Workout line $lineNo: id appears twice."
                    type !in validTypes ->
                        errors += "Workout line $lineNo: unknown type $type."
                    startMs <= 0 || endMs <= startMs ->
                        errors += "Workout line $lineNo: bad timestamps."
                    steps < 0 || steps > 500_000 ->
                        errors += "Workout line $lineNo: $steps steps is impossible."
                    distanceM < 0 || distanceM > 500_000 ->
                        errors += "Workout line $lineNo: bad distance."
                    pausedMs < 0 || pausedMs > (endMs - startMs) ->
                        errors += "Workout line $lineNo: bad paused time."
                    else -> out += RestoredWorkout(
                        id, type, startMs, endMs, steps, distanceM, pausedMs, poly,
                    )
                }
            } catch (e: NumberFormatException) {
                errors += "Workout line $lineNo: a number is not a number."
            } catch (e: Exception) {
                errors += "Workout line $lineNo: unreadable."
            }
            if (errors.size >= MAX_ERRORS) return null
        }
        if (lines.size - 1 > MAX_WORKOUT_ROWS) {
            errors += "More than $MAX_WORKOUT_ROWS workouts — file too large."
            return null
        }
        return if (errors.isEmpty()) out else null
    }

    /** Minimal CSV split supporting quoted cells with "" escapes. */
    fun splitCsv(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && line.getOrNull(i + 1) == '"' -> {
                    cur.append('"')
                    i += 2
                }
                c == '"' -> {
                    inQuotes = !inQuotes
                    i++
                }
                c == ',' && !inQuotes -> {
                    out += cur.toString()
                    cur.clear()
                    i++
                }
                else -> {
                    cur.append(c)
                    i++
                }
            }
        }
        out += cur.toString()
        return out
    }
}
