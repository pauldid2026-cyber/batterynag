package com.batterynag.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The pool of nag tracks, one folder per battery level.
 *
 * Two rules drive everything here:
 *
 *  1. The phone only talks to the server once it has played everything it
 *     already holds. A daily warning cycle therefore costs nothing, because
 *     the manifest is never fetched and no track is downloaded until the
 *     local pool runs dry.
 *
 *  2. A track is not replayed on a later day. Repeats inside the same day
 *     are fine - the pool is walked round in order - but once every track
 *     has been used, the store asks for new ones before it recycles
 *     anything. Only if the server has nothing new does it clear the day
 *     markers, so the nag is never left without a song.
 *
 * Nothing here runs on the caller's thread: [refresh] is submitted to a
 * single worker, and [pick] only inspects files that are already on disk.
 * If every track is missing or undecodable the store returns no file at
 * all, and the caller falls back to the bundled track.
 */
object TrackStore {

    /** Server root holding the buckets and the manifest above them. */
    private const val ROOT = "https://open.songslike.com/battery-nag-audio"

    /** Descent from the first warning to the last before the phone dies. */
    private val BUCKETS = listOf(30, 25, 20, 15, 10, 5)

    /** A bucket holding fewer than this is reported as needing tracks. */
    private const val MINIMUM = 2

    /** Ceiling per bucket, so a long life cannot fill the disk. */
    private const val MAX_POOL = 9

    /** Bound on one refresh, so an exhausted pool cannot pull the lot. */
    private const val MAX_DOWNLOADS = 4

    private const val PREFS = "battery_nag_tracks"
    private const val MANIFEST = "manifest_json"
    private const val MANIFEST_AT = "manifest_at"
    private const val MANIFEST_MS = 24 * 60 * 60 * 1000L

    private val io = Executors.newSingleThreadExecutor()
    private val refreshing = AtomicBoolean(false)
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** A resolved track: a cached file, or null to mean "use the bundled one". */
    data class Track(val file: File?, val id: String, val bucket: Int) {
        val bundled: Boolean get() = file == null
    }

    /** The bucket a battery level belongs to: 26% is the 30% run, 24% the 25%. */
    fun bucketFor(level: Int): Int =
        BUCKETS.filter { level <= it }.minOrNull() ?: BUCKETS.last()

    private fun cache(c: Context) = File(c.filesDir, "nag_tracks")

