package com.example.research

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ExportedPdfResult(
    val file: File,
    val contentUri: Uri,
    val byteSize: Long
)

object ResearchPdfExporter {

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 44f
    private const val CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN

    fun exportReportToPdf(context: Context, report: DeepResearchReport): ExportedPdfResult {
        val reportsDir = File(context.filesDir, "reports").apply { mkdirs() }
        val safeTopic = report.topic.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(32).ifBlank { "Report" }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(report.createdAtMs))
        val outFile = File(reportsDir, "Mahi_Research_${safeTopic}_$timestamp.pdf")

        val nativeSucceeded = tryExportWithAndroidPdfDocument(outFile, report)
        if (!nativeSucceeded || !outFile.exists() || outFile.length() == 0L) {
            writePurePdfFallback(outFile, report)
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
        return ExportedPdfResult(
            file = outFile,
            contentUri = uri,
            byteSize = outFile.length()
        )
    }

    private fun tryExportWithAndroidPdfDocument(outFile: File, report: DeepResearchReport): Boolean {
        var pdf: PdfDocument? = null
        return try {
            pdf = PdfDocument()
            val titlePaint = Paint().apply {
                color = Color.rgb(25, 20, 60)
                textSize = 18f
                isFakeBoldText = true
                isAntiAlias = true
            }
            val headingPaint = Paint().apply {
                color = Color.rgb(180, 25, 95)
                textSize = 13f
                isFakeBoldText = true
                isAntiAlias = true
            }
            val bodyPaint = Paint().apply {
                color = Color.rgb(30, 41, 59)
                textSize = 10.5f
                isAntiAlias = true
            }
            val metaPaint = Paint().apply {
                color = Color.rgb(100, 116, 139)
                textSize = 9.5f
                isAntiAlias = true
            }

            var pageNumber = 1
            var page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
            var canvas = page.canvas
            var y = MARGIN + 10f

            fun newPageIfNeeded(neededHeight: Float) {
                if (y + neededHeight > PAGE_HEIGHT - MARGIN) {
                    pdf.finishPage(page)
                    pageNumber++
                    page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
                    canvas = page.canvas
                    y = MARGIN
                }
            }

            fun drawWrappedText(text: String, paint: Paint, lineHeight: Float) {
                val paragraphs = text.split("\n")
                for (paragraph in paragraphs) {
                    if (paragraph.isBlank()) {
                        y += lineHeight * 0.5f
                        continue
                    }
                    val words = paragraph.split(" ")
                    var line = StringBuilder()
                    for (word in words) {
                        val candidate = if (line.isEmpty()) word else "$line $word"
                        if (paint.measureText(candidate) <= CONTENT_WIDTH) {
                            line = StringBuilder(candidate)
                        } else {
                            newPageIfNeeded(lineHeight)
                            canvas.drawText(line.toString(), MARGIN, y, paint)
                            y += lineHeight
                            line = StringBuilder(word)
                        }
                    }
                    if (line.isNotEmpty()) {
                        newPageIfNeeded(lineHeight)
                        canvas.drawText(line.toString(), MARGIN, y, paint)
                        y += lineHeight
                    }
                }
            }

            drawWrappedText("Mahi AI — Deep Research Report", titlePaint, 22f)
            val dateStr = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.US).format(Date(report.createdAtMs))
            drawWrappedText("Topic: ${report.topic}  |  Language: ${report.language.displayName}  |  $dateStr", metaPaint, 15f)
            y += 10f

            newPageIfNeeded(30f)
            drawWrappedText("1. Executive Summary", headingPaint, 18f)
            drawWrappedText(report.executiveSummary, bodyPaint, 14f)
            y += 8f

            newPageIfNeeded(30f)
            drawWrappedText("2. Key Findings", headingPaint, 18f)
            report.keyFindings.forEach { finding ->
                drawWrappedText("• $finding", bodyPaint, 14f)
            }
            y += 8f

            newPageIfNeeded(30f)
            drawWrappedText("3. Evidence Comparison & Conflicting Information", headingPaint, 18f)
            drawWrappedText(report.conflictingInformation, bodyPaint, 14f)
            y += 8f

            newPageIfNeeded(30f)
            drawWrappedText("4. Detailed Analysis", headingPaint, 18f)
            drawWrappedText(report.detailedAnalysis, bodyPaint, 14f)
            y += 8f

            newPageIfNeeded(30f)
            drawWrappedText("5. Verified Sources & Citations (${report.sources.size})", headingPaint, 18f)
            report.sources.forEach { src ->
                drawWrappedText("[${src.index}] ${src.title} (${src.domain})", bodyPaint, 14f)
                drawWrappedText("    ${src.url}", metaPaint, 13f)
            }

            pdf.finishPage(page)
            FileOutputStream(outFile).use { fos ->
                pdf.writeTo(fos)
            }
            true
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { pdf?.close() }
        }
    }

    private fun writePurePdfFallback(outFile: File, report: DeepResearchReport) {
        val rawLines = buildList {
            add("Mahi AI - Deep Research Report")
            add("Topic: ${report.topic} | Language: ${report.language.displayName}")
            add("")
            add("1. Executive Summary")
            addAll(wrapAsciiLines(report.executiveSummary, 84))
            add("")
            add("2. Key Findings")
            report.keyFindings.forEach { addAll(wrapAsciiLines("- $it", 84)) }
            add("")
            add("3. Conflicting Information")
            addAll(wrapAsciiLines(report.conflictingInformation, 84))
            add("")
            add("4. Detailed Analysis")
            addAll(wrapAsciiLines(report.detailedAnalysis, 84))
            add("")
            add("5. Sources (${report.sources.size})")
            report.sources.forEach { src ->
                addAll(wrapAsciiLines("[${src.index}] ${src.title} - ${src.url}", 84))
            }
        }.take(45)

        val streamContent = buildString {
            append("BT\n/F1 10 Tf\n44 790 Td\n14 TL\n")
            rawLines.forEachIndexed { idx, line ->
                val escaped = escapePdfAscii(line)
                if (idx == 0) append("($escaped) Tj\n") else append("T* ($escaped) Tj\n")
            }
            append("ET\n")
        }

        val objects = listOf(
            "1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n",
            "2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj\n",
            "3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >> endobj\n",
            "4 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj\n",
            "5 0 obj << /Length ${streamContent.toByteArray(Charsets.ISO_8859_1).size} >> stream\n${streamContent}endstream\nendobj\n"
        )

        val out = StringBuilder("%PDF-1.4\n")
        val offsets = mutableListOf<Int>()
        objects.forEach { obj ->
            offsets.add(out.toString().toByteArray(Charsets.ISO_8859_1).size)
            out.append(obj)
        }
        val xrefStart = out.toString().toByteArray(Charsets.ISO_8859_1).size
        out.append("xref\n0 ${objects.size + 1}\n")
        out.append("0000000000 65535 f \n")
        offsets.forEach { off ->
            out.append(String.format(Locale.US, "%010d 00000 n \n", off))
        }
        out.append("trailer << /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xrefStart\n%%EOF\n")

        FileOutputStream(outFile).use { fos ->
            fos.write(out.toString().toByteArray(Charsets.ISO_8859_1))
        }
    }

    private fun wrapAsciiLines(text: String, maxChars: Int): List<String> {
        val result = mutableListOf<String>()
        text.split("\n").forEach { para ->
            if (para.isBlank()) {
                result.add("")
            } else {
                var remaining = para.trim()
                while (remaining.length > maxChars) {
                    val splitAt = remaining.lastIndexOf(' ', maxChars).takeIf { it > 0 } ?: maxChars
                    result.add(remaining.substring(0, splitAt).trim())
                    remaining = remaining.substring(splitAt).trim()
                }
                if (remaining.isNotEmpty()) result.add(remaining)
            }
        }
        return result
    }

    private fun escapePdfAscii(text: String): String {
        val clean = text.map { if (it.code in 32..126) it else '?' }.joinToString("")
        return clean.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
    }

    fun openOrSharePdf(context: Context, pdfUri: Uri) {
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(pdfUri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(viewIntent)
        } catch (_: Exception) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, pdfUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(
                Intent.createChooser(shareIntent, "Open or Share Research PDF")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
