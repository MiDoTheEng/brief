package com.midotheeng.brief.links

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Instagram posts, carousels and reels (public only).
 * 1) The logged-out web GraphQL request Instagram's own site makes (with its session tokens)
 * 2) The same request in its simpler GET form
 * 3) The public embed page
 * A reel is never "saved" as just its cover picture: if no video is found, it's an error.
 */
object Instagram {
    private val CODE = Regex("""instagr(?:am\.com|\.am)/(?:[^/?#]+/)?(p|reels?|tv)/([A-Za-z0-9_-]+)""")
    private const val APP_ID = "936619743392459"
    private const val DOC_ID = "8845758582119845"

    fun fetch(link: String): Post {
        var m = CODE.find(link)
        if (m == null) m = CODE.find(Net.get(link, MOBILE_UA, readBody = false).url)
        if (m == null) throw Exception("Brief can read Instagram posts and reels (stories need a login).")
        val kind = m.groupValues[1]; val code = m.groupValues[2]
        val wantVideo = kind != "p"

        val errors = mutableListOf<String>()
        var photosOnly: Post? = null
        val tries: List<Pair<String, () -> Post?>> = listOf(
            "account" to { withLogin(code) },
            "web" to { webPost(code, kind) },
            "api" to { webGet(code) },
            "embed" to { embed(code) },
        )
        for ((name, attempt) in tries) {
            val p = try { attempt() } catch (e: Exception) { errors += "$name: ${e.message}"; continue }
            if (p == null || p.media.isEmpty()) { errors += "$name: nothing"; continue }
            val hasVideo = p.media.any { it.kind == Kind.VIDEO }
            if (hasVideo || !wantVideo) return p
            if (photosOnly == null) photosOnly = p
            errors += "$name: only the cover image"
        }
        if (wantVideo) throw Exception("Instagram didn't give the video (${errors.joinToString("; ")})")
        return photosOnly ?: throw NoMediaException("Instagram didn't share this post. It may be private.")
    }

    // ------------------------------------------------------------------ 0) Logged in (Settings → Connect Instagram)
    fun sessionCookies(): String? = try {
        android.webkit.CookieManager.getInstance().getCookie("https://www.instagram.com")
            ?.takeIf { it.contains("sessionid=") }
    } catch (e: Throwable) { null }

    private fun mediaId(code: String): String {
        val abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        var n = java.math.BigInteger.ZERO
        for (ch in code.take(11)) n = n.multiply(java.math.BigInteger.valueOf(64)).add(java.math.BigInteger.valueOf(abc.indexOf(ch).toLong()))
        return n.toString()
    }

