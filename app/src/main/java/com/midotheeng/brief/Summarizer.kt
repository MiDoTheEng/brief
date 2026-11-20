package com.midotheeng.brief

import java.io.File

/** Turns captions / Whisper output into compact "[m:ss] text" lines. */
object Transcript {
    private val CUE = Regex("""^(?:(\d+):)?(\d{1,2}):(\d{2})[.,]\d{3}\s+-->""")

    fun stamp(sec: Double): String {
        val s = sec.toLong()
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    fun fromVtt(vtt: String): String? {
        val segs = mutableListOf<Seg>()
        var start = -1.0
        var last = ""
        for (raw in vtt.lines()) {
            val line = raw.trim()
            val m = CUE.find(line)
            if (m != null) {
                start = (m.groupValues[1].toDoubleOrNull() ?: 0.0) * 3600 + m.groupValues[2].toDouble() * 60 + m.groupValues[3].toDouble()
                continue
            }
            if (start < 0 || line.isEmpty() || line == "WEBVTT" || line.startsWith("NOTE") || line.startsWith("Kind:") || line.startsWith("Language:")) continue
            val text = line.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&gt;", ">").replace("&lt;", "<").replace("&#39;", "'").replace("&quot;", "\"").trim()
            // Auto captions repeat the previous line as they roll; keep each line once.
            if (text.isEmpty() || text == last) continue
            last = text
            segs += Seg(start, text)
        }
        return if (segs.isEmpty()) null else fromSegs(segs)
    }

    /** Groups the pieces into ~30 second lines so the AI sees timestamps without wasting space. */
    fun fromSegs(segs: List<Seg>): String {
        val sb = StringBuilder()
        var groupStart = -100.0
        for (s in segs.sortedBy { it.start }) {
            if (s.start - groupStart >= 30) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append('[').append(stamp(s.start)).append("] ")
                groupStart = s.start
            } else sb.append(' ')
            sb.append(s.text)
        }
        return sb.toString()
    }
}

object Summarizer {
    /** Rough characters that fit in one free-tier request (leaves room for the answer). */
    private const val CHUNK_CHARS = 10000

    suspend fun listen(key: String, pieces: List<Piece>, progress: (String) -> Unit): String {
        val all = mutableListOf<Seg>()
        pieces.forEachIndexed { i, p ->
            progress(if (pieces.size == 1) "Listening…" else "Listening… part ${i + 1} of ${pieces.size}")
            Groq.transcribe(key, p.file, p.mime, progress).forEach { all += Seg(it.start + p.startSec, it.text) }
        }
        if (all.isEmpty()) throw BriefException("No speech was heard in this video.")
        return Transcript.fromSegs(all)
    }

    suspend fun write(
        key: String, model: String, title: String, transcript: String,
        length: String, lang: String, progress: (String) -> Unit,
    ): String {
        var text = transcript
        var round = 0
        // Long videos: condense part by part first, then write the brief from the notes.
        while (text.length > CHUNK_CHARS) {
            round++
            val parts = split(text, CHUNK_CHARS)
            val notes = parts.mapIndexed { i, part ->
                progress("Reading… part ${i + 1} of ${parts.size}" + if (round > 1) " (round $round)" else "")
                Groq.chat(key, model, NOTES_SYSTEM, part, 1800, progress)
            }
            text = notes.joinToString("\n\n")
            if (round >= 4) { text = text.take(CHUNK_CHARS); break }
        }
        progress("Writing the brief…")
        val user = buildString {
            if (title.isNotBlank()) append("Video title: ").append(title).append("\n\n")
            append(if (round == 0) "Transcript (timestamps in brackets):\n" else "Notes taken from the full transcript (timestamps in brackets):\n")
            append(text)
        }
        return Groq.chat(key, model, briefSystem(length, lang), user, if (length == Prefs.DETAILED) 3500 else 1500, progress).let(::tidy)
    }

    private fun split(text: String, size: Int): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (line in text.lines()) {
            if (cur.length + line.length > size && cur.isNotEmpty()) { out += cur.toString(); cur.clear() }
            if (line.length > size) line.chunked(size).forEach { out += it } else cur.append(line).append('\n')
        }
        if (cur.isNotBlank()) out += cur.toString()
        return out
    }

    /** Some models wrap their answer in ```markdown fences. */
    private fun tidy(s: String) = s.trim().removePrefix("```markdown").removePrefix("```md").removePrefix("```").removeSuffix("```").trim()

    private const val NOTES_SYSTEM =
        "You take dense notes from part of a video transcript. Keep every important idea, fact, number, name, " +
            "step and example, in order, each as a short bullet starting with its [timestamp] from the transcript. " +
            "Drop filler, greetings, ads and repetition. Write the notes in the transcript's own language. Output only the bullets."

    private fun briefSystem(length: String, lang: String): String {
        val language = if (lang == Prefs.AR)
            "Write everything in clear Modern Standard Arabic (العربية الفصحى), even if the video is in another language. Keep technical terms in English in brackets when helpful."
        else "Write everything in clear, simple English, even if the video is in another language."
        val shape = if (length == Prefs.DETAILED) """
            Use exactly this Markdown layout:
            # <a short, specific title for the video>
            **In one line:** <the core idea in one sentence>
            ## Overview
            <one short paragraph: what the video is about, who it's for, the main argument>
            ## Main ideas
            ### <section name> [m:ss]
            - <point, with the specifics: numbers, names, steps, examples>
            (one ### section for each part of the video, in order)
            ## Key facts & numbers
            - <fact>
            ## Takeaways
            - <what to remember or do>
            ## Worth quoting
            - "<a short memorable line>" [m:ss]
            (skip "Worth quoting" if nothing stands out)
        """.trimIndent() else """
            Use exactly this Markdown layout, and keep it short (it should be readable in under a minute):
            # <a short, specific title for the video>
            **In one line:** <the core idea in one sentence>
            ## Key points
            - <3 to 7 bullets, one idea each, with its [m:ss] timestamp>
            ## Takeaways
            - <1 to 3 bullets: what to remember or do>
        """.trimIndent()
        return "You write briefs of videos so someone can get the idea without watching. " +
            "Use only what is in the transcript or notes; never invent details. Timestamps must come from the input. " +
            "No intro, no closing remarks, no code fences.\n$language\n$shape"
    }

    fun cleanup(dir: File) { dir.listFiles()?.forEach { it.deleteRecursively() } }
}
