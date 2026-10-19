package com.batterynag.app

import android.content.Context
import android.os.Vibrator
import android.os.VibrationEffect
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * The pool of nag tracks, one folder per battery level per source.
 *
 * Songs can come from three places, and the settings screen lets the owner
 * pick the first two: [SOURCE_FREEAI] (fresh AI-picked songs every night),
 * [SOURCE_JUKEBOX] (the shared list on the website), and [SOURCE_ROOT] (the
 * original search-driven buckets, which sit behind both as the safety net so
 * the nag is never left without something to play).
 *
 * Two rules drive everything here:
 *
 *  1. The phone only talks to the server once it has played everything it
 *     already holds - except that a non-root source tops up as soon as one
 *     track has gone out, so a used song is replaced by a fresh one rather
 *     than waiting for the whole pool to run dry. A daily warning cycle
 *     still costs almost nothing, because the manifest is cached for a day
 *     and downloads stay bounded per refresh.
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

    // -- the three sources ----------------------------------------------------

    /** The original buckets, built from search terms. Also the fallback. */
    const val SOURCE_ROOT = "songslike"

    /** Songs picked by one OpenRouter call a night, under a `freeai/` prefix. */
    const val SOURCE_FREEAI = "freeai"

    /** Whatever has been added to the shared jukebox on the website. */
    const val SOURCE_JUKEBOX = "jukebox"

    /** Songs from both the jukebox and Free AI, played in random order. */
    const val SOURCE_BOTH = "both"

    private const val SOURCE_KEY = "song_source"

    /** Which source the notification songs come from. */
    fun source(c: Context): String {
        val raw = prefs(c).getString(SOURCE_KEY, SOURCE_FREEAI)
        return when (raw) {
            SOURCE_JUKEBOX -> SOURCE_JUKEBOX
            SOURCE_BOTH -> SOURCE_BOTH
            else -> SOURCE_FREEAI
        }
    }

    /**
     * Switches source and drops the manifest cache, so the next read asks the
     * server for a manifest that includes the newly chosen source instead of
     * answering from one written before the switch.
     */
    fun setSource(c: Context, value: String) {
        prefs(c).edit()
            .putString(SOURCE_KEY, when (value) {
                SOURCE_JUKEBOX -> SOURCE_JUKEBOX
                SOURCE_BOTH -> SOURCE_BOTH
                else -> SOURCE_FREEAI
            })
            .putLong(MANIFEST_AT, 0L)
            .apply()
    }

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
    data class Track(
        val file: File?,
        val id: String,
        val bucket: Int,
        val source: String = SOURCE_ROOT,
    ) {
        val bundled: Boolean get() = file == null
    }

    /** The bucket a battery level belongs to: 26% is the 30% run, 24% the 25%. */
    fun bucketFor(level: Int): Int =
        BUCKETS.filter { level <= it }.minOrNull() ?: BUCKETS.last()

    private fun cache(c: Context) = File(c.filesDir, "nag_tracks")

    /**
     * Where one source keeps one bucket. The jukebox is a single pool rather
     * than a per-level set - a song on the list is simply a song, and the
     * level decides only which pool is asked first.
     */
    private fun dir(c: Context, source: String, bucket: Int): File = when (source) {
        SOURCE_FREEAI -> File(cache(c), "freeai/$bucket")
        SOURCE_JUKEBOX -> File(cache(c), "jukebox")
        else -> File(cache(c), bucket.toString())
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, 0)

    private fun today() = dayFormat.format(Date())

    /** Pref key fragment: freeai stays per level, the jukebox is one pool, both uses its own fragment. */
    private fun tag(source: String, bucket: Int): String = when (source) {
        SOURCE_FREEAI -> "freeai_$bucket"
        SOURCE_JUKEBOX -> "jukebox"
        SOURCE_BOTH -> "both_$bucket"
        else -> bucket.toString()
    }

    // -- pools and day markers ------------------------------------------------

    /** Ids held for a source and bucket, in the order they were first fetched. */
    private fun pool(c: Context, source: String, bucket: Int): MutableList<String> {
        val raw = prefs(c).getString("pool_${tag(source, bucket)}", "") ?: ""
        return raw.split(',').filter { it.isNotBlank() }.toMutableList()
    }

    private fun savePool(c: Context, source: String, bucket: Int, ids: List<String>) {
        prefs(c).edit().putString("pool_${tag(source, bucket)}", ids.joinToString(",")).apply()
    }

    /** id -> the last date it was used. An id left out has never played. */
    private fun played(c: Context, source: String, bucket: Int): MutableMap<String, String> {
        val raw = prefs(c).getString("played_${tag(source, bucket)}", "") ?: ""
        val out = mutableMapOf<String, String>()
        runCatching {
            val json = JSONObject(if (raw.isBlank()) "{}" else raw)
            for (key in json.keys()) out[key] = json.optString(key, "")
        }
        return out
    }

    private fun savePlayed(c: Context, source: String, bucket: Int, marks: Map<String, String>) {
        val json = JSONObject()
        marks.forEach { (id, day) -> if (day.isNotBlank()) json.put(id, day) }
        prefs(c).edit().putString("played_${tag(source, bucket)}", json.toString()).apply()
    }

    // -- picking --------------------------------------------------------------

    /**
     * Returns the track to play for [level], marking it as used today.
     *
     * The chosen source is asked first; if it has nothing left, the sources
     * behind it are walked through down to the root buckets, and only when
     * they too are dry does the phone go online and hand back the bundled
     * fallback for this cycle - so a phone that runs dry mid-warning still
     * makes a noise. This is local only and never touches the network on the
     * happy path, so it is safe on the path that runs while the battery
     * receiver is still alive.
     */
    /** The sources [pick] walks through when the one ahead of it is dry. */
    private fun chain(c: Context): List<String> {
        val first = source(c)
        return when (first) {
            SOURCE_JUKEBOX -> listOf(SOURCE_JUKEBOX, SOURCE_FREEAI, SOURCE_ROOT)
            SOURCE_BOTH -> listOf(SOURCE_JUKEBOX, SOURCE_FREEAI, SOURCE_ROOT)
            else -> listOf(first, SOURCE_ROOT)
        }
    }

    fun pick(c: Context, level: Int): Track {
        val bucket = bucketFor(level)
        val first = source(c)

        val chosen = chain(c).firstNotNullOfOrNull { pickFrom(c, it, bucket) }
        if (chosen == null) {
            // Everything has been played. Only now does the phone go online,
            // and this cycle falls back to the bundled track while it does.
            refresh(c, level)
            return Track(null, "", bucket, first)
        }

        // A non-root source tops up as soon as one track has gone out: the
        // file that was just played stays on disk, and the pull that follows
        // brings the next one in, so there is always a new song waiting.
        if (chosen.source != SOURCE_ROOT) refresh(c, level)
        return chosen
    }

    /** One source's turn at the pool: null when it has nothing eligible. */
    private fun pickFrom(c: Context, source: String, bucket: Int): Track? {
        val marks = played(c, source, bucket)
        val today = today()
        val folder = dir(c, source, bucket)

        val held = pool(c, source, bucket).mapNotNull { id ->
            val file = File(folder, "$id.mp3")
            if (file.isFile && file.length() > 0L) id to file else null
        }

        // A file on disk that nobody is tracking still counts - it may have
        // arrived from an earlier pool that was pruned from the list.
        val known = held.map { it.first }.toSet()
        val extra = (folder.listFiles() ?: emptyArray())
            .filter { it.isFile && it.extension == "mp3" && it.length() > 0L }
            .filter { it.nameWithoutExtension !in known }
            .map { it.nameWithoutExtension to it }
        val all = held + extra

        val eligible = all.filter { (id, _) ->
            val day = marks[id]
            day == null || day.isBlank() || day == today
        }
        if (eligible.isEmpty()) return null

        // Score each eligible track:
        // - Jukebox: prioritize unheard (today) songs, then by global play count (fewest first)
        // - FreeAI/Root: random among eligible (fresh AI picks are already varied)
        val scored = when (source) {
            SOURCE_JUKEBOX -> {
                // Load global play counts for jukebox
                val globalCounts = getJukeboxGlobalCounts(c)
                eligible.map { (id, file) ->
                    val heardToday = marks[id] != null && marks[id] == today
                    val globalCount = globalCounts[id] ?: 0
                    // Score: unheard today = 0, heard today = 1; then by global count (ascending)
                    val priority = if (heardToday) 1 else 0
                    TrackScore(id, file, priority, globalCount)
                }
            }
            else -> {
                // For freeai and root, just randomize among eligible
                eligible.map { (id, file) ->
                    TrackScore(id, file, 0, Random.nextInt(1000000))
                }
            }
        }

        // Sort by priority (unheard first), then by global count (fewest plays first), then random
        val chosen = scored.sortedWith(
            compareBy<TrackScore> { it.priority }
                .thenBy { it.globalCount }
                .thenBy { it.random }
        ).first()

        // Vibrate when a track is selected to play
        vibrateOnPick(c)

        marks[chosen.id] = today
        savePlayed(c, source, bucket, marks)
        // Also increment global play count for jukebox
        if (source == SOURCE_JUKEBOX) {
            incrementJukeboxGlobalCount(c, chosen.id)
        }
        return Track(chosen.file, chosen.id, bucket, source)
    }

    /** Vibrates briefly when a new nag track starts. */
    private fun vibrateOnPick(c: Context) {
        try {
            val v = c.getSystemService(Vibrator::class.java) ?: return
            // Short double-buzz pattern: 100ms on, 50ms off, 100ms on
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 50, 100), -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(longArrayOf(0, 100, 50, 100), -1)
            }
        } catch (_: Exception) {
            // Vibration not available or failed - ignore
        }
    }

    /** Loads global play counts for jukebox tracks (persisted across days). */
    private fun getJukeboxGlobalCounts(c: Context): Map<String, Int> {
        val raw = prefs(c).getString("jukebox_global_counts", "") ?: ""
        val map = mutableMapOf<String, Int>()
        runCatching {
            val json = JSONObject(if (raw.isBlank()) "{}" else raw)
            for (key in json.keys()) map[key] = json.optInt(key, 0)
        }
        return map
    }

    /** Increments the global play count for a jukebox track. */
    private fun incrementJukeboxGlobalCount(c: Context, id: String) {
        val counts = getJukeboxGlobalCounts(c).toMutableMap()
        counts[id] = (counts[id] ?: 0) + 1
        val json = JSONObject()
        counts.forEach { (k, v) -> json.put(k, v) }
        prefs(c).edit().putString("jukebox_global_counts", json.toString()).apply()
    }

    /** Internal data class for scoring tracks during selection. */
    private data class TrackScore(
        val id: String,
        val file: File,
        val priority: Int,      // 0 = unheard today, 1 = heard today
        val globalCount: Int,   // total plays across all time (for jukebox)
        val random: Int = Random.nextInt(1000000)  // tiebreaker
    )

    /** True when the chosen source has nothing cached at all. */
    fun isDry(c: Context, level: Int): Boolean {
        val bucket = bucketFor(level)
        val source = source(c)
        return pool(c, source, bucket).none {
            File(dir(c, source, bucket), "$it.mp3").let { f -> f.isFile && f.length() > 0L }
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
                // The manifest is cached for a day, which meant "Refresh
                // Tracks" re-downloaded files it already knew about while a
                // song added minutes ago stayed invisible. A refresh has to
                // mean asking again.
                prefs(app).edit().putLong(MANIFEST_AT, 0L).apply()
                runCatching { pull(app, bucket) }
            } finally {
                refreshing.set(false)
            }
        }
    }

    /** Pulls the manifest and tops the whole pick chain up. */
    private fun pull(c: Context, bucket: Int) {
        val manifest = readManifest(c) ?: return

        // The same chain pick() walks, best source first, under one download
        // budget: a source further down only misses out while the ones ahead
        // of it still have something to bring in, and the root buckets at the
        // end stay alive as the fallback that keeps the nag from going quiet.
        var left = MAX_DOWNLOADS
        for (source in chain(c)) {
            if (left <= 0) break
            left -= pullSource(c, manifest, source, bucket, left)
        }
    }

    /** Downloads what is missing for one source, then rewrites its pool. */
    private fun pullSource(
        c: Context,
        manifest: JSONObject,
        source: String,
        bucket: Int,
        budget: Int,
    ): Int {
        if (budget <= 0) return 0
        val folder = dir(c, source, bucket)
        folder.mkdirs()

        val wanted = wanted(manifest, source, bucket)
        var added = 0
        for ((relative, id) in wanted) {
            if (added >= budget) break
            val target = File(folder, "$id.mp3")
            if (target.isFile && target.length() > 0L) continue
            if (download(relative, target)) added++
        }

        val marks = played(c, source, bucket)
        // Held is rewritten with filter and plus, both of which hand back an
        // immutable List - declaring it as one is what lets it be reassigned.
        var held: List<String> = pool(c, source, bucket)
        held = held.filter { File(folder, "$it.mp3").isFile }
        for ((_, id) in wanted) {
            if (File(folder, "$id.mp3").isFile && id !in held) held = held + id
        }
        val keep = held.takeLast(MAX_POOL)
        savePool(c, source, bucket, keep)

        val today = today()
        val dry = keep.none { day -> marks[day].isNullOrBlank() || marks[day] == today }
        if (dry) {
            // Nothing new arrived and everything left is spent. Clear the day
            // markers rather than let the warning fall silent.
            savePlayed(c, source, bucket, emptyMap())
        }
        return added
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
            if (conn.responseCode in 200..299 &&
                !conn.contentType.orEmpty().contains("text/html", ignoreCase = true)
            ) {
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

    /**
     * (path under the server root, track id) pairs one source should hold.
     *
     * The root buckets keep their old `30/x.mp3` form so a manifest published
     * before this feature keeps answering exactly as it did; freeai and the
     * jukebox carry their own prefix in every entry, and a manifest without a
     * `sources` section simply offers them nothing until the nightly run has
     * written one.
     */
    private fun wanted(manifest: JSONObject, source: String, bucket: Int): List<Pair<String, String>> {
        val list = when (source) {
            SOURCE_FREEAI -> manifest.optJSONObject("sources")
                ?.optJSONObject("freeai")
                ?.optJSONObject("buckets")
                ?.optJSONObject(bucket.toString())
                ?.optJSONArray("files")
            SOURCE_JUKEBOX -> manifest.optJSONObject("sources")
                ?.optJSONObject("jukebox")
                ?.optJSONArray("files")
            else -> null
        }
        if (list != null) {
            return (0 until list.length())
                .mapNotNull { list.optString(it).takeIf { s -> s.isNotBlank() } }
                .map { it to it.substringAfterLast('/').substringBeforeLast('.') }
        }
        if (source != SOURCE_ROOT) return emptyList()
        return entries(manifest, bucket).map { "$bucket/$it" to it.substringBeforeLast('.') }
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
            // A missing file here is answered with the site's own index page
            // and a 200, so anything claiming to be HTML is refused before its
            // body is read - otherwise an 800 KB page lands in the pool and
            // costs the phone the data this whole design exists to save.
            if (conn.contentType.orEmpty().contains("text/html", ignoreCase = true)) {
                return false
            }
            conn.inputStream.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!isMp3(temp)) {
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

    /**
     * An MP3 opens with an ID3 tag or an MPEG frame sync. The header is the
     * last line of defence against the server's HTML fallback being accepted
     * as audio when it does not announce itself as HTML.
     */
    private fun isMp3(file: File): Boolean {
        if (file.length() < 1024L) return false
        val head = ByteArray(3)
        val read = file.inputStream().use { it.read(head) }
        if (read < 3) return false
        if (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() &&
            head[2] == '3'.code.toByte()
        ) return true
        return (head[0].toInt() and 0xFF) == 0xFF &&
            (head[1].toInt() and 0xE0) == 0xE0
    }

    // -- stock reporting ------------------------------------------------------

    /**
     * A one-line answer for the settings screen, or blank when the manifest
     * has never been read. Refreshing it costs a few kilobytes a day and
     * downloads no audio, which is what keeps a phone from being charged for
     * music it already has. What it says depends on the chosen source.
     */
    fun status(c: Context): String = render(c, readManifest(c, allowNetwork = true))

    /** Same answer from memory alone, for the call that runs on the page. */
    fun statusCached(c: Context): String = render(c, readManifest(c, allowNetwork = false))

    /** Re-reads the manifest on the worker and hands the line back to [onDone]. */
    fun statusAsync(c: Context, onDone: (String) -> Unit) {
        io.execute { onDone(runCatching { status(c) }.getOrDefault("")) }
    }

    /**
     * Returns a list of all currently eligible tracks from the chosen source,
     * ready to play - file, id, bucket, source, plus a formatted display name.
     * This reads the manifest on every call (cached for 24h internally) so it
     * always reflects what the nightly run has just stocked.
     */
    fun currentTracks(c: Context): List<TrackInfo> {
        val manifest = readManifest(c, allowNetwork = true) ?: return emptyList()
        return when (source(c)) {
            SOURCE_JUKEBOX -> jukeboxTracks(c, manifest)
            SOURCE_FREEAI  -> freeaiTracks(c, manifest)
            // "Both" plays the jukebox and Free AI pools together, so the
            // list has to name both of them. Falling through to the root
            // buckets here used to show a pool this source never plays.
            SOURCE_BOTH    -> (freeaiTracks(c, manifest) + jukeboxTracks(c, manifest)).shuffled()
            else           -> rootTracks(c)
        }
    }

    /** Cached jukebox songs, each named from the manifest's jukebox meta. */
    private fun jukeboxTracks(c: Context, manifest: JSONObject): List<TrackInfo> {
        val jukebox = manifest.optJSONObject("sources")?.optJSONObject("jukebox") ?: return emptyList()
        val count = jukebox.optInt("count", 0)
        val meta = jukebox.optJSONObject("meta") ?: JSONObject()
        if (count <= 0) return emptyList()

        val folder = File(cache(c), "jukebox")
        val tracks = folder.listFiles()
            ?.filter { it.isFile && it.extension == "mp3" && it.length() > 0L }
            ?.mapNotNull { f ->
                val id = f.nameWithoutExtension
                val metaObj = meta.optJSONObject(id)
                // optString itself never returns null - the safe call above
                // is what makes it String?.
                val title = metaObj?.optString("title", "") ?: ""
                val artist = metaObj?.optString("artist", "") ?: ""
                TrackInfo(
                    file = f, id = id, bucket = 0, source = SOURCE_JUKEBOX,
                    display = buildDisplay(title, artist, id, 0, SOURCE_JUKEBOX),
                )
            } ?: emptyList()
        return tracks.take(count)   // respect the count reported by the manifest
    }

    /** Cached Free AI songs, each named from that bucket's metadata. */
    private fun freeaiTracks(c: Context, manifest: JSONObject): List<TrackInfo> {
        val freeai = manifest.optJSONObject("sources")?.optJSONObject("freeai") ?: return emptyList()
        val meta = freeai.optJSONObject("meta") ?: JSONObject()
        val tracks = BUCKETS.flatMap { bucket ->
            val bucketMeta = meta.optJSONObject(bucket.toString()) ?: JSONObject()
            val folder = File(cache(c), "freeai/$bucket")
            folder.listFiles()?.filter { it.isFile && it.extension == "mp3" && it.length() > 0L }
                ?.mapNotNull { f ->
                    val id = f.nameWithoutExtension
                    val title = bucketMeta.optString(id, "")
                    TrackInfo(
                        file = f, id = id, bucket = bucket, source = SOURCE_FREEAI,
                        display = buildDisplay(title, "", id, bucket, SOURCE_FREEAI),
                    )
                } ?: emptyList()
        }
        // Randomize within each bucket to give variety; only keep what's actually on disk
        return tracks.shuffled()
    }

    /**
     * The original search-driven buckets. Their manifest entries are bare
     * file paths carrying no titles, so these rows only ever hold the id -
     * the settings page names them from YouTube instead.
     */
    private fun rootTracks(c: Context): List<TrackInfo> {
        val bucket = BUCKETS.first()
        val folder = File(cache(c), bucket.toString())
        return folder.listFiles()?.filter { it.isFile && it.extension == "mp3" && it.length() > 0L }
            ?.mapNotNull { f ->
                val id = f.nameWithoutExtension
                TrackInfo(
                    file = f, id = id, bucket = bucket, source = SOURCE_ROOT,
                    display = buildDisplay("", "", id, bucket, SOURCE_ROOT),
                )
            } ?: emptyList()
    }

    /**
     * Builds a human-readable display string from available metadata.
     * Falls back to showing the bucket and ID if no title is available.
     */
    private fun buildDisplay(title: String, artist: String, id: String, bucket: Int, source: String): String {
        return when {
            title.isNotBlank() && artist.isNotBlank() -> "$artist - $title"
            title.isNotBlank() -> title
            source == SOURCE_JUKEBOX -> "Jukebox - $id"
            source == SOURCE_FREEAI -> "$bucket% - $id"
            else -> "$bucket% - $id"
        }
    }

    data class TrackInfo(
        val file: File?,
        val id: String,
        val bucket: Int,
        val source: String,
        val display: String
    )

    private fun render(c: Context, manifest: JSONObject?): String {
        if (manifest == null) return ""
        return when (source(c)) {
            SOURCE_JUKEBOX -> renderJukebox(manifest)
            SOURCE_FREEAI -> renderBuckets(manifest, SOURCE_FREEAI)
            else -> renderBuckets(manifest, SOURCE_ROOT)
        }
    }

    private fun renderJukebox(manifest: JSONObject): String {
        val count = manifest.optJSONObject("sources")
            ?.optJSONObject("jukebox")?.optInt("count", 0) ?: 0
        val line = "jukebox: $count ready"
        return if (count < MINIMUM) "NEW MP3s NEEDED\n$line" else line
    }

    private fun renderBuckets(manifest: JSONObject, source: String): String {
        val needed = mutableListOf<Int>()
        val lines = BUCKETS.map { bucket ->
            val count = wanted(manifest, source, bucket).size
            if (count < MINIMUM) needed += bucket
            "$bucket%: $count"
        }.joinToString("  ")
        return if (needed.isEmpty()) lines
        else "NEW MP3s NEEDED for " + needed.joinToString(", ") + "%\n" + lines
    }

    /** The manifest's own view of which buckets are short, for the UI. */
    fun neededBuckets(c: Context): List<Int> = runCatching {
        val manifest = readManifest(c, allowNetwork = false) ?: return emptyList()
        val source = source(c)
        if (source == SOURCE_JUKEBOX) {
            val count = manifest.optJSONObject("sources")
                ?.optJSONObject("jukebox")?.optInt("count", 0) ?: 0
            if (count < MINIMUM) listOf(BUCKETS.first()) else emptyList()
        } else {
            BUCKETS.filter { wanted(manifest, source, it).size < MINIMUM }
        }
    }.getOrElse { emptyList() }
}
