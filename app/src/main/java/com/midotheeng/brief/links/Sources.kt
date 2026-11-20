package com.midotheeng.brief.links

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Decides which site a shared link belongs to and returns its media. */
object Router {
    suspend fun resolve(shared: String): Post = withContext(Dispatchers.IO) {
        XApi.extractId(shared)?.let { return@withContext XApi.fetch(it) }
        val url = Net.firstUrl(shared) ?: throw Exception("No link found in what was shared.")
        val host = Regex("""https?://([^/?#]+)""").find(url)?.groupValues?.get(1)?.lowercase().orEmpty()
        when {
            host.endsWith("instagram.com") || host.endsWith("instagr.am") -> Instagram.fetch(url)
            host.endsWith("pin.it") || host.contains("pinterest.") -> Pinterest.fetch(url)
            host.endsWith("fb.watch") || host.endsWith("fb.com") || host.contains("facebook.com") -> Facebook.fetch(url)
            host.contains("google.") || host.endsWith("goo.gl") || host.endsWith("share.google") -> Google.fetch(url)
            else -> {
                // A short link (t.co, bit.ly…) may lead to X.
                val page = Net.get(url, readBody = false)
                XApi.extractId(page.url)?.let { XApi.fetch(it) } ?: Generic.fetch(url)
            }
        }
    }
}

// ---------------------------------------------------------------- Pinterest
object Pinterest {
    private val PIN_ID = Regex("""/pin/(?:[^/?#]*--)?(\d+)""")

    fun fetch(link: String): Post {
        val page = Net.get(link, DESKTOP_UA)
        val id = PIN_ID.find(page.url)?.groupValues?.get(1)
            ?: PIN_ID.find(page.body.orEmpty())?.groupValues?.get(1)
            ?: throw Exception("That Pinterest link isn't a pin.")

        api(id)?.let { if (it.media.isNotEmpty()) return it }
        scrape(id, page.body.orEmpty()).let { if (it.media.isNotEmpty()) return it }
        throw NoMediaException("Couldn't find the photo or video in this pin.")
    }

    private fun api(id: String): Post? {
        val data = """{"options":{"id":"$id","field_set_key":"detailed"},"context":{}}"""
        val url = "https://www.pinterest.com/resource/PinResource/get/?source_url=" +
            URLEncoder.encode("/pin/$id/", "UTF-8") + "&data=" + URLEncoder.encode(data, "UTF-8")
        val json = Net.json(url, mapOf("X-Requested-With" to "XMLHttpRequest", "X-Pinterest-PWS-Handler" to "www/pin/[id].js"), DESKTOP_UA)
        val pin = json?.optJSONObject("resource_response")?.optJSONObject("data") ?: return null
        val media = mutableListOf<Media>()
        val thumb = pin.optJSONObject("images")?.optJSONObject("736x")?.optString("url").orEmpty()

        // Idea / story pins: several pages, each a video or image
        pin.optJSONObject("story_pin_data")?.optJSONArray("pages")?.let { pages ->
            for (i in 0 until pages.length()) {
                val blocks = pages.optJSONObject(i)?.optJSONArray("blocks") ?: continue
                for (j in 0 until blocks.length()) {
                    val b = blocks.optJSONObject(j) ?: continue
                    b.optJSONObject("video")?.optJSONObject("video_list")?.let { v -> bestVideo(v)?.let { media += it.copy(thumb = it.thumb.ifBlank { thumb }) } }
                        ?: b.optJSONObject("image")?.optJSONObject("images")?.let { im -> bestImage(im)?.let { media += it } }
                }
            }
        }
        // Carousel pins: several images
        if (media.isEmpty()) pin.optJSONObject("carousel_data")?.optJSONArray("carousel_slots")?.let { slots ->
            for (i in 0 until slots.length()) slots.optJSONObject(i)?.optJSONObject("images")?.let { bestImage(it)?.let { m -> media += m } }
        }
        // Plain video pin
        if (media.isEmpty()) pin.optJSONObject("videos")?.optJSONObject("video_list")?.let { v -> bestVideo(v)?.let { media += it.copy(thumb = it.thumb.ifBlank { thumb }) } }
        // Plain image pin
        if (media.isEmpty()) pin.optJSONObject("images")?.let { bestImage(it)?.let { m -> media += m } }

        val user = pin.optJSONObject("pinner")?.optString("username").orEmpty()
        return Post("pinterest", id, user, pin.optString("title").ifBlank { pin.optString("description") }, media)
    }

