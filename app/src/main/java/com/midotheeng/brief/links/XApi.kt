package com.midotheeng.brief.links

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class Kind { PHOTO, VIDEO, GIF }

data class Media(
    val kind: Kind,
    val url: String,        // full-resolution download URL
    val thumb: String,      // preview image
    val ext: String,        // jpg / png / mp4 ...
    val width: Int = 0,
    val height: Int = 0,
    val headers: Map<String, String> = emptyMap(),
)

/** One shared post/page and everything downloadable in it. */
data class Post(val source: String, val id: String, val user: String, val text: String, val media: List<Media>)

class NoMediaException(msg: String = "No photos or videos found here.") : Exception(msg)

object XApi {

    private val ID_RE = Regex("""(?:x|twitter|fxtwitter|vxtwitter|fixupx|fixvx|nitter\.[a-z.]+)\.com/(?:[^/\s]+/)?(?:i/web/)?status(?:es)?/(\d{5,25})""", RegexOption.IGNORE_CASE)
    private val BARE_ID = Regex("""^\s*(\d{5,25})\s*$""")

    /** Pull the tweet id out of whatever text X (or a browser) shared. */
    fun extractId(text: String?): String? {
        if (text == null) return null
        ID_RE.find(text)?.let { return it.groupValues[1] }
        return BARE_ID.find(text)?.groupValues?.get(1)
    }

