package com.midotheeng.brief

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Last resort for any site: open the page in a hidden browser, like a real phone,
 * and catch the video (or better, just its sound track) as the page loads it.
 */
object WebCapture {
    class Found(val url: String, val ext: String, val headers: Map<String, String>, val title: String)

    private class Cand(val url: String, val audioOnly: Boolean, val videoOnly: Boolean, val ext: String)

    private val MEDIA_EXT = Regex("""\.(mp4|m4a|webm|mov|mp3|aac|m4v)(?:$|[?#])""", RegexOption.IGNORE_CASE)

    fun ua(ctx: Context): String = try {
        android.webkit.WebSettings.getDefaultUserAgent(ctx).replace("; wv", "")
    } catch (e: Exception) { com.midotheeng.brief.links.MOBILE_UA }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun capture(ctx: Context, pageUrl: String, timeoutMs: Long = 30000): Found? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val main = Handler(Looper.getMainLooper())
            val cands = java.util.Collections.synchronizedList(mutableListOf<Cand>())
            val seen = java.util.Collections.synchronizedSet(mutableSetOf<String>())
            var htmlVideo: String? = null
            var title = ""
            var done = false
            val wv = WebView(ctx.applicationContext)
            val agent = ua(ctx)

            fun finish() {
                if (done) return
                done = true
                main.removeCallbacksAndMessages(null)
                val pick = synchronized(cands) {
                    cands.firstOrNull { it.audioOnly } ?: htmlVideo?.let { Cand(it, false, false, "mp4") }
                        ?: cands.firstOrNull { !it.videoOnly }
                }
                val cookies = try { CookieManager.getInstance().getCookie(pageUrl) } catch (e: Exception) { null }
                try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {}
                val headers = buildMap {
                    put("User-Agent", agent); put("Referer", pageUrl)
                    if (!cookies.isNullOrBlank()) put("Cookie", cookies)
                }
                if (cont.isActive) cont.resume(pick?.let { Found(it.url, it.ext, headers, title) })
            }

            fun scan() {
                if (done) return
                val js = """(function(){try{var v=document.querySelector('video');if(v){v.muted=true;v.play&&v.play();}
                    |return JSON.stringify({t:document.title||'',src:(v&&v.currentSrc&&v.currentSrc.indexOf('http')==0)?v.currentSrc:'',
                    |html:document.documentElement.outerHTML.slice(0,3000000)});}catch(e){return '{}'}})()""".trimMargin()
                wv.evaluateJavascript(js) { raw ->
                    try {
                        val o = JSONObject(JSONObject("{\"v\":$raw}").getString("v"))
                        title = o.optString("t").ifBlank { title }
                        val src = o.optString("src")
                        val html = o.optString("html")
                        htmlVideo = htmlVideo ?: src.takeIf { it.isNotBlank() } ?: fromHtml(html)
                    } catch (_: Exception) {}
                    if (cands.any { it.audioOnly }) main.postDelayed({ finish() }, 800)
                }
            }

            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                userAgentString = agent
                loadsImagesAutomatically = false
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            wv.layout(0, 0, 1080, 1920)
            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val u = request?.url?.toString() ?: return null
                    if (u.startsWith("http") && MEDIA_EXT.containsMatchIn(u.substringBefore('?') + "?")) {
                        val clean = u.replace(Regex("""&?(bytestart|byteend)=\d+"""), "").replace("?&", "?")
                        val key = clean.substringBefore('?')
                        if (seen.add(key)) {
                            val tag = efgTag(u)
                            val audio = tag.contains("audio") || u.contains("audio", true) && !u.contains("video", true)
                            val videoOnly = !audio && (tag.contains("dash") || u.contains("bytestart"))
                            val ext = if (audio) "m4a" else MEDIA_EXT.find(key + "?")?.groupValues?.get(1)?.lowercase() ?: "mp4"
                            cands += Cand(clean, audio, videoOnly, ext)
                            if (audio) main.post { main.postDelayed({ finish() }, 800) }
                        }
                    }
                    return null
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {}

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (done) return
                    main.postDelayed({ scan() }, 1500)
                    main.postDelayed({ scan() }, 5000)
                    main.postDelayed({ scan(); if (htmlVideo != null || cands.isNotEmpty()) main.postDelayed({ finish() }, 2500) }, 9000)
                }
            }
            main.postDelayed({ finish() }, timeoutMs)
            cont.invokeOnCancellation { main.post { finish() } }
            wv.loadUrl(pageUrl)
        }
    }

    /** Instagram/Facebook put a base64 note on each stream saying what it is (e.g. "…_audio"). */
    private fun efgTag(u: String): String {
        val efg = Regex("""[?&]efg=([^&]+)""").find(u)?.groupValues?.get(1) ?: return ""
        return try {
            val raw = java.net.URLDecoder.decode(efg, "UTF-8")
            String(Base64.decode(raw, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)).lowercase()
        } catch (e: Exception) {
            try { String(Base64.decode(java.net.URLDecoder.decode(efg, "UTF-8"), Base64.DEFAULT)).lowercase() } catch (_: Exception) { "" }
        }
    }

    /** A full (sound + picture) video link written inside the page. */
    private fun fromHtml(html: String): String? {
        if (html.isBlank()) return null
        val flat = html.replace("\\\\/", "/").replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
        Regex(""""video_versions"\s*:\s*\[\s*\{[^\]]*?"url"\s*:\s*"(https[^"]+)"""").find(flat)?.let { return it.groupValues[1] }
        Regex(""""(?:video_url|playable_url_quality_hd|browser_native_hd_url|playable_url|browser_native_sd_url|playAddr|downloadAddr)"\s*:\s*"(https[^"]+)"""").find(flat)?.let { return it.groupValues[1] }
        Regex("""<meta[^>]+property=["']og:video(?::secure_url|:url)?["'][^>]*content=["'](https[^"']+)["']""").find(flat)?.let { return it.groupValues[1] }
        return null
    }
}
