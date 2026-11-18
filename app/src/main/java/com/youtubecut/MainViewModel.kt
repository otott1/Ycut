package com.youtubecut

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppSettings(
    val model: String?,
    val autoModel: String?,
    val quality: String,
    val marginStart: Int,
    val marginEnd: Int,
)

data class UiState(
    // مفتاح Gemini
    val needsKey: Boolean,
    val changingKey: Boolean = false,
    val keyBusy: Boolean = false,
    val keyError: String? = null,
    val maskedKey: String = "",
    // الشاشة الرئيسية
    val url: String = "",
    val busy: Boolean = false,
    val status: String = "",
    val progress: Float? = null,
    val error: String? = null,
    val meta: VideoMeta? = null,
    val clips: List<ClipUi> = emptyList(),
    // الإعدادات
    val showSettings: Boolean = false,
    val models: List<String> = emptyList(),
    val settings: AppSettings,
    val ytdlpStatus: String = "",
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val appCtx: Application = app
    val prefs = Prefs(app)

    private val _ui = MutableStateFlow(
        UiState(
            needsKey = prefs.apiKey == null,
            maskedKey = mask(prefs.apiKey),
            settings = snapshot(),
        ),
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private fun snapshot() = AppSettings(
        model = prefs.model,
        autoModel = prefs.autoModel,
        quality = prefs.quality,
        marginStart = prefs.marginStart,
        marginEnd = prefs.marginEnd,
    )

    private fun mask(key: String?): String =
        if (key == null) "" else if (key.length > 8) key.take(4) + "••••" + key.takeLast(4) else "••••"

    // ───────────────────────── مفتاح Gemini ─────────────────────────

    fun saveKey(raw: String) {
        val key = raw.trim()
        if (key.isEmpty()) {
            _ui.update { it.copy(keyError = "أدخل المفتاح أولاً.") }
            return
        }
        _ui.update { it.copy(keyBusy = true, keyError = null) }
        viewModelScope.launch {
            try {
                val models = withContext(Dispatchers.IO) { Gemini.listModels(key) }
                prefs.apiKey = key
                prefs.autoModel = Gemini.pickModel(models)
                prefs.autoModelAt = System.currentTimeMillis()
                _ui.update {
                    it.copy(
                        needsKey = false,
                        changingKey = false,
                        keyBusy = false,
                        keyError = null,
                        maskedKey = mask(key),
                        models = Gemini.selectable(models),
                        settings = snapshot(),
                    )
                }
            } catch (e: GeminiException) {
                val msg = if (e.kind == Kind.InvalidKey) "المفتاح غير صالح. تأكد من نسخه كاملاً." else e.message
                _ui.update { it.copy(keyBusy = false, keyError = msg) }
            }
        }
    }

    fun requestKeyChange() = _ui.update { it.copy(needsKey = true, changingKey = true, keyError = null) }

    fun cancelKeyChange() = _ui.update { it.copy(needsKey = false, changingKey = false, keyError = null) }

    // ───────────────────────── الرابط والتحليل ─────────────────────────

    fun setUrl(s: String) = _ui.update { it.copy(url = s, error = null) }

    /** رابط مُشارك من تطبيق يوتيوب. */
    fun onSharedText(text: String) {
        if (extractVideoId(text) == null) return
        _ui.update { it.copy(url = text.trim(), error = null) }
        if (!_ui.value.needsKey) analyze()
    }

    fun analyze() {
        val s = _ui.value
        if (s.busy) return
        val id = extractVideoId(s.url)
        if (id == null) {
            _ui.update { it.copy(error = "الرابط غير صالح. الصق رابط فيديو من يوتيوب.") }
            return
        }
        val key = prefs.apiKey
        if (key == null) {
            _ui.update { it.copy(needsKey = true) }
            return
        }
        _ui.update {
            it.copy(
                busy = true, error = null, meta = null, clips = emptyList(), progress = null,
                status = if (Engine.isReady) "جلب معلومات الفيديو…" else "تهيئة المحرك لأول مرة…",
            )
        }
        viewModelScope.launch {
            try {
                val meta = Engine.fetchInfo("https://www.youtube.com/watch?v=$id")
                _ui.update { it.copy(status = "جلب الترجمة…") }
                val lines = Engine.fetchTranscript(meta)
                val raw = gemini(key, meta, lines)
                val clips = buildClips(raw, meta)
                _ui.update {
                    it.copy(
                        busy = false, status = "", progress = null, meta = meta, clips = clips,
                        error = if (clips.isEmpty()) "لم يعثر Gemini على مقاطع في هذا الفيديو." else null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GeminiException) {
                if (e.kind == Kind.InvalidKey) {
                    _ui.update {
                        it.copy(
                            busy = false, needsKey = true, changingKey = false,
                            keyError = "المفتاح المحفوظ لم يعد صالحاً. أدخل مفتاحاً جديداً.",
                        )
                    }
                } else {
                    _ui.update { it.copy(busy = false, error = e.message) }
                }
            } catch (t: Throwable) {
                _ui.update { it.copy(busy = false, error = Engine.friendly(t)) }
            }
        }
    }

    private suspend fun gemini(key: String, meta: VideoMeta, lines: List<TLine>?): List<RawClip> {
        suspend fun attempt(model: String): List<RawClip> = withContext(Dispatchers.IO) {
            if (!lines.isNullOrEmpty()) {
                Gemini.analyzeTranscript(key, model, prefs.instructions, lines) { cur, total ->
                    _ui.update {
                        it.copy(
                            status = "تحليل النص بواسطة Gemini… (جزء $cur من $total)",
                            progress = (cur - 1f) / total,
                        )
                    }
                }
            } else {
                _ui.update {
                    it.copy(
                        status = "لا توجد ترجمة لهذا الفيديو، فسيحلّله Gemini مباشرة وقد يستغرق دقائق…",
                        progress = null,
                    )
                }
                Gemini.analyzeVideo(key, model, prefs.instructions, meta.url)
            }
        }

        return try {
            attempt(resolveModel(key))
        } catch (e: GeminiException) {
            if (e.kind == Kind.ModelNotFound && prefs.model == null) {
                // النموذج المخزّن سُحب: نعيد اختيار الأحدث من القائمة الفعلية
                prefs.autoModel = null
                attempt(resolveModel(key))
            } else {
                throw e
            }
        }
    }

    private suspend fun resolveModel(key: String): String {
        prefs.model?.let { return it }
        val cached = prefs.autoModel
        val fresh = System.currentTimeMillis() - prefs.autoModelAt < 3L * 24 * 3600 * 1000
        if (cached != null && fresh) return cached
        return try {
            val models = withContext(Dispatchers.IO) { Gemini.listModels(key) }
            val m = Gemini.pickModel(models) ?: "gemini-flash-latest"
            prefs.autoModel = m
            prefs.autoModelAt = System.currentTimeMillis()
            m
        } catch (e: GeminiException) {
            if (e.kind == Kind.InvalidKey) throw e
            cached ?: "gemini-flash-latest"
        }
    }

    /** يحوّل مقترحات Gemini إلى مقاطع مع هوامش الأمان (قبل البداية وبعد النهاية). */
    private fun buildClips(raw: List<RawClip>, meta: VideoMeta): List<ClipUi> {
        val before = prefs.marginStart
        val after = prefs.marginEnd
        val dur = meta.durationSec
        return raw
            .filter { it.end > it.start }
            .filter { dur <= 0 || it.start < dur }
            .sortedBy { it.start }
            .mapIndexed { i, c ->
                val cutStart = (c.start - before).coerceAtLeast(0)
                var cutEnd = c.end + after
                if (dur > 0) cutEnd = cutEnd.coerceAtMost(dur)
                ClipUi(i + 1, c.title, c.start, c.end, cutStart, cutEnd, true)
            }
    }

    // ───────────────────────── الاختيار والتحميل ─────────────────────────

    fun toggle(index: Int) = _ui.update { s ->
        s.copy(clips = s.clips.map { if (it.index == index) it.copy(selected = !it.selected) else it })
    }

    fun selectAll(value: Boolean) = _ui.update { s ->
        s.copy(clips = s.clips.map { it.copy(selected = value) })
    }

    fun startDownload() {
        val s = _ui.value
        val meta = s.meta ?: return
        val chosen = s.clips.filter { it.selected }
        if (chosen.isEmpty()) return
        if (!DownloadHub.begin(DlRequest(meta, chosen, prefs.quality))) return
        ContextCompat.startForegroundService(appCtx, Intent(appCtx, DownloadService::class.java))
    }

    fun cancelDownload() = DownloadHub.cancel()

    fun closeDownload() = DownloadHub.reset()

    // ───────────────────────── الإعدادات ─────────────────────────

    fun openSettings() {
        _ui.update { it.copy(showSettings = true, settings = snapshot()) }
        val key = prefs.apiKey ?: return
        if (_ui.value.models.isEmpty()) {
            viewModelScope.launch {
                try {
                    val all = withContext(Dispatchers.IO) { Gemini.listModels(key) }
                    _ui.update { it.copy(models = Gemini.selectable(all)) }
                } catch (e: Exception) {
                    // القائمة اختيارية؛ نتجاهل الفشل
                }
            }
        }
    }

    fun closeSettings() = _ui.update { it.copy(showSettings = false) }

    private fun refreshSettings() = _ui.update { it.copy(settings = snapshot()) }

    fun setModel(m: String?) {
        prefs.model = m
        refreshSettings()
    }

    fun setQuality(q: String) {
        prefs.quality = q
        refreshSettings()
    }

    fun setMarginStart(v: Int) {
        prefs.marginStart = v
        refreshSettings()
    }

    fun setMarginEnd(v: Int) {
        prefs.marginEnd = v
        refreshSettings()
    }

    /** لا نحدّث الحالة هنا حتى لا يُعاد ضبط مؤشر الكتابة أثناء التحرير. */
    fun setInstructions(s: String) {
        prefs.instructions = s
    }

    fun resetInstructions() {
        prefs.instructions = DEFAULT_INSTRUCTIONS
    }

    fun updateYtDlp() {
        _ui.update { it.copy(ytdlpStatus = "جارٍ التحديث…") }
        viewModelScope.launch {
            val msg = try {
                Engine.update()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                "تعذّر التحديث: ${Engine.friendly(t)}"
            }
            _ui.update { it.copy(ytdlpStatus = msg) }
        }
    }
}
