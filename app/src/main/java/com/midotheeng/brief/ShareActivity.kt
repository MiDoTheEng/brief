package com.midotheeng.brief

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midotheeng.brief.links.Net

/**
 * "Share → Brief" from any app. A small sheet asks Short/Detailed and English/Arabic,
 * then the work carries on in the background and this closes.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        val stream: Uri? = if (Build.VERSION.SDK_INT >= 33) i?.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") i?.getParcelableExtra(Intent.EXTRA_STREAM)
        val text = i?.getStringExtra(Intent.EXTRA_TEXT) ?: i?.dataString
        val link = text?.let { Net.firstUrl(it) }
        val type = i?.type

        if (stream == null && link == null) {
            Toast.makeText(this, "Brief: no video or link found in what was shared", Toast.LENGTH_LONG).show()
            finish(); return
        }
        val label = link ?: "Shared video file"

        setContent {
            MaterialTheme {
                Sheet(label, hasKey = Prefs.groqKey(this).isNotBlank(), onCancel = { finish() }) { length, lang ->
                    Prefs.setLength(this, length); Prefs.setLang(this, lang)
                    try {
                        if (link != null) BriefService.fromLink(this, text!!, length, lang)
                        else BriefService.fromFile(this, stream!!, type, length, lang)
                        Toast.makeText(this, "Brief: working on it — you'll get a notification", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "Brief: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    finish()
                }
            }
        }
    }
}

@Composable
private fun Sheet(label: String, hasKey: Boolean, onCancel: () -> Unit, onGo: (String, String) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var length by remember { mutableStateOf(Prefs.length(ctx)) }
    var lang by remember { mutableStateOf(Prefs.lang(ctx)) }
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000))
            .clickable(MutableInteractionSource(), null) { onCancel() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .background(Paper, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .clickable(MutableInteractionSource(), null) { }
                .navigationBarsPadding()
                .padding(22.dp)
        ) {
            Text("Brief this video", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(4.dp))
            Text(label, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(18.dp))
            if (!hasKey) {
                Text(
                    "First add your free Groq key: open the Brief app → Settings. It takes 2 minutes.",
                    color = Accent, fontSize = 15.sp,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra("settings", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    onCancel()
                }, colors = ButtonDefaults.buttonColors(containerColor = Ink), modifier = Modifier.fillMaxWidth()) { Text("Open settings") }
                return@Column
            }
            Text("Length", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Toggle(LENGTHS, length, { length = it }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            Text("Language", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Toggle(LANGS, lang, { lang = it }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(22.dp))
            Button(
                onClick = { onGo(length, lang) },
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Make brief", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}
