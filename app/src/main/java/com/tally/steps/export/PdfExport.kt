package com.tally.steps.export

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.tally.steps.data.Day
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Day/week/month summary PDF via framework [PdfDocument] (stdlib, no dep).
 *
 * Plain-data like [BackupExport]: callers hand in Days, this file never
 * touches Room/DataStore. Written to the app-specific Tally dir
 * (FileProvider-free — the user moves/shares the file themselves).
 */
object PdfExport {
    private const val PAGE_W = 595 // A4 @72dpi
    private const val PAGE_H = 842
    private const val MARGIN = 48f
    private const val ROW_H = 26f
    private const val TITLE_SIZE = 18f
    private const val BODY_SIZE = 11f

    /**
     * Summary table of date/steps/distance/kcal + totals row, oldest first.
     * Distance shown in km (2 decimals); kcal rounded. Never throws empty:
     * an empty list still yields a titled page with zero totals.
     */
    suspend fun exportDays(context: Context, days: List<Day>): File =
        withContext(Dispatchers.IO) {
            val sorted = days.sortedBy { it.epochDay }
            val doc = PdfDocument()
            try {
                var page = startPage(doc)
                var y = drawHeader(page, sorted)
                if (sorted.isEmpty()) {
                    y = drawRow(page, y, listOf("No days recorded yet.", "", "", ""))
                } else {
                    for (d in sorted) {
                        if (y + ROW_H > PAGE_H - MARGIN) {
                            doc.finishPage(page)
                            page = startPage(doc)
                            y = MARGIN + ROW_H // continuation: no repeated header
                        }
                        y = drawRow(
                            page,
                            y,
                            listOf(
                                dateOf(d),
                                "%,d".format(d.steps + d.manualDelta),
                                "%.2f km".format(d.distanceM / 1000f),
                                "%,d kcal".format(d.kcal.toInt()),
                            ),
                        )
                    }
                }
                // Totals need a rule + one row: if they don't fit, continue on a fresh page.
                if (y + ROW_H * 2 > PAGE_H - MARGIN) {
                    doc.finishPage(page)
                    page = startPage(doc)
                    y = MARGIN
                }
                drawTotals(page, y, sorted)
                doc.finishPage(page)
                val dir = tallyDir(context)
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val file = File(dir, "tally-summary-$stamp.pdf")
                file.outputStream().buffered().use { doc.writeTo(it) }
                file
            } finally {
                doc.close()
            }
        }

    private fun startPage(doc: PdfDocument): PdfDocument.Page {
        val info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create()
        return doc.startPage(info)
    }

    private fun bodyPaint(bold: Boolean = false): Paint = Paint().apply {
        textSize = BODY_SIZE
        isFakeBoldText = bold
        isAntiAlias = true
    }

    /** Title + column heads. Returns the y for the first data row. */
    private fun drawHeader(page: PdfDocument.Page, days: List<Day>): Float {
        val c = page.canvas
        val title = Paint().apply {
            textSize = TITLE_SIZE
            isFakeBoldText = true
            isAntiAlias = true
        }
        val range = if (days.isEmpty()) {
            "Tally step summary"
        } else {
            "Tally step summary — ${dateOf(days.first())} to ${dateOf(days.last())} " +
                "(${days.size} day(s))"
        }
        c.drawText(range, MARGIN, MARGIN, title)
        c.drawText(
            "Steps include your manual corrections. Distance and calories are estimates.",
            MARGIN,
            MARGIN + 20f,
            bodyPaint(),
        )
        return drawRow(
            page,
            MARGIN + 40f,
            listOf("Date", "Steps", "Distance", "Calories"),
            bold = true,
        )
    }

    private fun drawRow(
        page: PdfDocument.Page,
        y: Float,
        cells: List<String>,
        bold: Boolean = false,
    ): Float {
        val c = page.canvas
        val p = bodyPaint(bold)
        // 4 columns across the printable width.
        val xs = floatArrayOf(MARGIN, MARGIN + 150f, MARGIN + 280f, MARGIN + 400f)
        cells.forEachIndexed { i, text -> c.drawText(text, xs[i], y, p) }
        return y + ROW_H
    }

    private fun drawTotals(page: PdfDocument.Page, y: Float, days: List<Day>) {
        val steps = days.sumOf { (it.steps + it.manualDelta).toLong() }
        val distKm = days.sumOf { it.distanceM.toDouble() } / 1000.0
        val kcal = days.sumOf { it.kcal.toLong() }
        val p = bodyPaint()
        page.canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, p)
        drawRow(
            page,
            y + ROW_H,
            listOf(
                "Total",
                "%,d".format(steps),
                "%.2f km".format(distKm),
                "%,d kcal".format(kcal),
            ),
            bold = true,
        )
    }

    private fun dateOf(d: Day): String =
        runCatching { LocalDate.ofEpochDay(d.epochDay).toString() }.getOrDefault("day ${d.epochDay}")

    private fun tallyDir(context: Context): File {
        // App-specific storage: no storage permission needed on any API level.
        val dir = File(context.getExternalFilesDir(null), "Tally")
        if (!dir.isDirectory) dir.mkdirs()
        return dir
    }
}