    /** Backup: read the media links straight out of the pin page source. */
    private fun scrape(id: String, html: String): Post {
        val media = mutableListOf<Media>()
        val videos = Regex("""https:\\?/\\?/v1\.pinimg\.com\\?/videos\\?/[^"\s]+?\.mp4""").findAll(html).map { it.value.replace("\\/", "/") }.distinct().toList()
        val best = videos.firstOrNull { it.contains("/720p/") } ?: videos.firstOrNull { it.contains("/expMp4/") } ?: videos.firstOrNull()
        val orig = Regex("""https:\\?/\\?/i\.pinimg\.com\\?/originals\\?/[^"\s]+?\.(?:jpg|jpeg|png|gif|webp)""").find(html)?.value?.replace("\\/", "/")
        val og = Net.meta(html, "og:image")
        if (best != null) media += Media(Kind.VIDEO, best, orig ?: og.orEmpty(), "mp4")
        else (orig ?: og?.let { upgradePinImage(it) })?.let { media += Media(Kind.PHOTO, it, it, Net.extFromType("", it)) }
        return Post("pinterest", id, "", "", media)
    }

    private fun bestVideo(list: JSONObject): Media? {
        var best: JSONObject? = null
        for (k in list.keys()) {
            val v = list.optJSONObject(k) ?: continue
            if (!v.optString("url").contains(".mp4")) continue
            if (best == null || v.optInt("width") > best.optInt("width")) best = v
        }
        return best?.let { Media(Kind.VIDEO, it.optString("url"), it.optString("thumbnail"), "mp4", it.optInt("width"), it.optInt("height")) }
    }

    private fun bestImage(images: JSONObject): Media? {
        val o = images.optJSONObject("orig") ?: images.optJSONObject("originals")
            ?: images.keys().asSequence().mapNotNull { images.optJSONObject(it) }.maxByOrNull { it.optInt("width") }
            ?: return null
        val url = upgradePinImage(o.optString("url")).ifBlank { return null }
        return Media(Kind.PHOTO, url, o.optString("url"), Net.extFromType("", url), o.optInt("width"), o.optInt("height"))
    }

    /** i.pinimg.com/736x/ab/cd/x.jpg → i.pinimg.com/originals/ab/cd/x.jpg */
    private fun upgradePinImage(u: String) = u.replace(Regex("""(i\.pinimg\.com/)[^/]+/"""), "$1originals/")
}

// ---------------------------------------------------------------- Facebook
object Facebook {
    fun fetch(link: String): Post {
        val headers = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Sec-Fetch-Mode" to "navigate", "Sec-Fetch-Dest" to "document", "Sec-Fetch-Site" to "none",
        )
        val page = Net.get(link, DESKTOP_UA, headers)
        val html = page.body.orEmpty()
        val id = Regex("""(?:videos?/|reel/|v=|fbid=|story_fbid=|/posts/|/share/[a-z]/)([A-Za-z0-9]+)""").find(page.url)?.groupValues?.get(1)
            ?: (page.url.hashCode().toUInt().toString())
        val thumb = Net.meta(html, "og:image").orEmpty()
        val media = mutableListOf<Media>()

