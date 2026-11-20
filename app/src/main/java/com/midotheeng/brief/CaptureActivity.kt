package com.midotheeng.brief

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * Opens the video's page on screen inside Brief. You press play (and sign in if the site asks);
 * as soon as the page loads the video's sound, Brief takes it and carries on in the background.
 */
class CaptureActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val cands = java.util.Collections.synchronizedList(mutableListOf<WebCapture.Cand>())
    private val seen = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var handedOff = false
    private lateinit var web: WebView
    private lateinit var status: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(BriefService.EXTRA_ID) ?: return finish()
        val brief = Store.get(this, id) ?: return finish()
        val pageUrl = brief.link.ifBlank { return finish() }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true; setBackgroundColor(Color.WHITE) }
        val bar = LinearLayout(this).apply { setPadding(28, 18, 20, 18); gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.rgb(246, 244, 239)) }
        status = TextView(this).apply {
            text = "Press ▶ play on the video. Sign in first if it asks."
            textSize = 15f; setTextColor(Color.rgb(20, 24, 33))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val use = Button(this).apply { text = "Use this"; setOnClickListener { handOff(id, pageUrl, manual = true) } }
        bar.addView(status); bar.addView(use)

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = WebCapture.ua(this@CaptureActivity)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val c = request?.url?.toString()?.let { WebCapture.sniff(it) } ?: return null
                    if (seen.add(c.url.substringBefore('?'))) {
                        cands += c
                        main.post {
                            status.text = if (cands.any { it.audioOnly }) "Got the sound ✓" else "Found the video… keep it playing"
                            if (c.audioOnly) main.postDelayed({ handOff(id, pageUrl, manual = false) }, 1200)
                            else if (!c.videoOnly) main.postDelayed({ handOff(id, pageUrl, manual = false) }, 2500)
                        }
                    }
                    return null
                }
                override fun onPageFinished(view: WebView?, url: String?) { CookieManager.getInstance().flush() }
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        root.addView(bar); root.addView(web)
        setContentView(root)
        web.loadUrl(pageUrl)
    }

    private fun handOff(id: String, pageUrl: String, manual: Boolean) {
        if (handedOff) return
        val pick = synchronized(cands) { cands.firstOrNull { it.audioOnly } ?: cands.firstOrNull { !it.videoOnly } }
        if (pick == null) {
            if (manual) Toast.makeText(this, "No video sound yet — press play on the video first", Toast.LENGTH_LONG).show()
            return
        }
        handedOff = true
        CookieManager.getInstance().flush()
        val headers = buildMap {
            put("User-Agent", WebCapture.ua(this@CaptureActivity)); put("Referer", pageUrl)
            CookieManager.getInstance().getCookie(pick.url)?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
        }
        BriefService.fromCaptured(this, id, pick.url, pick.ext, headers)
        Toast.makeText(this, "Got it — Brief is working on it", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        try { web.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }
}