    private fun dir(c: Context, bucket: Int) = File(cache(c), bucket.toString())

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, 0)

    private fun today() = dayFormat.format(Date())

    // -- pools and day markers ------------------------------------------------

    /** Ids held for a bucket, in the order they were first fetched. */
    private fun pool(c: Context, bucket: Int): MutableList<String> {
        val raw = prefs(c).getString("pool_$bucket", "") ?: ""
        return raw.split(',').filter { it.isNotBlank() }.toMutableList()
    }

    private fun savePool(c: Context, bucket: Int, ids: List<String>) {
        prefs(c).edit().putString("pool_$bucket", ids.joinToString(",")).apply()
    }

    /** id -> the last date it was used. An id left out has never played. */
    private fun played(c: Context, bucket: Int): MutableMap<String, String> {
        val raw = prefs(c).getString("played_$bucket", "") ?: ""
        val out = mutableMapOf<String, String>()
        runCatching {
            val json = JSONObject(if (raw.isBlank()) "{}" else raw)
            for (key in json.keys()) out[key] = json.optString(key, "")
        }
        return out
    }

    private fun savePlayed(c: Context, bucket: Int, marks: Map<String, String>) {
        val json = JSONObject()
        marks.forEach { (id, day) -> if (day.isNotBlank()) json.put(id, day) }
        prefs(c).edit().putString("played_$bucket", json.toString()).apply()
    }

    // -- picking --------------------------------------------------------------

    /**
     * Returns the track to play for [level], marking it as used today.
     *
     * This is local only and never touches the network, so it is safe on the
     * path that runs while the battery receiver is still alive. An empty pool
     * triggers a refresh for the *next* cycle and hands back the bundled
     * fallback for this one, so a phone that runs dry mid-warning still makes
     * a noise.
     */
    fun pick(c: Context, level: Int): Track {
        val bucket = bucketFor(level)
        val marks = played(c, bucket)
        val today = today()

        val held = pool(c, bucket).mapNotNull { id ->
            val file = File(dir(c, bucket), "$id.mp3")
            if (file.isFile && file.length() > 0L) id to file else null
        }

        // A file on disk that nobody is tracking still counts - it may have
        // arrived from an earlier pool that was pruned from the list.
        val known = held.map { it.first }.toSet()
        val extra = (dir(c, bucket).listFiles() ?: emptyArray())
            .filter { it.isFile && it.extension == "mp3" && it.length() > 0L }
            .filter { it.nameWithoutExtension !in known }
            .map { it.nameWithoutExtension to it }
        val all = held + extra

        val eligible = all.filter { (id, _) ->
            val day = marks[id]
            day == null || day.isBlank() || day == today
        }

        if (eligible.isEmpty()) {
            // Everything has been played. Only now does the phone go online,
            // and this cycle falls back to the bundled track while it does.
            refresh(c, bucket)
            return Track(null, "", bucket)
        }

        // Prefer anything the server sent that has never been used, then the
        // track whose last outing is furthest away - that walks the pool round
        // instead of hammering whichever file happens to sort first.
        val chosen = eligible.sortedWith(
            compareBy<Pair<String, File>> { if (marks[it.first].isNullOrBlank()) 0 else 1 }
                .thenBy { marks[it.first] ?: "" }
        ).first()

        marks[chosen.first] = today
        savePlayed(c, bucket, marks)
        return Track(chosen.second, chosen.first, bucket)
    }

    /** True when this bucket has nothing cached at all. */
    fun isDry(c: Context, level: Int): Boolean {
        val bucket = bucketFor(level)
        return pool(c, bucket).none {
            File(dir(c, bucket), "$it.mp3").let { f -> f.isFile && f.length() > 0L }
        }
    }

    // -- fetching -------------------------------------------------------------

    /** Runs a refresh on the worker unless one is already in flight. */
    fun refresh(c: Context, level: Int) = refreshBucket(c, bucketFor(level))

    private fun refreshBucket(c: Context, bucket: Int) {
        if (!refreshing.compareAndSet(false, true)) return
        val app = c.applicationContext
        io.execute {
            try {
                runCatching { pull(app, bucket) }
            } finally {
                refreshing.set(false)
            }
        }
    }

    /** Pulls the manifest, tops the bucket up, and reopens it if still dry. */
    private fun pull(c: Context, bucket: Int) {
        val manifest = readManifest(c) ?: return

        var added = 0
        for (entry in entries(manifest, bucket)) {
            if (added >= MAX_DOWNLOADS) break
            val id = entry.substringBeforeLast('.')
            val target = File(dir(c, bucket), "$id.mp3")
            if (target.isFile && target.length() > 0L) continue
            if (download("$bucket/$entry", target)) added++
        }

        val marks = played(c, bucket)
        // Held is rewritten with filter and plus, both of which hand back an
        // immutable List - declaring it as one is what lets it be reassigned.
        var held: List<String> = pool(c, bucket)
        val dir = dir(c, bucket)
        held = held.filter { File(dir, "$it.mp3").isFile }
        for (entry in entries(manifest, bucket)) {
            val id = entry.substringBeforeLast('.')
            if (File(dir, "$id.mp3").isFile && id !in held) held = held + id
        }
        val keep = held.takeLast(MAX_POOL)
        savePool(c, bucket, keep)

        val today = today()
        val dry = keep.none { day -> marks[day].isNullOrBlank() || marks[day] == today }
        if (dry) {
            // Nothing new arrived and everything left is spent. Clear the day
            // markers rather than let the warning fall silent.
            savePlayed(c, bucket, emptyMap())
        }
    }

    /** Reads the manifest, from memory when it is less than a day old. */
    private fun readManifest(c: Context, allowNetwork: Boolean = true): JSONObject? {
        val prefs = prefs(c)
        val cached = prefs.getString(MANIFEST, null)
        val at = prefs.getLong(MANIFEST_AT, 0L)
        if (!cached.isNullOrBlank() && System.currentTimeMillis() - at < MANIFEST_MS) {
            runCatching { return JSONObject(cached) }
        }
        if (!allowNetwork) {
            // Settings screen: answer with what we already have rather than
            // hold the page open waiting on a socket.
            if (cached.isNullOrBlank()) return null
            return runCatching { JSONObject(cached) }.getOrNull()
        }

        val text = fetch("$ROOT/manifest.json") ?: return null
        return runCatching {
            prefs.edit()
                .putString(MANIFEST, text)
                .putLong(MANIFEST_AT, System.currentTimeMillis())
                .apply()
            JSONObject(text)
        }.getOrNull()
    }

    /** Reads a small text asset. Null on any failure, so no caller can throw. */
    private fun fetch(url: String): String? {
        val conn = runCatching {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 15000
                setRequestProperty("Accept", "application/json")
            }
        }.getOrNull() ?: return null
        return try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** File names the manifest lists for a bucket, oldest first. */
    private fun entries(manifest: JSONObject, bucket: Int): List<String> {
        val list = manifest.optJSONObject("buckets")
            ?.optJSONObject(bucket.toString())
            ?.optJSONArray("files") ?: return emptyList()
        return (0 until list.length()).mapNotNull { list.optString(it).takeIf { s -> s.isNotBlank() } }
            .map { it.substringAfter('/') }
    }

    private fun download(relative: String, target: File): Boolean {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.part")
        val conn = runCatching {
            (URL("$ROOT/$relative").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 30000
            }
        }.getOrNull() ?: return false
        return try {
            if (conn.responseCode !in 200..299) return false
            conn.inputStream.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            if (temp.length() < 1024L) {
                temp.delete()
                false
            } else {
                // Renaming across the same volume is atomic; copying is the
                // fallback if something already has the target open.
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                target.isFile && target.length() > 0L
            }
        } catch (_: Exception) {
            temp.delete()
            false
        } finally {
            conn.disconnect()
        }
    }

    // -- stock reporting ------------------------------------------------------

    /**
     * A one-line answer for the settings screen, or blank when the manifest
     * has never been read. Refreshing it costs a few kilobytes a day and
     * downloads no audio, which is what keeps a phone from being charged for
     * music it already has.
     */
    fun status(c: Context): String = render(readManifest(c, allowNetwork = true))

    /** Same answer from memory alone, for the call that runs on the page. */
    fun statusCached(c: Context): String = render(readManifest(c, allowNetwork = false))

    /** Re-reads the manifest on the worker and hands the line back to [onDone]. */
    fun statusAsync(c: Context, onDone: (String) -> Unit) {
        io.execute { onDone(runCatching { status(c) }.getOrDefault("")) }
    }

    private fun render(manifest: JSONObject?): String {
        if (manifest == null) return ""
        val needed = mutableListOf<Int>()
        val lines = BUCKETS.map { bucket ->
            val count = entries(manifest, bucket).size
            if (count < MINIMUM) needed += bucket
            "$bucket%: $count"
        }.joinToString("  ")
        return if (needed.isEmpty()) lines
        else "NEW MP3s NEEDED for " + needed.joinToString(", ") + "%\n" + lines
    }

    /** The manifest's own view of which buckets are short, for the UI. */
    fun neededBuckets(c: Context): List<Int> = runCatching {
        val manifest = readManifest(c, allowNetwork = false) ?: return emptyList()
        BUCKETS.filter { entries(manifest, it).size < MINIMUM }
    }.getOrElse { emptyList() }
}