    suspend fun fetch(id: String): Post = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        for (source in listOf(::fromSyndication, ::fromFx, ::fromVx)) {
            try {
                val t = source(id)
                if (t != null && t.media.isNotEmpty()) return@withContext t
                if (t != null) errors += "no media"
            } catch (e: Exception) {
                errors += (e.message ?: e.javaClass.simpleName)
            }
        }
        if (errors.isNotEmpty() && errors.all { it == "no media" })
            throw NoMediaException("This post has no photos, videos or GIFs.")
        throw Exception("Couldn't read this post. It may be private, deleted or age-restricted.")
    }

    // ---------- 1) X's own embed API (no login) ----------
    private fun fromSyndication(id: String): Post? {
        val json = Net.json("https://cdn.syndication.twimg.com/tweet-result?id=$id&lang=en&token=${token(id)}") ?: return null
        if (json.optString("__typename") == "TweetTombstone") return null
        var details = json.optJSONArray("mediaDetails")
        if (details == null || details.length() == 0) {
            details = json.optJSONObject("quoted_tweet")?.optJSONArray("mediaDetails")
        }
        val media = mutableListOf<Media>()
        if (details != null) for (i in 0 until details.length()) {
            val m = details.getJSONObject(i)
            val thumb = m.optString("media_url_https")
            val w = m.optJSONObject("original_info")?.optInt("width") ?: 0
            val h = m.optJSONObject("original_info")?.optInt("height") ?: 0
            when (m.optString("type")) {
                "photo" -> media += photo(thumb, w, h)
                "video", "animated_gif" -> {
                    val best = bestMp4(m.optJSONObject("video_info")?.optJSONArray("variants")) ?: continue
                    media += Media(if (m.optString("type") == "video") Kind.VIDEO else Kind.GIF, best, thumb, "mp4", w, h)
                }
            }
        }
        val user = json.optJSONObject("user")?.optString("screen_name").orEmpty()
        return Post("x", id, user, json.optString("text"), media)
    }

    // ---------- 2) FxTwitter ----------
    private fun fromFx(id: String): Post? {
        val tw = Net.json("https://api.fxtwitter.com/status/$id")?.optJSONObject("tweet") ?: return null
        var all = tw.optJSONObject("media")?.optJSONArray("all")
        if (all == null || all.length() == 0) all = tw.optJSONObject("quote")?.optJSONObject("media")?.optJSONArray("all")
        val media = mutableListOf<Media>()
        if (all != null) for (i in 0 until all.length()) {
            val m = all.getJSONObject(i)
            val w = m.optInt("width"); val h = m.optInt("height")
            when (m.optString("type")) {
                "photo" -> media += photo(m.optString("url"), w, h)
                "video", "gif" -> {
                    val url = bestMp4(m.optJSONArray("variants")) ?: m.optString("url")
                    if (url.isBlank()) continue
                    media += Media(if (m.optString("type") == "video") Kind.VIDEO else Kind.GIF, url, m.optString("thumbnail_url"), "mp4", w, h)
                }
            }
        }
        return Post("x", id, tw.optJSONObject("author")?.optString("screen_name").orEmpty(), tw.optString("text"), media)
    }

    // ---------- 3) vxTwitter ----------
    private fun fromVx(id: String): Post? {
        val json = Net.json("https://api.vxtwitter.com/Twitter/status/$id") ?: return null
        val arr = json.optJSONArray("media_extended")
        val media = mutableListOf<Media>()
        if (arr != null) for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val size = m.optJSONObject("size")
            val w = size?.optInt("width") ?: 0; val h = size?.optInt("height") ?: 0
            when (m.optString("type")) {
                "image" -> media += photo(m.optString("url"), w, h)
                "video", "gif" -> media += Media(
                    if (m.optString("type") == "video") Kind.VIDEO else Kind.GIF,
                    m.optString("url"), m.optString("thumbnail_url"), "mp4", w, h
                )
            }
        }
        return Post("x", id, json.optString("user_screen_name"), json.optString("text"), media)
    }

    /** Turn any pbs.twimg.com photo link into its original-size version. */
    private fun photo(raw: String, w: Int, h: Int): Media {
        val base = raw.substringBefore('?')
        val m = Regex("""^(https://pbs\.twimg\.com/media/[^.?]+)\.(\w+)$""").find(base)
        return if (m != null) {
            val (stem, ext) = m.destructured
            Media(Kind.PHOTO, "$stem?format=$ext&name=orig", "$stem?format=$ext&name=small", ext, w, h)
        } else {
            val fmt = Regex("""format=(\w+)""").find(raw)?.groupValues?.get(1) ?: "jpg"
            Media(Kind.PHOTO, raw, raw, fmt, w, h)
        }
    }

    private fun bestMp4(variants: JSONArray?): String? {
        if (variants == null) return null
        var best: String? = null; var bestRate = -1
        for (i in 0 until variants.length()) {
            val v = variants.getJSONObject(i)
            val type = v.optString("content_type", v.optString("type"))
            val url = v.optString("url", v.optString("src"))
            if (!type.contains("mp4") && !url.contains(".mp4")) continue
            val rate = v.optInt("bitrate", v.optInt("bit_rate", 0))
            if (rate > bestRate) { bestRate = rate; best = url }
        }
        return best
    }


    // The embed API wants a token derived from the id: ((id / 1e15) * PI).toString(36) with 0s and dots removed.
    fun token(id: String): String =
        jsToString36(id.toDouble() / 1e15 * Math.PI).replace(Regex("(0+|\\.)"), "")

    private const val CH = "0123456789abcdefghijklmnopqrstuvwxyz"

    /** Exact port of JavaScript's Number.prototype.toString(radix) for positive doubles (V8). */
    private fun jsToString36(value: Double, radix: Int = 36): String {
        val buf = CharArray(2200); var ic = 1100; var fc = 1100
        var integer = Math.floor(value); var fraction = value - integer
        var delta = maxOf(Math.nextUp(0.0), 0.5 * (Math.nextUp(value) - value))
        if (fraction >= delta) {
            buf[fc++] = '.'
            do {
                fraction *= radix; delta *= radix
                var digit = fraction.toInt()
                buf[fc++] = CH[digit]; fraction -= digit
                if (fraction > 0.5 || (fraction == 0.5 && (digit and 1) == 1)) {
                    if (fraction + delta > 1) {
                        while (true) {
                            fc--
                            if (fc == 1100) { integer += 1; break }
                            val c = buf[fc]
                            digit = if (c > '9') c - 'a' + 10 else c - '0'
                            if (digit + 1 < radix) { buf[fc++] = CH[digit + 1]; break }
                        }
                        break
                    }
                }
            } while (fraction >= delta)
        }
        while (Math.getExponent(integer / radix) > 52) { integer /= radix; buf[--ic] = '0' }
        do {
            val r = integer % radix
            buf[--ic] = CH[r.toInt()]
            integer = (integer - r) / radix
        } while (integer > 0)
        return String(buf, ic, fc - ic)
    }
}
