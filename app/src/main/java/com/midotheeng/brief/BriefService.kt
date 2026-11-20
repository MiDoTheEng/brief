package com.midotheeng.brief

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.OpenableColumns
import com.midotheeng.brief.links.Instagram
import com.midotheeng.brief.links.Kind
import com.midotheeng.brief.links.MOBILE_UA
import com.midotheeng.brief.links.Net
import com.midotheeng.brief.links.Router
import kotlinx.coroutines.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Does the work in the background with a notification, so you can keep using the phone:
 * get the video → get the words (captions or listening) → write the brief.
 */
class BriefService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = AtomicInteger(0)
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground("Starting…")
        val i = intent ?: return START_NOT_STICKY
        val id = i.getStringExtra(EXTRA_ID) ?: return START_NOT_STICKY
        val length = i.getStringExtra(EXTRA_LENGTH) ?: Prefs.length(this)
        val lang = i.getStringExtra(EXTRA_LANG) ?: Prefs.lang(this)
        val text = i.getStringExtra(EXTRA_TEXT)
        val uri = i.data
        val type = i.type
        val mediaUrl = i.getStringExtra(EXTRA_MEDIA_URL)
        val mediaExt = i.getStringExtra(EXTRA_MEDIA_EXT) ?: "mp4"
        val mediaHeaders = i.getStringExtra(EXTRA_MEDIA_HEADERS)?.let { h ->
            try { val o = org.json.JSONObject(h); o.keys().asSequence().associateWith { o.getString(it) } } catch (e: Exception) { emptyMap() }
        } ?: emptyMap()
        jobs.incrementAndGet()
        active += id
        scope.launch {
            try {
                run(id, text, uri, type, length, lang, mediaUrl?.let { Triple(it, mediaExt, mediaHeaders) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val msg = (e as? BriefException)?.message ?: "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
                Store.update(this@BriefService, id) { it.status = if (it.versions.isEmpty()) Brief.FAILED else Brief.DONE; it.error = msg; it.stage = "" }
                notifyDone(id, "Couldn't make the brief", msg)
            } finally {
                active -= id
                if (jobs.decrementAndGet() == 0) withContext(Dispatchers.Main) {
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private suspend fun run(id: String, text: String?, uri: Uri?, type: String?, length: String, lang: String, captured: Triple<String, String, Map<String, String>>? = null) {
        val key = Prefs.groqKey(this)
        if (key.isBlank()) throw BriefException("Add your free Groq key in Brief → Settings first.")
        val work = File(cacheDir, "job_$id").apply { mkdirs() }
        val progress: (String) -> Unit = { s ->
            Store.update(this, id) { it.stage = s }
            goForeground(s)
        }
        try {
            var b = Store.get(this, id) ?: throw BriefException("Brief was deleted.")
            b.status = Brief.WORKING; b.error = ""; b.length = length; b.lang = lang
            Store.save(this, b)

            // 1) Words: reuse the saved transcript if we already listened once.
            if (b.transcript.isBlank()) {
                val transcript = when {
                    captured != null -> {
                        val f = File(work, "captured.${captured.second}")
                        download(captured.first, captured.third, f, progress)
                        setDuration(id, f)
                        Summarizer.listen(key, Audio.split(f, File(work, "pieces")), progress)
                    }
                    uri != null -> fromFile(id, uri, type, work, key, progress)
                    text != null -> fromLink(id, text, work, key, progress)
                    b.link.isNotBlank() -> fromLink(id, b.link, work, key, progress)
                    else -> throw BriefException("The original video is gone; share it again.")
                }
                Store.update(this, id) { it.transcript = transcript }
            }

            // 2) Brief
            b = Store.get(this, id) ?: return
            val model = Groq.pickChatModel(key, Prefs.chatModel(this))
            val md = Summarizer.write(key, model, b.title, b.transcript, length, lang, progress)
            Store.update(this, id) {
                it.versions[Brief.key(length, lang)] = md
                it.nameFrom(md)
                it.status = Brief.DONE; it.stage = ""; it.error = ""
            }
            notifyDone(id, "Brief ready", Store.get(this, id)?.title ?: "")
        } finally {
            work.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ inputs
    private suspend fun fromLink(id: String, text: String, work: File, key: String, progress: (String) -> Unit): String {
        val url = Net.firstUrl(text) ?: throw BriefException("No link found in what was shared.")
        Store.update(this, id) { if (it.link.isBlank()) it.link = url }

        if (YouTube.matches(url)) {
            Store.update(this, id) { it.source = "YouTube" }
            val r = YouTube.load(url, work, progress)
            Store.update(this, id) { it.title = r.title; it.durationSec = r.durationSec }
            r.transcript?.let { return it }
            return Summarizer.listen(key, Audio.split(r.audio!!, File(work, "pieces")), progress)
        }

        progress("Finding the video…")
        val host = Regex("""https?://(?:www\.|m\.)?([^/?#]+)""").find(url)?.groupValues?.get(1)?.lowercase().orEmpty()
        var failure: String? = null
        val post = try { Router.resolve(text) } catch (e: Exception) { failure = e.message; null }
        val media = post?.media?.firstOrNull { it.kind == Kind.VIDEO || it.kind == Kind.GIF }
        if (media == null) {
            // Fallback for any site: open it in a hidden browser and catch the video as it loads.
            progress("Opening the page like a browser…")
            val igCode = Regex("""instagr(?:am\.com|\.am)/(?:[^/?#]+/)?(?:p|reels?|tv)/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
            val pages = listOfNotNull(igCode?.let { "https://www.instagram.com/p/$it/embed/captioned/" }, url)
            var found: WebCapture.Found? = null
            val seen = mutableListOf<String>()
            for (pg in pages) {
                found = WebCapture.capture(this, pg)
                seen += WebCapture.lastDiag
                if (found != null) break
            }
            if (found == null) {
                val ig = host.contains("instagram")
                throw BriefException(
                    "Couldn't get the video from this link" + (failure?.let { " ($it)" } ?: "") + "; " + seen.joinToString("; ") + ". " +
                        (if (ig && Instagram.sessionCookies() == null) "Instagram often hides videos from logged-out visitors: connect your account in Brief → Settings, then tap Retry. "
                        else "") +
                        "Or save the video to your phone and share the file to Brief."
                )
            }
            Store.update(this, id) {
                if (it.source.isBlank()) it.source = host.substringBefore('.').replaceFirstChar { c -> c.uppercase() }
                if (it.title.isBlank()) it.title = found.title.take(90)
            }
            val file = File(work, "video.${found.ext}")
            download(found.url, found.headers, file, progress)
            setDuration(id, file)
            return Summarizer.listen(key, Audio.split(file, File(work, "pieces")), progress)
        }
        post!!
        Store.update(this, id) {
            it.source = post.source.replaceFirstChar { c -> c.uppercase() }
            if (it.title.isBlank()) it.title = post.text.lineSequence().firstOrNull().orEmpty().take(90)
                .ifBlank { if (post.user.isNotBlank()) "Video by ${post.user}" else "" }
        }
        val file = File(work, "video.${media.ext.ifBlank { "mp4" }}")
        download(media.url, media.headers, file, progress)
        setDuration(id, file)
        return Summarizer.listen(key, Audio.split(file, File(work, "pieces")), progress)
    }

    private suspend fun fromFile(id: String, uri: Uri, type: String?, work: File, key: String, progress: (String) -> Unit): String {
        progress("Reading the video…")
        var name = "video"
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { name = it }
            }
        } catch (_: Exception) {}
        val ext = name.substringAfterLast('.', "").lowercase().ifBlank {
            when { type?.startsWith("audio/") == true -> "m4a"; type?.contains("webm") == true -> "webm"; else -> "mp4" }
        }
        val file = File(work, "input.$ext")
        val input = contentResolver.openInputStream(uri) ?: throw BriefException("Couldn't open the shared file.")
        input.use { ins -> file.outputStream().use { ins.copyTo(it, 256 * 1024) } }
        Store.update(this, id) { it.source = "file"; if (it.title.isBlank()) it.title = name.substringBeforeLast('.') }
        setDuration(id, file)
        return Summarizer.listen(key, Audio.split(file, File(work, "pieces")), progress)
    }

    private fun setDuration(id: String, f: File) {
        try {
            val r = MediaMetadataRetriever()
            r.setDataSource(f.path)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            r.release()
            if (ms > 0) Store.update(this, id) { it.durationSec = ms / 1000 }
        } catch (_: Exception) {}
    }

    private fun download(url: String, headers: Map<String, String>, out: File, progress: (String) -> Unit) {
        var u = url
        repeat(8) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000; c.readTimeout = 60000
            c.setRequestProperty("User-Agent", MOBILE_UA)
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            try {
                val code = c.responseCode
                if (code in 300..399) { u = URL(URL(u), c.getHeaderField("Location")).toString(); return@repeat }
                if (code !in 200..299) throw BriefException("The video download failed (HTTP $code).")
                val total = c.contentLengthLong
                if (total > MAX_VIDEO) throw BriefException("That video is too big (over 2 GB).")
                var got = 0L
                var lastPct = -1L
                c.inputStream.use { ins ->
                    out.outputStream().use { os ->
                        val b = ByteArray(128 * 1024)
                        while (true) {
                            val n = ins.read(b); if (n < 0) break
                            os.write(b, 0, n); got += n
                            if (got > MAX_VIDEO) throw BriefException("That video is too big (over 2 GB).")
                            if (total > 0) {
                                val pct = got * 100 / total
                                if (pct / 5 != lastPct / 5) { lastPct = pct; progress("Downloading video… $pct%") }
                            }
                        }
                    }
                }
                return
            } finally { c.disconnect() }
        }
        throw BriefException("Too many redirects while downloading.")
    }

    // ------------------------------------------------------------------ notifications
    private fun channel(id: String, name: String, importance: Int) {
        if (nm.getNotificationChannel(id) == null) nm.createNotificationChannel(NotificationChannel(id, name, importance))
    }

    private fun openApp(id: String?): PendingIntent {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (id != null) i.putExtra(EXTRA_ID, id)
        return PendingIntent.getActivity(this, id?.hashCode() ?: 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun goForeground(text: String) {
        channel(CH_WORK, "Working", NotificationManager.IMPORTANCE_LOW)
        val n = Notification.Builder(this, CH_WORK)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Making a brief")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(null))
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, n)
    }

    private fun notifyDone(id: String, title: String, text: String) {
        channel(CH_DONE, "Finished briefs", NotificationManager.IMPORTANCE_DEFAULT)
        val n = Notification.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(id))
            .build()
        try { nm.notify(id.hashCode(), n) } catch (_: SecurityException) {}
    }

    companion object {
        /** Briefs being worked on right now (so the app doesn't mark them as stuck). */
        val active: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
        const val EXTRA_ID = "id"
        const val EXTRA_TEXT = "text"
        const val EXTRA_LENGTH = "length"
        const val EXTRA_LANG = "lang"
        const val EXTRA_MEDIA_URL = "media_url"
        const val EXTRA_MEDIA_EXT = "media_ext"
        const val EXTRA_MEDIA_HEADERS = "media_headers"
        private const val CH_WORK = "work"
        private const val CH_DONE = "done"
        private const val MAX_VIDEO = 2L * 1024 * 1024 * 1024

        private fun start(ctx: Context, i: Intent) {
            ctx.startForegroundService(i)
        }

        /** New brief from a shared link (any text containing a link). */
        fun fromLink(ctx: Context, text: String, length: String, lang: String): String {
            val id = UUID.randomUUID().toString().take(8)
            val url = Net.firstUrl(text).orEmpty()
            Store.save(ctx, Brief(id, link = url, source = if (YouTube.matches(url)) "YouTube" else "", length = length, lang = lang, stage = "Starting…"))
            start(ctx, Intent(ctx, BriefService::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_LENGTH, length).putExtra(EXTRA_LANG, lang))
            return id
        }

        /** New brief from a shared/picked video or audio file. */
        fun fromFile(ctx: Context, uri: Uri, type: String?, length: String, lang: String): String {
            val id = UUID.randomUUID().toString().take(8)
            Store.save(ctx, Brief(id, source = "file", length = length, lang = lang, stage = "Starting…"))
            start(ctx, Intent(ctx, BriefService::class.java).putExtra(EXTRA_ID, id)
                .setDataAndType(uri, type ?: "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(EXTRA_LENGTH, length).putExtra(EXTRA_LANG, lang))
            return id
        }

        /** Continue a brief with a video/sound link caught in Brief's on-screen browser. */
        fun fromCaptured(ctx: Context, id: String, url: String, ext: String, headers: Map<String, String>) {
            val b = Store.get(ctx, id) ?: return
            Store.update(ctx, id) { it.status = Brief.WORKING; it.error = ""; it.stage = "Starting…"; it.transcript = "" }
            start(ctx, Intent(ctx, BriefService::class.java).putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_LENGTH, b.length).putExtra(EXTRA_LANG, b.lang)
                .putExtra(EXTRA_MEDIA_URL, url).putExtra(EXTRA_MEDIA_EXT, ext)
                .putExtra(EXTRA_MEDIA_HEADERS, org.json.JSONObject(headers as Map<*, *>).toString()))
        }

        /** Another version (Short/Detailed, English/Arabic) or a retry of an existing brief. */
        fun redo(ctx: Context, id: String, length: String, lang: String) {
            Store.update(ctx, id) { it.status = Brief.WORKING; it.error = ""; it.stage = "Starting…"; it.length = length; it.lang = lang }
            start(ctx, Intent(ctx, BriefService::class.java).putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_LENGTH, length).putExtra(EXTRA_LANG, lang))
        }
    }
}
