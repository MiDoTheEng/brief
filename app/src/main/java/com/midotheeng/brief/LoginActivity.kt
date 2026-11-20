package com.midotheeng.brief

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.midotheeng.brief.links.Instagram

/** Optional: sign in to Instagram inside Brief so it can read reels that hide from logged-out visitors. */
class LoginActivity : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        val bar = LinearLayout(this).apply { setPadding(24, 16, 24, 16) }
        val label = TextView(this).apply {
            text = "Sign in to Instagram. Brief only uses this to read videos you share to it."
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val done = Button(this).apply { text = "Done"; setOnClickListener { finishWithResult() } }
        bar.addView(label); bar.addView(done)
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = WebCapture.ua(this@LoginActivity)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    CookieManager.getInstance().flush()
                    if (Instagram.sessionCookies() != null && url?.contains("/accounts/") == false) finishWithResult()
                }
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        root.addView(bar); root.addView(web)
        setContentView(root)
        web.loadUrl("https://www.instagram.com/accounts/login/")
    }

    private fun finishWithResult() {
        CookieManager.getInstance().flush()
        Toast.makeText(this, if (Instagram.sessionCookies() != null) "Instagram connected" else "Not signed in", Toast.LENGTH_SHORT).show()
        finish()
    }
}
