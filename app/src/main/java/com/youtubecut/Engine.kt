package com.youtubecut

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File

/**
 * غلاف yt-dlp: يجلب معلومات الفيديو والترجمة ويحمّل مقاطع محددة فقط
 * (دون تحميل الفيديو الكامل) عبر --download-sections.
 */
object Engine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ready = CompletableDeferred<Unit>()

    @Volatile private var updateJob: Job? = null
    @Volatile private var started = false
    private lateinit var app: Context

    /** يُستدعى من Application.onCreate. التهيئة الأولى تفكّ بايثون وقد تستغرق بضع ثوانٍ. */
    fun start(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        scope.launch {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
            } catch (t: Throwable) {
                ready.completeExceptionally(t)
                return@launch
            }
            // تحديث yt-dlp تلقائياً مرة كل 24 ساعة (يوتيوب يغيّر آلياته باستمرار)
            if (System.currentTimeMillis() - Prefs(app).lastUpdate > 24L * 3600 * 1000) {
                updateJob = launch {
                    try {
                        doUpdate()
                    } catch (t: Throwable) {
                        // نتجاهل: سيُعاد المحاولة لاحقاً
                    }
                }
            }
            ready.complete(Unit)
        }
    }

    suspend fun awaitReady() {
        try {
            ready.await()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            throw IllegalStateException("تعذّرت تهيئة محرك التحميل: ${t.message}", t)
        }
        updateJob?.let { withTimeoutOrNull(45_000) { it.join() } }
    }

    fun awaitReadyBlocking() = runBlocking { awaitReady() }

    val isReady: Boolean get() = ready.isCompleted

    private fun exec(
        req: YoutubeDLRequest,
        processId: String? = null,
        cb: ((Float, Long, String) -> Unit)? = null,
    ) = YoutubeDL.getInstance().execute(req, processId, cb)

    private fun JSONObject.str(k: String): String? =
        if (isNull(k)) null else optString(k).ifBlank { null }

    // ───────────────────────── معلومات الفيديو ─────────────────────────

    suspend fun fetchInfo(url: String): VideoMeta = withContext(Dispatchers.IO) {
        awaitReady()
        val req = YoutubeDLRequest(url)
        req.addOption("--dump-single-json")
        req.addOption("--skip-download")
        req.addOption("--no-playlist")
        req.addOption("--no-warnings")
        val out = exec(req).out
        val s = out.indexOf('{')
        val e = out.lastIndexOf('}')
        if (s < 0 || e <= s) throw IllegalStateException("لم يُرجع yt-dlp معلومات الفيديو.")
        val j = JSONObject(out.substring(s, e + 1))
        if (j.optBoolean("is_live", false)) {
            throw IllegalStateException("البث المباشر غير مدعوم. انتظر انتهاءه ثم أعد المحاولة.")
        }
        val id = j.str("id") ?: throw IllegalStateException("تعذّر قراءة معرّف الفيديو.")
        VideoMeta(
            id = id,
            title = j.str("title") ?: id,
            durationSec = j.optDouble("duration", 0.0).toInt(),
            url = url,
            lang = j.str("language"),
        )
    }

    // ───────────────────────── الترجمة ─────────────────────────

    /** يرجع الترجمة (ثانية + نص) مدموجة في أسطر بطول ~10 ثوانٍ، أو null إن لم توجد. */
    suspend fun fetchTranscript(meta: VideoMeta): List<TLine>? = withContext(Dispatchers.IO) {
        awaitReady()
        val dir = File(app.cacheDir, "subs/${meta.id}")
        dir.deleteRecursively()
        dir.mkdirs()

        val langs = LinkedHashSet<String>()
        meta.lang?.let {
            langs.add("$it-orig")
            langs.add(it)
        }
        langs.addAll(listOf("ar-orig", "ar", "en-orig", "en"))

        val req = YoutubeDLRequest(meta.url)
        req.addOption("--skip-download")
        req.addOption("--no-playlist")
        req.addOption("--write-subs")
        req.addOption("--write-auto-subs")
        req.addOption("--sub-langs", langs.joinToString(","))
        req.addOption("--sub-format", "json3")
        req.addOption("-o", File(dir, "%(id)s").absolutePath)
        try {
            exec(req)
        } catch (t: Throwable) {
            // قد يفشل تنزيل لغة واحدة بينما نجحت أخرى؛ نفحص الملفات الناتجة
        }

        val files = dir.listFiles()?.filter { it.name.endsWith(".json3") }.orEmpty()
        fun langOf(f: File) = f.name.removeSuffix(".json3").substringAfterLast('.')
        val pick = langs.firstNotNullOfOrNull { l -> files.firstOrNull { langOf(it) == l } } ?: files.firstOrNull()
        val result = pick?.let { merge(parseJson3(it)) }
        dir.deleteRecursively()
        result?.takeIf { it.isNotEmpty() }
    }

    private fun parseJson3(f: File): List<TLine> {
        val events = JSONObject(f.readText()).optJSONArray("events") ?: return emptyList()
        val out = ArrayList<TLine>()
        for (i in 0 until events.length()) {
            val ev = events.getJSONObject(i)
            val segs = ev.optJSONArray("segs") ?: continue
            val sb = StringBuilder()
            for (k in 0 until segs.length()) sb.append(segs.getJSONObject(k).optString("utf8", ""))
            val text = sb.toString().replace('\n', ' ').trim()
            if (text.isEmpty()) continue
            out.add(TLine((ev.optLong("tStartMs", 0L) / 1000L).toInt(), text))
        }
        return out
    }

    private fun merge(raw: List<TLine>, windowSec: Int = 10): List<TLine> {
        val out = ArrayList<TLine>()
        val sb = StringBuilder()
        var start = 0
        for (l in raw) {
            if (sb.isNotEmpty() && l.sec - start >= windowSec) {
                out.add(TLine(start, sb.toString().trim()))
                sb.setLength(0)
            }
            if (sb.isEmpty()) start = l.sec
            sb.append(l.text).append(' ')
        }
        if (sb.isNotEmpty()) out.add(TLine(start, sb.toString().trim()))
        return out
    }

    // ───────────────────────── تحميل مقطع ─────────────────────────

    /** متزامن: يُستدعى من خيط خلفي داخل خدمة التحميل. يرجع الملف الناتج في [dir]. */
    fun downloadClip(
        meta: VideoMeta,
        clip: ClipUi,
        quality: String,
        dir: File,
        base: String,
        processId: String,
        onProgress: (Float, String) -> Unit,
    ): File {
        awaitReadyBlocking()
        val req = YoutubeDLRequest(meta.url)
        req.addOption("--no-playlist")
        req.addOption("--no-mtime")
        req.addOption("--download-sections", "*${hms(clip.cutStart)}-${hms(clip.cutEnd)}")
        if (quality == "audio") {
            req.addOption("-f", "ba[ext=m4a]/ba/b")
        } else {
            val h = quality.toIntOrNull() ?: 720
            req.addOption(
                "-f",
                "bv*[height<=$h][ext=mp4]+ba[ext=m4a]/bv*[height<=$h]+ba/b[height<=$h]/b",
            )
            req.addOption("--merge-output-format", "mp4")
        }
        req.addOption("-o", File(dir, "$base.%(ext)s").absolutePath)

        exec(req, processId) { p, _, line -> onProgress(p, line) }

        return dir.listFiles()
            ?.filter { it.name.startsWith("$base.") && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?.maxByOrNull { it.length() }
            ?: throw IllegalStateException("لم ينتج yt-dlp أي ملف لهذا المقطع.")
    }

    // ───────────────────────── التحديث ─────────────────────────

    private fun doUpdate(): String {
        val st = YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.NIGHTLY)
        Prefs(app).lastUpdate = System.currentTimeMillis()
        return when (st.toString()) {
            "DONE" -> "تم تحديث المحرك إلى أحدث إصدار."
            "ALREADY_UP_TO_DATE" -> "المحرك محدّث بالفعل."
            else -> "اكتمل التحديث."
        }
    }

    suspend fun update(): String = withContext(Dispatchers.IO) {
        awaitReady()
        doUpdate()
    }

    // ───────────────────────── رسائل الأخطاء ─────────────────────────

    fun friendly(e: Throwable): String {
        val m = e.message ?: e.toString()
        return when {
            "not a bot" in m || "Sign in to confirm" in m ->
                "يطلب يوتيوب التحقق من أنك لست روبوتاً. جرّب لاحقاً أو بدّل الشبكة (واي فاي/بيانات الهاتف)."
            "Private video" in m -> "هذا الفيديو خاص."
            "Video unavailable" in m -> "الفيديو غير متاح."
            "HTTP Error 403" in m ->
                "رفض يوتيوب الطلب (403). حدّث المحرك من الإعدادات ثم أعد المحاولة."
            "JavaScript runtime" in m && "ERROR" in m ->
                "تعذّر تشغيل محرك جافاسكربت المطلوب من يوتيوب. حدّث المحرك من الإعدادات."
            else -> m.lines().lastOrNull { it.trimStart().startsWith("ERROR") }?.take(300) ?: m.take(300)
        }
    }
}
