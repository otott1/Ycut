package com.youtubecut

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.Locale

// ───────────────────────── الحالة المشتركة بين الواجهة والخدمة ─────────────────────────

data class DlRequest(val meta: VideoMeta, val clips: List<ClipUi>, val quality: String)

enum class ItemState { Queued, Running, Done, Failed, Cancelled }

data class DlItem(
    val clip: ClipUi,
    val state: ItemState = ItemState.Queued,
    val progress: Float = -1f,
    val note: String = "",
    val uri: Uri? = null,
)

data class DlState(
    val items: List<DlItem> = emptyList(),
    val active: Boolean = false,
    val folder: String = "",
    val location: String = "",
)

object DownloadHub {
    val state = MutableStateFlow(DlState())

    @Volatile var request: DlRequest? = null
    @Volatile var cancelled = false
    @Volatile var currentProcessId: String? = null

    fun begin(r: DlRequest): Boolean {
        if (state.value.active) return false
        request = r
        cancelled = false
        val folder = safeName(r.meta.title, 60).ifBlank { r.meta.id }
        val root = if (r.quality == "audio") "Music" else "Movies"
        state.value = DlState(
            items = r.clips.map { DlItem(it) },
            active = true,
            folder = folder,
            location = "$root/YoutubeCut/$folder",
        )
        return true
    }

    fun update(index: Int, f: (DlItem) -> DlItem) {
        state.update { s -> s.copy(items = s.items.mapIndexed { i, it -> if (i == index) f(it) else it }) }
    }

    fun cancel() {
        cancelled = true
        val pid = currentProcessId
        if (pid != null) {
            try {
                YoutubeDL.getInstance().destroyProcessById(pid)
            } catch (t: Throwable) {
                // لا شيء
            }
        }
    }

    fun reset() {
        if (!state.value.active) state.value = DlState()
    }
}

// ───────────────────────── الحفظ في المعرض (MediaStore) ─────────────────────────

object MediaSaver {
    /** ينسخ الملف إلى Movies/YoutubeCut/<folder> (أو Music للصوت) دون الحاجة لأي صلاحية تخزين. */
    fun save(ctx: Context, src: File, folder: String, displayName: String, audio: Boolean): Uri {
        val ext = src.extension.lowercase(Locale.ROOT)
        val mime = if (audio) {
            when (ext) {
                "m4a", "mp4", "aac" -> "audio/mp4"
                "mp3" -> "audio/mpeg"
                "opus", "ogg" -> "audio/ogg"
                "webm" -> "audio/webm"
                else -> "audio/*"
            }
        } else {
            when (ext) {
                "mp4", "m4v" -> "video/mp4"
                "webm" -> "video/webm"
                "mkv" -> "video/x-matroska"
                else -> "video/*"
            }
        }
        val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
        val collection = if (audio) MediaStore.Audio.Media.getContentUri(volume)
        else MediaStore.Video.Media.getContentUri(volume)
        val root = if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$root/YoutubeCut/$folder")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(collection, values)
            ?: throw IOException("تعذّر إنشاء الملف في مساحة التخزين.")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("تعذّر فتح الملف للكتابة.")
            out.use { o -> src.inputStream().use { it.copyTo(o) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
        return uri
    }
}

// ───────────────────────── خدمة المقدّمة ─────────────────────────

/** تستمر في التحميل حتى لو خرجت من التطبيق أو أُغلقت الشاشة. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(CHANNEL, "تحميل المقاطع", NotificationManager.IMPORTANCE_LOW),
            )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            DownloadHub.cancel()
            return START_NOT_STICKY
        }
        startForeground(
            NOTIF_ID,
            progressNotification("نجهّز التحميل…", 0, 1),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (job?.isActive != true) {
            job = scope.launch {
                val pm = getSystemService(PowerManager::class.java)
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "youtubecut:download")
                    .also { it.acquire(2 * 60 * 60 * 1000L) }
                try {
                    runQueue()
                } finally {
                    wake?.let { if (it.isHeld) it.release() }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun runQueue() {
        val hub = DownloadHub
        val req = hub.request ?: return
        val folder = hub.state.value.folder
        val audio = req.quality == "audio"
        val work = File(cacheDir, "clips/${req.meta.id}")
        work.deleteRecursively()
        work.mkdirs()

        val total = req.clips.size
        var ok = 0
        req.clips.forEachIndexed { i, clip ->
            if (hub.cancelled) {
                hub.update(i) { it.copy(state = ItemState.Cancelled) }
                return@forEachIndexed
            }
            hub.update(i) { it.copy(state = ItemState.Running, progress = -1f, note = "") }
            post(progressNotification("مقطع ${i + 1} من $total: ${clip.title}", i, total))

            val pid = "clip-${req.meta.id}-$i"
            hub.currentProcessId = pid
            try {
                var last = 0L
                val file = Engine.downloadClip(req.meta, clip, req.quality, work, "clip_${clip.index}", pid) { p, _ ->
                    val now = System.currentTimeMillis()
                    if (now - last > 500) {
                        last = now
                        val frac = if (p > 0f) (p / 100f).coerceAtMost(1f) else -1f
                        hub.update(i) { it.copy(progress = frac) }
                    }
                }
                val name = String.format(Locale.ROOT, "%02d", clip.index) + "_" +
                    safeName(clip.title, 70).ifBlank { "clip" } + "." + file.extension
                val uri = MediaSaver.save(this, file, folder, name, audio)
                file.delete()
                hub.update(i) { it.copy(state = ItemState.Done, progress = 1f, uri = uri, note = "") }
                ok++
            } catch (t: Throwable) {
                val wasCancelled = hub.cancelled
                hub.update(i) {
                    it.copy(
                        state = if (wasCancelled) ItemState.Cancelled else ItemState.Failed,
                        note = if (wasCancelled) "" else Engine.friendly(t),
                    )
                }
            } finally {
                hub.currentProcessId = null
            }
        }
        work.deleteRecursively()
        hub.state.update { it.copy(active = false) }
        postDone(ok, total)
    }

    // ───────── الإشعارات ─────────

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun progressNotification(text: String, done: Int, total: Int): Notification {
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("قص يوتيوب")
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setProgress(total, done, false)
            .addAction(0, "إيقاف", cancel)
            .build()
    }

    private fun post(n: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }

    private fun postDone(ok: Int, total: Int) {
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("قص يوتيوب")
            .setContentText("تم حفظ $ok من $total مقطعاً")
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(DONE_ID, n)
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 1
        private const val DONE_ID = 2
        private const val ACTION_CANCEL = "com.youtubecut.CANCEL"
    }
}
