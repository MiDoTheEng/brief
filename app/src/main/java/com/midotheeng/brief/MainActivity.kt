package com.midotheeng.brief

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midotheeng.brief.links.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class Screen {
    object Home : Screen()
    object Settings : Screen()
    class Detail(val id: String) : Screen()
}

class MainActivity : ComponentActivity() {
    private val screen = mutableStateOf<Screen>(Screen.Home)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        Store.markStale(this, BriefService.active)
        route(intent)
        if (Prefs.groqKey(this).isBlank() && screen.value == Screen.Home) screen.value = Screen.Settings
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Ink, secondary = Accent, background = Paper, surface = Paper)) {
                App(screen)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        route(intent)
    }

    private fun route(i: Intent?) {
        i?.getStringExtra(BriefService.EXTRA_ID)?.let { screen.value = Screen.Detail(it) }
        if (i?.getBooleanExtra("settings", false) == true) screen.value = Screen.Settings
    }
}

@Composable
private fun App(screen: MutableState<Screen>) {
    val s = screen.value
    BackHandler(enabled = s != Screen.Home) { screen.value = Screen.Home }
    Box(Modifier.fillMaxSize().background(Paper).systemBarsPadding()) {
        when (s) {
            is Screen.Home -> Home(open = { screen.value = Screen.Detail(it) }, settings = { screen.value = Screen.Settings })
            is Screen.Settings -> Settings(back = { screen.value = Screen.Home })
            is Screen.Detail -> Detail(s.id, back = { screen.value = Screen.Home })
        }
    }
}

// ====================================================================== Home
@Composable
private fun Home(open: (String) -> Unit, settings: () -> Unit) {
    val ctx = LocalContext.current
    val tick by Store.changes.collectAsState()
    val briefs = remember(tick) { Store.all(ctx) }
    var link by remember { mutableStateOf("") }
    var length by remember { mutableStateOf(Prefs.length(ctx)) }
    var lang by remember { mutableStateOf(Prefs.lang(ctx)) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            Prefs.setLength(ctx, length); Prefs.setLang(ctx, lang)
            open(BriefService.fromFile(ctx, uri, ctx.contentResolver.getType(uri), length, lang))
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Brief", fontSize = 34.sp, fontWeight = FontWeight.Black, color = Ink)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = settings) { Text("Settings", color = Ink) }
            }
            Text("Share any video to Brief, or paste a link here.", color = Muted, fontSize = 15.sp)
            Spacer(Modifier.height(18.dp))

            Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(20.dp)).padding(16.dp)) {
                OutlinedTextField(
                    value = link, onValueChange = { link = it },
                    placeholder = { Text("Video link (YouTube, X, Instagram…)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(10.dp))
                Toggle(LENGTHS, length, { length = it }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Toggle(LANGS, lang, { lang = it }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Row {
                    OutlinedButton(onClick = {
                        val cm = ctx.getSystemService(ClipboardManager::class.java)
                        link = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                    }) { Text("Paste", color = Ink) }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { pick.launch(arrayOf("video/*", "audio/*")) }) { Text("Pick file", color = Ink) }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            if (Prefs.groqKey(ctx).isBlank()) { settings(); return@Button }
                            Prefs.setLength(ctx, length); Prefs.setLang(ctx, lang)
                            val id = BriefService.fromLink(ctx, link, length, lang)
                            link = ""
                            open(id)
                        },
                        enabled = Net.firstUrl(link) != null,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    ) { Text("Brief it") }
                }
            }
            Spacer(Modifier.height(22.dp))
            if (briefs.isNotEmpty()) Text("Your briefs", fontWeight = FontWeight.Bold, color = Ink, fontSize = 18.sp)
            else Text(
                "No briefs yet.\n\nIn YouTube, X, Instagram, your gallery or any app: tap Share → Brief.",
                color = Muted, fontSize = 15.sp, lineHeight = 22.sp,
            )
            Spacer(Modifier.height(8.dp))
        }
        items(briefs, key = { it.id }) { b -> BriefRow(b) { open(b.id) } }
    }
}