        // Video: prefer HD, then SD
        val keys = listOf("browser_native_hd_url", "playable_url_quality_hd", "hd_src", "browser_native_sd_url", "playable_url", "sd_src")
        for (k in keys) {
            val m = Regex(""""?$k"?\s*:\s*"((?:[^"\\]|\\.)+)"""").find(html) ?: continue
            val url = Net.unescape(m.groupValues[1])
            if (url.startsWith("http")) { media += Media(Kind.VIDEO, url, thumb, "mp4"); break }
        }
        // Photos: the full-size images used by the photo viewer
        if (media.isEmpty()) {
            Regex(""""viewer_image"\s*:\s*\{"height":(\d+),"width":(\d+),"uri":"((?:[^"\\]|\\.)+)"""").findAll(html)
                .map { Triple(Net.unescape(it.groupValues[3]), it.groupValues[2].toInt(), it.groupValues[1].toInt()) }
                .distinctBy { it.first.substringBefore('?') }
                .forEach { (u, w, h) -> media += Media(Kind.PHOTO, u, u, "jpg", w, h) }
        }
        if (media.isEmpty() && thumb.isNotBlank()) media += Media(Kind.PHOTO, thumb, thumb, "jpg")
        if (media.isEmpty()) throw NoMediaException("Facebook didn't show the media. Only public posts work.")
        return Post("facebook", id, "", Net.meta(html, "og:title").orEmpty(), media)
    }
}

// ---------------------------------------------------------------- Google (Images / Lens / Google app shares)
object Google {
    fun fetch(link: String): Post {
        val page = Net.get(link)
        val img = Net.query(page.url, "imgurl")
            ?: Net.query(link, "imgurl")
            ?: page.body?.let { b -> Regex("""imgurl=([^&"\\]+)""").find(b)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") } }
        if (img != null) {
            val ref = Net.query(page.url, "imgrefurl") ?: Net.query(link, "imgrefurl")
            return Generic.direct(img, "google", ref)
        }
        // Not an image result — treat the page it led to like any other site.
        return Generic.fetch(page.url)
    }
}

// ---------------------------------------------------------------- Any other site / direct file links
object Generic {
    fun direct(url: String, source: String, referer: String? = null): Post {
        val h = referer?.let { mapOf("Referer" to it) } ?: emptyMap()
        val type = try { Net.get(url, headers = h, readBody = false).type } catch (e: Exception) { "" }
        val ext = Net.extFromType(type, url)
        val kind = if (type.startsWith("video") || ext in setOf("mp4", "mov", "webm")) Kind.VIDEO else Kind.PHOTO
        return Post(source, url.hashCode().toUInt().toString(), "", "", listOf(Media(kind, url, if (kind == Kind.PHOTO) url else "", ext, headers = h)))
    }

    fun fetch(url: String): Post {
        val page = Net.get(url)
        if (page.type.startsWith("image/") || page.type.startsWith("video/")) return direct(page.url, "web")
        val html = page.body.orEmpty()
        val host = Regex("""https?://(?:www\.)?([^/?#]+)""").find(page.url)?.groupValues?.get(1)?.substringBefore('.').orEmpty().ifBlank { "web" }
        val video = listOf("og:video:secure_url", "og:video:url", "og:video", "twitter:player:stream").firstNotNullOfOrNull { Net.meta(html, it) }
            ?.takeIf { it.contains(".mp4") || it.contains("video") }
        val image = listOf("og:image:secure_url", "og:image", "twitter:image").firstNotNullOfOrNull { Net.meta(html, it) }
        val media = mutableListOf<Media>()
        if (video != null) media += Media(Kind.VIDEO, video, image.orEmpty(), "mp4", headers = mapOf("Referer" to page.url))
        else if (image != null) media += Media(Kind.PHOTO, image, image, Net.extFromType("", image), headers = mapOf("Referer" to page.url))
        if (media.isEmpty()) throw NoMediaException()
        return Post(host, page.url.hashCode().toUInt().toString(), "", Net.meta(html, "og:title").orEmpty(), media)
    }
}

@Suppress("unused") private fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
