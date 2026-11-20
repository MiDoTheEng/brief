package com.midotheeng.brief

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class Seg(val start: Double, val text: String)

open class BriefException(msg: String) : Exception(msg)

/**
 * Groq's free API (OpenAI-compatible): Whisper turns speech into text,
 * then a chat model writes the brief.
 */
object Groq {
    private const val BASE = "https://api.groq.com/openai/v1"
    const val WHISPER = "whisper-large-v3"

    /** Best first. The first one this account can use is picked. */
    private val CHAT_PREFS = listOf(
        "openai/gpt-oss-120b",
        "llama-3.3-70b-versatile",
        "openai/gpt-oss-20b",
        "meta-llama/llama-4-maverick-17b-128e-instruct",
        "meta-llama/llama-4-scout-17b-16e-instruct",
        "llama-3.1-8b-instant",
    )

    private class Http(val code: Int, val body: String, val retryAfter: Double?)

    // ------------------------------------------------------------------ models
    fun models(key: String): List<String> {
        val r = send(key, "GET", "$BASE/models", null, null)
        check(key, r)
        val arr = JSONObject(r.body).optJSONArray("data") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id") }
    }

    fun pickChatModel(key: String, saved: String): String {
        if (saved.isNotBlank()) return saved
        val have = try { models(key).toSet() } catch (e: BriefException) { throw e } catch (e: Exception) { emptySet() }
        return CHAT_PREFS.firstOrNull { it in have }
            ?: have.firstOrNull { !it.contains("whisper") && !it.contains("guard") && !it.contains("tts") && !it.contains("orpheus") }
            ?: CHAT_PREFS.first()
    }

    // ------------------------------------------------------------------ speech → text
    suspend fun transcribe(key: String, file: File, mime: String, onWait: (String) -> Unit): List<Seg> {
        val r = withRetries(onWait) { multipart(key, file, mime) }
        check(key, r)
        val o = JSONObject(r.body)
        val segs = o.optJSONArray("segments")
        if (segs == null || segs.length() == 0) {
            val t = o.optString("text").trim()
            return if (t.isEmpty()) emptyList() else listOf(Seg(0.0, t))
        }
        return (0 until segs.length()).mapNotNull { i ->
            val s = segs.optJSONObject(i) ?: return@mapNotNull null
            val t = s.optString("text").trim()
            if (t.isEmpty()) null else Seg(s.optDouble("start", 0.0), t)
        }
    }

    // ------------------------------------------------------------------ chat
    suspend fun chat(key: String, model: String, system: String, user: String, maxTokens: Int, onWait: (String) -> Unit): String {
        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0.3)
            put("max_completion_tokens", maxTokens)
            if (model.startsWith("openai/gpt-oss")) put("reasoning_effort", "low")
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                put(JSONObject().put("role", "user").put("content", user))
            })
        }.toString().toByteArray()
        val r = withRetries(onWait) { send(key, "POST", "$BASE/chat/completions", body, "application/json") }
        check(key, r)
        val msg = JSONObject(r.body).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
        val text = msg?.optString("content").orEmpty().trim()
        if (text.isEmpty()) throw BriefException("The AI returned an empty answer. Try again.")
        return text
    }

    // ------------------------------------------------------------------ plumbing
    /** Waits out short "too many requests" limits; gives up on the daily one. */
    private suspend fun withRetries(onWait: (String) -> Unit, call: () -> Http): Http {
        var tries = 0
        while (true) {
            val r = try { call() } catch (e: java.io.IOException) {
                if (++tries > 3) throw BriefException("Network problem: ${e.message}")
                delay(3000L * tries); continue
            }
            if (r.code == 429 || r.code == 503 || r.code == 502) {
                val wait = r.retryAfter ?: waitFromMessage(r.body) ?: 20.0
                if (wait > 120 || ++tries > 8) {
                    throw BriefException(
                        "Groq's free limit is used up for now" +
                            (if (wait > 120) " (it resets in about ${fmtWait(wait)})." else ".") +
                            " Your brief is saved; tap Retry later."
                    )
                }
                onWait("Free limit — waiting ${wait.toInt() + 1}s…")
                delay(((wait + 1) * 1000).toLong())
                continue
            }
            return r
        }
    }

    private fun waitFromMessage(body: String): Double? {
        val m = Regex("""try again in\s+(?:(\d+)h)?(?:(\d+)m)?(?:([\d.]+)s)?""").find(body) ?: return null
        val h = m.groupValues[1].toDoubleOrNull() ?: 0.0
        val min = m.groupValues[2].toDoubleOrNull() ?: 0.0
        val s = m.groupValues[3].toDoubleOrNull() ?: 0.0
        val total = h * 3600 + min * 60 + s
        return if (total > 0) total else null
    }

    private fun fmtWait(sec: Double): String {
        val m = (sec / 60).toInt()
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }

    private fun check(key: String, r: Http) {
        if (r.code in 200..299) return
        val msg = try { JSONObject(r.body).optJSONObject("error")?.optString("message") } catch (e: Exception) { null }
        throw BriefException(
            when (r.code) {
                401, 403 -> if (key.isBlank()) "Add your free Groq key in Settings first." else "Groq didn't accept your key. Check it in Settings."
                413 -> "That piece of audio was too big for Groq."
                else -> "Groq error ${r.code}: ${msg ?: r.body.take(200)}"
            }
        )
    }

    private fun send(key: String, method: String, url: String, body: ByteArray?, type: String?): Http {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20000; c.readTimeout = 180000
        c.setRequestProperty("Authorization", "Bearer $key")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", type)
            c.outputStream.use { it.write(body) }
        }
        return read(c)
    }

    private fun multipart(key: String, file: File, mime: String): Http {
        val boundary = "----brief" + System.nanoTime()
        val c = URL("$BASE/audio/transcriptions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 20000; c.readTimeout = 300000
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.setChunkedStreamingMode(64 * 1024)
        DataOutputStream(c.outputStream).use { out ->
            fun field(name: String, value: String) {
                out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n")
                out.write(value.toByteArray()); out.writeBytes("\r\n")
            }
            field("model", WHISPER)
            field("response_format", "verbose_json")
            field("temperature", "0")
            out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\nContent-Type: $mime\r\n\r\n")
            file.inputStream().use { it.copyTo(out, 64 * 1024) }
            out.writeBytes("\r\n--$boundary--\r\n")
        }
        return read(c)
    }

    private fun read(c: HttpURLConnection): Http = try {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val ra = c.getHeaderField("retry-after")?.toDoubleOrNull()
        Http(code, text, ra)
    } finally { c.disconnect() }
}