@Composable
private fun BriefRow(b: Brief, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 5.dp)
            .background(Card, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(14.dp)
    ) {
        Text(
            b.title.ifBlank { b.link.ifBlank { "Video" } }, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, style = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
        )
        Spacer(Modifier.height(4.dp))
        val status = when (b.status) {
            Brief.WORKING -> b.stage.ifBlank { "Working…" }
            Brief.FAILED -> "Failed — tap to see why"
            else -> listOfNotNull(
                b.source.takeIf { it.isNotBlank() },
                b.durationSec.takeIf { it > 0 }?.let { Transcript.stamp(it.toDouble()) },
                DateUtils.getRelativeTimeSpanString(b.created).toString(),
            ).joinToString(" · ")
        }
        Text(status, color = when (b.status) { Brief.WORKING -> Accent; Brief.FAILED -> Color(0xFFB3261E); else -> Muted }, fontSize = 13.sp)
    }
}

// ====================================================================== Detail
@Composable
private fun Detail(id: String, back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tick by Store.changes.collectAsState()
    val b = remember(tick, id) { Store.get(ctx, id) }
    if (b == null) { LaunchedEffect(Unit) { back() }; return }
    var length by remember(id) { mutableStateOf(b.length) }
    var lang by remember(id) { mutableStateOf(b.lang) }
    var showTranscript by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val md = b.versions[Brief.key(length, lang)]
    val working = b.status == Brief.WORKING

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = back) { Text("‹ Back", color = Ink, fontSize = 16.sp) }
            Spacer(Modifier.weight(1f))
            if (b.link.isNotBlank()) TextButton(onClick = {
                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(b.link))) } catch (_: Exception) {}
            }) { Text("Open video", color = Ink) }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Toggle(LENGTHS, length, { length = it }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Toggle(LANGS, lang, { lang = it }, Modifier.fillMaxWidth())
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
            when {
                md != null -> {
                    val meta = listOfNotNull(
                        b.source.takeIf { it.isNotBlank() },
                        b.durationSec.takeIf { it > 0 }?.let { Transcript.stamp(it.toDouble()) },
                    ).joinToString(" · ")
                    if (meta.isNotBlank()) Text(meta, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
                    SelectionContainer { MarkdownView(md, b.link) }
                    if (working) { Spacer(Modifier.height(12.dp)); Text(b.stage, color = Accent) }
                }
                working -> {
                    Spacer(Modifier.height(30.dp))
                    CircularProgressIndicator(color = Accent)
                    Spacer(Modifier.height(16.dp))
                    Text(b.stage.ifBlank { "Working…" }, color = Ink, fontSize = 16.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("You can leave the app; you'll get a notification when it's ready.", color = Muted, fontSize = 14.sp)
                }
                b.status == Brief.FAILED && b.versions.isEmpty() -> {
                    Text(b.title.ifBlank { "Couldn't make this brief" }, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Spacer(Modifier.height(10.dp))
                    Text(b.error, color = Color(0xFFB3261E), fontSize = 15.sp)
                    Spacer(Modifier.height(16.dp))
                    if (b.link.isNotBlank() || b.transcript.isNotBlank()) Button(
                        onClick = { BriefService.redo(ctx, b.id, length, lang) },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    ) { Text("Retry") }
                    else Text("Share the video file to Brief again to retry.", color = Muted)
                }
                else -> {
                    Text("This version isn't made yet.", color = Ink, fontSize = 16.sp)
                    if (b.error.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(b.error, color = Color(0xFFB3261E), fontSize = 14.sp) }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { BriefService.redo(ctx, b.id, length, lang) },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    ) { Text("Make ${LENGTHS.first { it.first == length }.second.lowercase()} · ${LANGS.first { it.first == lang }.second}") }
                    Text("Uses the saved transcript, so it's quick.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }

            if (b.transcript.isNotBlank()) {
                Spacer(Modifier.height(24.dp))
                TextButton(onClick = { showTranscript = !showTranscript }) {
                    Text(if (showTranscript) "Hide transcript" else "Show transcript", color = Muted)
                }
                if (showTranscript) SelectionContainer {
                    Text(b.transcript, color = Muted, fontSize = 13.sp, lineHeight = 19.sp,
                        style = LocalTextStyle.current.copy(textDirection = TextDirection.Content))
                }
            }
        }

        // Actions
        Row(
            Modifier.fillMaxWidth().background(Card).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TextButton(enabled = md != null, onClick = {
                val m = md ?: return@TextButton
                scope.launch {
                    try {
                        val uri = withContext(Dispatchers.IO) { Pdf.export(ctx, b.copy(length = length, lang = lang), m) }
                        Toast.makeText(ctx, "Saved to Downloads/Brief", Toast.LENGTH_SHORT).show()
                        ctx.startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share PDF"))
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "PDF failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }) { Text("PDF", color = if (md != null) Ink else Muted) }
            TextButton(enabled = md != null, onClick = {
                val t = Md.plain(md ?: return@TextButton) + if (b.link.isNotBlank()) "\n\n${b.link}" else ""
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), "Share brief"))
            }) { Text("Share", color = if (md != null) Ink else Muted) }
            TextButton(enabled = md != null, onClick = {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Brief", Md.plain(md ?: return@TextButton)))
                Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
            }) { Text("Copy", color = if (md != null) Ink else Muted) }
            TextButton(enabled = !working && md != null, onClick = { BriefService.redo(ctx, b.id, length, lang) }) {
                Text("Redo", color = if (!working && md != null) Ink else Muted)
            }
            TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = Color(0xFFB3261E)) }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this brief?") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; Store.delete(ctx, id); back() }) { Text("Delete", color = Color(0xFFB3261E)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

// ====================================================================== Settings
@Composable
private fun Settings(back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(Prefs.groqKey(ctx)) }
    var status by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        TextButton(onClick = back, contentPadding = PaddingValues(0.dp)) { Text("‹ Back", color = Ink, fontSize = 16.sp) }
        Text("Settings", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Ink)
        Spacer(Modifier.height(18.dp))

        Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(20.dp)).padding(16.dp)) {
            Text("Groq key (free)", fontWeight = FontWeight.Bold, color = Ink, fontSize = 17.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Brief uses Groq's free AI to listen to videos and write the briefs. No credit card.\n\n" +
                    "1. Open console.groq.com/keys and sign in with Google\n" +
                    "2. Tap \"Create API Key\", give it any name\n" +
                    "3. Copy the key (starts with gsk_) and paste it below",
                color = Muted, fontSize = 14.sp, lineHeight = 20.sp,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys"))) } catch (_: Exception) {}
            }) { Text("Open console.groq.com", color = Ink) }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = key, onValueChange = { key = it.trim(); status = "" },
                label = { Text("gsk_…") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    val cm = ctx.getSystemService(ClipboardManager::class.java)
                    key = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim().orEmpty(); status = ""
                }) { Text("Paste", color = Ink) }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = key.isNotBlank() && !checking,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    onClick = {
                        checking = true; status = "Checking…"
                        scope.launch {
                            status = try {
                                val models = withContext(Dispatchers.IO) { Groq.models(key) }
                                Prefs.setGroqKey(ctx, key)
                                Prefs.setChatModel(ctx, "")
                                if (models.any { it.contains("whisper") }) "✓ Saved. You're ready — share a video to Brief."
                                else "✓ Saved, but this key can't use Whisper (speech-to-text)."
                            } catch (e: Exception) { "✗ ${e.message}" }
                            checking = false
                        }
                    },
                ) { Text("Save & check") }
            }
            if (status.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(status, color = if (status.startsWith("✗")) Color(0xFFB3261E) else Ink, fontSize = 14.sp)
            }
        }

        Spacer(Modifier.height(18.dp))
        Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(20.dp)).padding(16.dp)) {
            Text("Good to know", fontWeight = FontWeight.Bold, color = Ink, fontSize = 17.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "• YouTube videos with captions are briefed instantly from the captions.\n" +
                    "• Other videos are listened to, so Brief understands what's said, not what's only shown on screen.\n" +
                    "• Groq's free tier has daily limits. If you hit one, the brief waits and you can tap Retry later.\n" +
                    "• Switching Short/Detailed or English/Arabic on a brief reuses the transcript, so it's quick.\n" +
                    "• PDFs are saved to Downloads/Brief.",
                color = Muted, fontSize = 14.sp, lineHeight = 21.sp,
            )
        }
    }
}
