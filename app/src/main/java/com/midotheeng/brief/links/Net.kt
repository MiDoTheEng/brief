package com.midotheeng.brief.links

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

class Page(val url: String, val type: String, val body: String?)

object Net {
    private val URL_RE = Regex("""https?://[^\s"'<>]+""")

    fun firstUrl(text: String): String? = URL_RE.find(text)?.value?.trimEnd('.', ',', ')', ']')

    /**
     * Fetches a URL, following every redirect by hand (including http→https),
     * and reads the body only when it is text (HTML/JSON).
     */
    fun get(
        start: String,
        ua: String = MOBILE_UA,
        headers: Map<String, String> = emptyMap(),
        readBody: Boolean = true,
    ): Page {
        var url = start
        repeat(10) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 12000; c.readTimeout = 20000
            c.setRequestProperty("User-Agent", ua)
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw Exception("Bad redirect")
                    url = URL(URL(url), loc).toString()
                    return@repeat
                }
                if (code !in 200..299) throw Exception("HTTP $code")
                val type = c.contentType.orEmpty().lowercase()
                val textual = type.isEmpty() || type.contains("text") || type.contains("json") || type.contains("xml") || type.contains("javascript")
                val body = if (readBody && textual) c.inputStream.bufferedReader().use { it.readText() } else null
                return Page(url, type, body)
            } finally { c.disconnect() }
        }
        throw Exception("Too many redirects")
    }

    fun json(url: String, headers: Map<String, String> = emptyMap(), ua: String = MOBILE_UA): JSONObject? = try {
        val p = get(url, ua, headers + ("Accept" to "application/json"))
        p.body?.takeIf { it.isNotBlank() }?.let { JSONObject(it) }
    } catch (e: Exception) { null }

    fun query(url: String, key: String): String? =
        Regex("""[?&#]${Regex.escape(key)}=([^&#]+)""").find(url)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") }

    /** Decodes a JSON string body as it appears inside page source (\/ & etc.). */
    fun unescape(s: String): String = try { JSONObject("{\"v\":\"$s\"}").getString("v") } catch (e: Exception) { s.replace("\\/", "/") }

    fun meta(html: String, prop: String): String? {
        val p = Regex.escape(prop)
        val a = Regex("""<meta[^>]+(?:property|name)=["']$p["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
        val b = Regex("""<meta[^>]+content=["']([^"']+)["'][^>]*(?:property|name)=["']$p["']""", RegexOption.IGNORE_CASE).find(html)
        return (a ?: b)?.groupValues?.get(1)?.replace("&amp;", "&")
    }

    fun extFromType(type: String, url: String): String {
        val t = type.substringBefore(';').trim()
        return when {
            t == "image/jpeg" || t == "image/jpg" -> "jpg"
            t.startsWith("image/") -> t.removePrefix("image/").replace("svg+xml", "svg")
            t == "video/mp4" -> "mp4"
            t.startsWith("video/") -> t.removePrefix("video/")
            else -> Regex("""\.(jpe?g|png|gif|webp|mp4|mov|webm)(?:$|[?#])""", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.get(1)?.lowercase()?.replace("jpeg", "jpg") ?: "jpg"
        }
    }
}
