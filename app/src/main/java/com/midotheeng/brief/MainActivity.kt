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
import androidx.compose.foundation.combinedClickable
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
        Store.renameOld(this)
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
    val selected = remember { mutableStateListOf<String>() }
    var selecting by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(briefs) { selected.retainAll(briefs.map { it.id }.toSet()) }
    BackHandler(enabled = selecting) { selecting = false; selected.clear() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            Prefs.setLength(ctx, length); Prefs.setLang(ctx, lang)
            open(BriefService.fromFile(ctx, uri, ctx.contentResolver.getType(uri), length, lang))
        }
    }

    Column(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
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
            if (briefs.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Your briefs", fontWeight = FontWeight.Bold, color = Ink, fontSize = 18.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { selecting = !selecting; selected.clear() }) {
                    Text(if (selecting) "Cancel" else "Select", color = Accent, fontWeight = FontWeight.SemiBold)
                }
            }
            else Text(
                "No briefs yet.\n\nIn YouTube, X, Instagram, your gallery or any app: tap Share → Brief.",
                color = Muted, fontSize = 15.sp, lineHeight = 22.sp,
            )
            Spacer(Modifier.height(8.dp))
        }
        items(briefs, key = { it.id }) { b ->
            BriefRow(
                b, selecting = selecting, checked = b.id in selected,
                onClick = {
                    if (selecting) { if (b.id in selected) selected.remove(b.id) else selected.add(b.id) }
                    else open(b.id)
                },
                onLongClick = { selecting = true; if (b.id !in selected) selected.add(b.id) },
            )
        }
    }

    if (selecting) Row(
        Modifier.fillMaxWidth().background(Card).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${selected.size} selected", color = Ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
        Spacer(Modifier.weight(1f))
        TextButton(onClick = {
            if (selected.size == briefs.size) selected.clear() else { selected.clear(); selected.addAll(briefs.map { it.id }) }
        }) { Text(if (selected.size == briefs.size && briefs.isNotEmpty()) "None" else "All", color = Ink) }
        TextButton(enabled = selected.isNotEmpty(), onClick = {
            val chosen = briefs.filter { it.id in selected }
            scope.launch {
                val n = withContext(Dispatchers.IO) { savePdfs(ctx, chosen) }
                Toast.makeText(ctx,
                    if (n == 0) "Nothing to save yet (those briefs aren't finished)"
                    else "Saved $n PDF${if (n > 1) "s" else ""} to Downloads/Brief", Toast.LENGTH_LONG).show()
                selecting = false; selected.clear()
            }
        }) { Text("Save PDF", color = if (selected.isNotEmpty()) Ink else Muted) }
        TextButton(enabled = selected.isNotEmpty(), onClick = { confirmDelete = true }) {
            Text("Delete", color = if (selected.isNotEmpty()) Color(0xFFB3261E) else Muted)
        }
    }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${selected.size} brief${if (selected.size > 1) "s" else ""}?") },
        text = { Text("PDFs you already saved stay in Downloads.") },
        confirmButton = {
            TextButton(onClick = {
                selected.toList().forEach { Store.delete(ctx, it) }
                selected.clear(); selecting = false; confirmDelete = false
            }) { Text("Delete", color = Color(0xFFB3261E)) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

/** Saves each finished brief (the version last shown) as a PDF. Returns how many were saved. */
private fun savePdfs(ctx: android.content.Context, list: List<Brief>): Int {
    var n = 0
    list.forEach { b ->
        val md = b.current ?: b.versions.values.firstOrNull() ?: return@forEach
        try { Pdf.export(ctx, b, md); n++ } catch (_: Exception) {}
    }
    return n
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun BriefRow(b: Brief, selecting: Boolean, checked: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp)
            .background(if (checked) AccentSoft else Card, RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
    if (selecting) Checkbox(checked = checked, onCheckedChange = { onClick() },
        colors = CheckboxDefaults.colors(checkedColor = Accent), modifier = Modifier.padding(end = 6.dp))
    Column(Modifier.weight(1f)) {
        Text(
            (if (b.aiTitle || b.source == "YouTube") b.title else "").ifBlank {
                if (b.status == Brief.WORKING) "New brief…" else b.title.ifBlank { "Video" }
            }, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
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
                    Text("Couldn't make this brief", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Spacer(Modifier.height(10.dp))
                    Text(b.error, color = Color(0xFFB3261E), fontSize = 15.sp)
                    Spacer(Modifier.height(16.dp))
                    if (b.link.isNotBlank() || b.transcript.isNotBlank()) Row {
                        Button(
                            onClick = { BriefService.redo(ctx, b.id, length, lang) },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        ) { Text("Retry") }
                        if (b.link.isNotBlank() && b.transcript.isBlank()) {
                            Spacer(Modifier.width(10.dp))
                            Button(
                                onClick = {
                                    Store.update(ctx, b.id) { it.length = length; it.lang = lang }
                                    ctx.startActivity(Intent(ctx, CaptureActivity::class.java).putExtra(BriefService.EXTRA_ID, b.id))
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Ink),
                            ) { Text("Play it in Brief") }
                        }
                    }
                    else Text("Share the video file to Brief again to retry.", color = Muted)
                    if (b.link.isNotBlank() && b.transcript.isBlank()) Text(
                        "\"Play it in Brief\" opens the video here. Press play (sign in if asked) and Brief takes the sound from there.",
                        color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
                    )
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
                        withContext(Dispatchers.IO) { Pdf.export(ctx, b.copy(length = length, lang = lang), m) }
                        Toast.makeText(ctx, "PDF saved to Downloads/Brief", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "PDF failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }) { Text("Save PDF", color = if (md != null) Ink else Muted) }
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
            var igOn by remember { mutableStateOf(com.midotheeng.brief.links.Instagram.sessionCookies() != null) }
            val life = androidx.lifecycle.compose.LocalLifecycleOwner.current
            DisposableEffect(life) {
                val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                    if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) igOn = com.midotheeng.brief.links.Instagram.sessionCookies() != null
                }
                life.lifecycle.addObserver(obs)
                onDispose { life.lifecycle.removeObserver(obs) }
            }
            Text("Instagram (optional)", fontWeight = FontWeight.Bold, color = Ink, fontSize = 17.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                if (igOn) "✓ Connected. Instagram reels will work more reliably."
                else "Instagram hides many reels from logged-out apps. Sign in once inside Brief so it can read the reels you share.",
                color = Muted, fontSize = 14.sp, lineHeight = 20.sp,
            )
            Spacer(Modifier.height(10.dp))
            if (igOn) OutlinedButton(onClick = {
                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                android.webkit.CookieManager.getInstance().flush()
                igOn = false
            }) { Text("Disconnect", color = Ink) }
            else OutlinedButton(onClick = { ctx.startActivity(Intent(ctx, LoginActivity::class.java)) }) { Text("Connect Instagram", color = Ink) }
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
