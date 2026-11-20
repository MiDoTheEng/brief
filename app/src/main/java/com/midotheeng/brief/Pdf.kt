package com.midotheeng.brief

import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import java.text.DateFormat
import java.util.Date

/** The brief's Markdown, as simple blocks. Shared by the screen and the PDF. */
object Md {
    enum class T { H1, H2, H3, BULLET, PARA, BLANK }
    class Block(val t: T, val text: String, val indent: Int = 0)

    fun parse(md: String): List<Block> = md.lines().map { raw ->
        val line = raw.trimEnd()
        val lead = line.length - line.trimStart().length
        val s = line.trim()
        when {
            s.isEmpty() -> Block(T.BLANK, "")
            s.startsWith("### ") -> Block(T.H3, s.removePrefix("### "))
            s.startsWith("## ") -> Block(T.H2, s.removePrefix("## "))
            s.startsWith("# ") -> Block(T.H1, s.removePrefix("# "))
            s.startsWith("- ") || s.startsWith("* ") || s.startsWith("• ") -> Block(T.BULLET, s.substring(2), if (lead >= 2) 1 else 0)
            Regex("""^\d+[.)]\s""").containsMatchIn(s) -> Block(T.BULLET, s, if (lead >= 2) 1 else 0)
            else -> Block(T.PARA, s)
        }
    }

    /** Splits "**bold** text" into (text, isBold) runs. */
    fun runs(s: String): List<Pair<String, Boolean>> {
        val out = mutableListOf<Pair<String, Boolean>>()
        var bold = false
        s.split("**").forEach { part -> if (part.isNotEmpty()) out += part.replace("__", "") to bold; bold = !bold }
        return out
    }

    fun plain(md: String): String = parse(md).joinToString("\n") { b ->
        val t = runs(b.text).joinToString("") { it.first }
        when (b.t) { T.BULLET -> "  ".repeat(b.indent) + "• " + t; T.H1 -> t.uppercase(); else -> t }
    }
}

object Pdf {
    private const val W = 595
    private const val H = 842
    private const val M = 50

    /** Writes the brief to Downloads/Brief and returns its address. */
    fun export(ctx: Context, b: Brief, md: String): Uri {
        val text = build(b, md)
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Color.rgb(30, 30, 30) }
        val width = W - 2 * M
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setLineSpacing(2f, 1.15f)
            .setIncludePad(true)
            .build()

        val doc = PdfDocument()
        val usable = H - 2 * M - 20
        var line = 0
        var page = 1
        while (line < layout.lineCount) {
            val top = layout.getLineTop(line)
            var last = line
            while (last + 1 < layout.lineCount && layout.getLineBottom(last + 1) - top <= usable) last++
            val p = doc.startPage(PdfDocument.PageInfo.Builder(W, H, page).create())
            val c = p.canvas
            c.save()
            c.translate(M.toFloat(), M.toFloat() - top)
            c.clipRect(0, top, width, layout.getLineBottom(last))
            layout.draw(c)
            c.restore()
            val foot = TextPaint(paint).apply { textSize = 8f; color = Color.GRAY }
            c.drawText("Brief · page $page", M.toFloat(), (H - M / 2).toFloat(), foot)
            doc.finishPage(p)
            line = last + 1
            page++
        }

        val name = safeName(b.title.ifBlank { "Brief" }) + " (" + b.length + "-" + b.lang + ").pdf"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Brief")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw BriefException("Couldn't create the PDF file.")
        ctx.contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) }
        doc.close()
        return uri
    }

    private fun safeName(s: String) = s.replace(Regex("""[\\/:*?"<>|\n\r]"""), " ").trim().take(80)

    private fun build(b: Brief, md: String): CharSequence {
        val sb = SpannableStringBuilder()
        fun span(start: Int, vararg spans: Any) = spans.forEach { sb.setSpan(it, start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        fun inline(s: String) = Md.runs(s).forEach { (t, bold) ->
            val st = sb.length; sb.append(t); if (bold) span(st, StyleSpan(Typeface.BOLD))
        }

        val meta = listOfNotNull(
            b.source.takeIf { it.isNotBlank() },
            b.durationSec.takeIf { it > 0 }?.let { Transcript.stamp(it.toDouble()) },
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(b.created)),
        ).joinToString("  ·  ")
        var first = true
        for (blk in Md.parse(md)) {
            val st = sb.length
            when (blk.t) {
                Md.T.H1 -> {
                    inline(blk.text); span(st, AbsoluteSizeSpan(20), StyleSpan(Typeface.BOLD)); sb.append('\n')
                    if (first) {
                        val ms = sb.length
                        sb.append(meta)
                        if (b.link.isNotBlank()) sb.append('\n').append(b.link)
                        span(ms, AbsoluteSizeSpan(9), ForegroundColorSpan(Color.GRAY)); sb.append("\n\n")
                    }
                }
                Md.T.H2 -> { sb.append('\n'); val s2 = sb.length; inline(blk.text); span(s2, AbsoluteSizeSpan(15), StyleSpan(Typeface.BOLD), ForegroundColorSpan(Color.rgb(20, 70, 140))); sb.append('\n') }
                Md.T.H3 -> { inline(blk.text); span(st, AbsoluteSizeSpan(12), StyleSpan(Typeface.BOLD)); sb.append('\n') }
                Md.T.BULLET -> {
                    val pad = 14 + blk.indent * 14
                    sb.append("•  "); inline(blk.text)
                    sb.append('\n'); span(st, LeadingMarginSpan.Standard(pad - 10, pad + 4))
                }
                Md.T.PARA -> { inline(blk.text); sb.append('\n') }
                Md.T.BLANK -> {}
            }
            if (blk.t != Md.T.BLANK) first = false
        }
        return sb
    }
}