    private fun withLogin(code: String): Post? {
        val jar = sessionCookies() ?: throw Exception("not signed in")
        val cookies = jar.split(';').map { it.trim() }.filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }.toMutableMap()
        val body = request(
            "https://www.instagram.com/api/v1/media/${mediaId(code)}/info/", cookies = cookies,
            headers = mapOf(
                "X-IG-App-ID" to APP_ID, "X-CSRFToken" to (cookies["csrftoken"] ?: ""), "X-ASBD-ID" to "129477",
                "X-Requested-With" to "XMLHttpRequest", "Accept" to "*/*",
                "Referer" to "https://www.instagram.com/p/$code/",
            ),
        ) ?: throw Exception("signed in, but Instagram refused")
        val item = try { JSONObject(body).optJSONArray("items")?.optJSONObject(0) } catch (e: Exception) { null }
            ?: throw Exception("signed in, but no media in reply")
        return fromItem(code, item)
    }

    // ------------------------------------------------------------------ 1) POST, like instagram.com does
    private fun webPost(code: String, kind: String): Post? {
        // Visit the page first to get the session cookies and the LSD token.
        val cookies = mutableMapOf<String, String>()
        val pagePath = if (kind == "p") "p" else "reel"
        val html = request("https://www.instagram.com/$pagePath/$code/", cookies = cookies) ?: ""
        val lsd = Regex(""""LSD",\[],\{"token":"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: Regex(""""lsd":"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: "AVqbxe3J_YA"
        val csrf = cookies["csrftoken"]
            ?: Regex(""""csrf_token":"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: ""
        val vars = """{"shortcode":"$code","fetch_tagged_user_count":null,"hoisted_comment_id":null,"hoisted_reply_id":null}"""
        val form = linkedMapOf(
            "av" to "0", "__d" to "www", "__user" to "0", "__a" to "1", "__req" to "3",
            "__comet_req" to "7", "dpr" to "2", "lsd" to lsd,
            "fb_api_caller_class" to "RelayModern",
            "fb_api_req_friendly_name" to "PolarisPostActionLoadPostQueryQuery",
            "variables" to vars, "server_timestamps" to "true", "doc_id" to DOC_ID,
        ).entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        val body = request(
            "https://www.instagram.com/graphql/query", cookies = cookies, post = form,
            headers = mapOf(
                "X-IG-App-ID" to APP_ID, "X-FB-LSD" to lsd, "X-CSRFToken" to csrf,
                "X-ASBD-ID" to "129477", "X-FB-Friendly-Name" to "PolarisPostActionLoadPostQueryQuery",
                "Content-Type" to "application/x-www-form-urlencoded",
                "Origin" to "https://www.instagram.com", "Referer" to "https://www.instagram.com/$pagePath/$code/",
                "Sec-Fetch-Site" to "same-origin", "Sec-Fetch-Mode" to "cors", "Accept" to "*/*",
            ),
        ) ?: return null
        return parseGraph(code, body)
    }

    // ------------------------------------------------------------------ 2) GET form
    private fun webGet(code: String): Post? {
        val vars = """{"shortcode":"$code","child_comment_count":3,"fetch_comment_count":40,"parent_comment_count":24,"has_threaded_comments":true}"""
        val body = request(
            "https://www.instagram.com/graphql/query/?doc_id=$DOC_ID&variables=" + URLEncoder.encode(vars, "UTF-8"),
            headers = mapOf(
                "X-IG-App-ID" to APP_ID, "X-ASBD-ID" to "198387", "X-IG-WWW-Claim" to "0",
                "X-Requested-With" to "XMLHttpRequest", "Origin" to "https://www.instagram.com",
                "Referer" to "https://www.instagram.com/p/$code/", "Accept" to "*/*",
            ),
        ) ?: return null
        return parseGraph(code, body)
    }

    private fun parseGraph(code: String, body: String): Post? {
        val json = try { JSONObject(body) } catch (e: Exception) { return null }
        val data = json.optJSONObject("data") ?: return null
        val m = data.optJSONObject("xdt_shortcode_media") ?: data.optJSONObject("shortcode_media")
        if (m != null) return fromShortcodeMedia(code, m)
        // Newer shape: { xdt_api__v1__media__shortcode__web_info: { items: [ … ] } }
        val items = data.keys().asSequence().mapNotNull { data.optJSONObject(it)?.optJSONArray("items") }.firstOrNull()
        val item = items?.optJSONObject(0) ?: return null
        return fromItem(code, item)
    }

    // ------------------------------------------------------------------ 3) Embed page
    private fun embed(code: String): Post? {
        val html = request("https://www.instagram.com/p/$code/embed/captioned/") ?: return null
        Regex(""""contextJSON"\s*:\s*"((?:[^"\\]|\\.)+)"""").find(html)?.let { m ->
            try {
                val ctx = JSONObject(Net.unescape(m.groupValues[1]))
                val sm = ctx.optJSONObject("gql_data")?.optJSONObject("shortcode_media")
                if (sm != null) fromShortcodeMedia(code, sm).let { if (it.media.isNotEmpty()) return it }
            } catch (_: Exception) {}
        }
        // Search the page with all escaping removed, for a video link anywhere in it
        val flat = html.replace("\\\\", "\\").replace("\\\"", "\"").replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
        val media = mutableListOf<Media>()
        val thumb = Regex("""class="EmbeddedMediaImage"[^>]*src="([^"]+)"""").find(flat)?.groupValues?.get(1).orEmpty()
        Regex(""""video_url"\s*:\s*"(https[^"]+)"""").find(flat)?.let {
            media += Media(Kind.VIDEO, it.groupValues[1], thumb, "mp4")
        }
        if (media.isEmpty() && thumb.isNotBlank()) media += Media(Kind.PHOTO, thumb, thumb, "jpg")
        val user = Regex("""class="UsernameText"[^>]*>([^<]+)<""").find(html)?.groupValues?.get(1).orEmpty()
        return Post("instagram", code, user, "", media)
    }

    // ------------------------------------------------------------------ parsing
    private fun fromShortcodeMedia(code: String, m: JSONObject): Post {
        val nodes = m.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")
            ?.let { e -> (0 until e.length()).mapNotNull { e.optJSONObject(it)?.optJSONObject("node") } }
            ?.takeIf { it.isNotEmpty() } ?: listOf(m)
        val media = nodes.mapNotNull { n ->
            val thumb = n.optString("display_url")
            val dims = n.optJSONObject("dimensions")
            val w = dims?.optInt("width") ?: 0; val h = dims?.optInt("height") ?: 0
            val video = n.optString("video_url")
            if (video.startsWith("http")) Media(Kind.VIDEO, video, thumb, "mp4", w, h)
            else {
                val best = n.optJSONArray("display_resources").objs().maxByOrNull { it.optInt("config_width") }
                val url = best?.optString("src")?.takeIf { it.startsWith("http") } ?: thumb
                if (url.startsWith("http")) Media(Kind.PHOTO, url, thumb.ifBlank { url }, "jpg",
                    best?.optInt("config_width") ?: w, best?.optInt("config_height") ?: h) else null
            }
        }
        return Post("instagram", code, m.optJSONObject("owner")?.optString("username").orEmpty(), "", media)
    }

    private fun fromItem(code: String, item: JSONObject): Post {
        val parts = item.optJSONArray("carousel_media").objs().ifEmpty { listOf(item) }
        val media = parts.mapNotNull { p ->
            val img = p.optJSONObject("image_versions2")?.optJSONArray("candidates").objs().maxByOrNull { it.optInt("width") }
            val vid = p.optJSONArray("video_versions").objs().maxByOrNull { it.optInt("width") * 10_000 + it.optInt("height") }
            val thumb = img?.optString("url").orEmpty()
            when {
                vid != null -> Media(Kind.VIDEO, vid.optString("url"), thumb, "mp4", vid.optInt("width"), vid.optInt("height"))
                img != null -> Media(Kind.PHOTO, thumb, thumb, "jpg", img.optInt("width"), img.optInt("height"))
                else -> null
            }
        }
        return Post("instagram", code, item.optJSONObject("user")?.optString("username").orEmpty(), "", media)
    }

    private fun JSONArray?.objs(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    // ------------------------------------------------------------------ HTTP with a cookie jar
    private fun request(
        url: String,
        cookies: MutableMap<String, String> = mutableMapOf(),
        post: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): String? {
        var u = url
        var body = post
        repeat(6) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 12000; c.readTimeout = 20000
            c.setRequestProperty("User-Agent", DESKTOP_UA)
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            if (headers["Accept"] == null) c.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (cookies.isNotEmpty()) c.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            try {
                if (body != null) {
                    c.requestMethod = "POST"; c.doOutput = true
                    c.outputStream.use { it.write(body!!.toByteArray()) }
                }
                c.headerFields["Set-Cookie"]?.forEach { sc ->
                    val kv = sc.substringBefore(';'); val k = kv.substringBefore('=').trim(); val v = kv.substringAfter('=', "").trim()
                    if (k.isNotEmpty() && v.isNotEmpty() && v != "\"\"") cookies[k] = v
                }
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: return null
                    if (loc.contains("/accounts/login")) return null
                    u = URL(URL(u), loc).toString(); body = null
                    return@repeat
                }
                val stream = if (code in 200..299) c.inputStream else return null
                return stream.bufferedReader().use { it.readText() }
            } finally { c.disconnect() }
        }
        return null
    }
}
