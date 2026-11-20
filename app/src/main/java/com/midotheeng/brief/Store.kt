package com.midotheeng.brief

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File

/** One summarized video. `versions` holds every Short/Detailed × English/Arabic brief made so far. */
data class Brief(
    val id: String,
    var title: String = "",
    var source: String = "",          // youtube / x / instagram / file …
    var link: String = "",            // original link (empty for files)
    val created: Long = System.currentTimeMillis(),
    var durationSec: Long = 0,
    var length: String = Prefs.SHORT, // the version shown
    var lang: String = Prefs.EN,
    var status: String = WORKING,     // working / done / failed
    var stage: String = "",           // what it's doing right now
    var error: String = "",
    var transcript: String = "",
    val versions: MutableMap<String, String> = mutableMapOf(),
) {
    val current: String? get() = versions[key(length, lang)]

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("source", source); put("link", link)
        put("created", created); put("durationSec", durationSec); put("length", length); put("lang", lang)
        put("status", status); put("stage", stage); put("error", error); put("transcript", transcript)
        put("versions", JSONObject(versions as Map<*, *>))
    }

    companion object {
        const val WORKING = "working"
        const val DONE = "done"
        const val FAILED = "failed"

        fun key(length: String, lang: String) = "$length-$lang"

        fun fromJson(o: JSONObject): Brief {
            val v = mutableMapOf<String, String>()
            o.optJSONObject("versions")?.let { j -> j.keys().forEach { k -> v[k] = j.optString(k) } }
            return Brief(
                id = o.getString("id"), title = o.optString("title"), source = o.optString("source"),
                link = o.optString("link"), created = o.optLong("created"), durationSec = o.optLong("durationSec"),
                length = o.optString("length", Prefs.SHORT), lang = o.optString("lang", Prefs.EN),
                status = o.optString("status", DONE), stage = o.optString("stage"), error = o.optString("error"),
                transcript = o.optString("transcript"), versions = v,
            )
        }
    }
}

/** Briefs are small JSON files inside the app. */
object Store {
    /** Bumped on every change so the screens refresh. */
    val changes = MutableStateFlow(0)
    private val lock = Any()

    private fun dir(ctx: Context) = File(ctx.filesDir, "briefs").apply { mkdirs() }

    fun save(ctx: Context, b: Brief) {
        synchronized(lock) {
            val f = File(dir(ctx), "${b.id}.json")
            val tmp = File(dir(ctx), "${b.id}.tmp")
            tmp.writeText(b.toJson().toString())
            tmp.renameTo(f)
        }
        changes.value++
    }

    fun get(ctx: Context, id: String): Brief? = synchronized(lock) {
        val f = File(dir(ctx), "$id.json")
        if (!f.exists()) null else try { Brief.fromJson(JSONObject(f.readText())) } catch (e: Exception) { null }
    }

    fun all(ctx: Context): List<Brief> = synchronized(lock) {
        dir(ctx).listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { try { Brief.fromJson(JSONObject(it.readText())) } catch (e: Exception) { null } }
            .sortedByDescending { it.created }
    }

    fun delete(ctx: Context, id: String) {
        synchronized(lock) { File(dir(ctx), "$id.json").delete() }
        changes.value++
    }

    /** Briefs left "working" by an app that was killed mid-way. */
    fun markStale(ctx: Context, running: Set<String>) {
        all(ctx).filter { it.status == Brief.WORKING && it.id !in synchronized(running) { running.toSet() } }.forEach {
            it.status = if (it.versions.isEmpty()) Brief.FAILED else Brief.DONE
            if (it.versions.isEmpty()) it.error = "Stopped before finishing. Tap Retry."
            it.stage = ""
            save(ctx, it)
        }
    }

    fun update(ctx: Context, id: String, change: (Brief) -> Unit) {
        val b = get(ctx, id) ?: return
        change(b)
        save(ctx, b)
    }
}

object Prefs {
    const val SHORT = "short"
    const val DETAILED = "detailed"
    const val EN = "en"
    const val AR = "ar"

    private fun p(ctx: Context) = ctx.getSharedPreferences("brief", Context.MODE_PRIVATE)

    fun groqKey(ctx: Context) = p(ctx).getString("groq_key", "").orEmpty().trim()
    fun setGroqKey(ctx: Context, v: String) = p(ctx).edit().putString("groq_key", v.trim()).apply()

    fun length(ctx: Context) = p(ctx).getString("length", SHORT) ?: SHORT
    fun setLength(ctx: Context, v: String) = p(ctx).edit().putString("length", v).apply()

    fun lang(ctx: Context) = p(ctx).getString("lang", EN) ?: EN
    fun setLang(ctx: Context, v: String) = p(ctx).edit().putString("lang", v).apply()

    fun chatModel(ctx: Context) = p(ctx).getString("chat_model", "").orEmpty()
    fun setChatModel(ctx: Context, v: String) = p(ctx).edit().putString("chat_model", v).apply()
}
