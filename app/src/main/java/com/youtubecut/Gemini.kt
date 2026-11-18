package com.youtubecut

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class Kind { InvalidKey, RateLimit, ModelNotFound, Network, Server, Other }

class GeminiException(val kind: Kind, message: String) : Exception(message)

/** التعليمات الافتراضية (قابلة للتعديل من الإعدادات). صيغة الإخراج تُضاف تلقائياً ولا يمكن كسرها. */
val DEFAULT_INSTRUCTIONS: String = """
المحتوى: جلسة أسئلة وأجوبة دينية باللغة العربية.
المهمة: استخرج كل المسائل الدينية المميزة، وحدّد لكل مسألة وقت بداية السؤال ووقت نهاية الجواب.
قواعد الاستخراج:
1. لا تُفوّت أي مسألة مهما كانت بسيطة.
2. إذا تجاوزت المناقشة 3 دقائق فقسّمها إلى مسائل فرعية.
3. ركّز على: الأحكام الفقهية، التفسير، الحديث، العقيدة، الأخلاق.
4. العنوان موجز وواضح يلخّص المسألة، ولا يزيد على عشر كلمات.
""".trimIndent()

private val FORMAT_RULES: String = """
التنسيق الإلزامي: أعد مصفوفة JSON فقط ولا شيء غيرها، بهذا الشكل:
[{"start":"HH:MM:SS","end":"HH:MM:SS","title":"العنوان"}]
- start هو وقت بداية السؤال، وend هو وقت نهاية الجواب، بصيغة HH:MM:SS كما في النص.
- يجب أن يكون end بعد start، وأن تكون الأوقات ضمن نطاق ما أُرسل إليك.
- إذا لم توجد أي مسألة فأعد [].
""".trimIndent()

/** كل الدوال هنا متزامنة (blocking): استدعها من Dispatchers.IO. */
object Gemini {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    private val JSON_TYPE = "application/json".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.MINUTES)
        .readTimeout(10, TimeUnit.MINUTES)
        .callTimeout(12, TimeUnit.MINUTES)
        .build()

    // ───────────────────────── المفتاح والنماذج ─────────────────────────

    /** يرجع النماذج التي تدعم generateContent. يرمي InvalidKey إن كان المفتاح خاطئاً. */
    fun listModels(key: String): List<String> {
        val names = ArrayList<String>()
        var token: String? = null
        do {
            val url = "$BASE/models?pageSize=200" + (token?.let { "&pageToken=$it" } ?: "")
            val text = call(Request.Builder().url(url).header("x-goog-api-key", key).get().build())
            val j = JSONObject(text)
            val arr = j.optJSONArray("models")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val methods = m.optJSONArray("supportedGenerationMethods")
                    var ok = methods == null
                    if (methods != null) {
                        for (k in 0 until methods.length()) {
                            if (methods.optString(k) == "generateContent") ok = true
                        }
                    }
                    if (ok) names.add(m.optString("name").removePrefix("models/"))
                }
            }
            token = if (j.isNull("nextPageToken")) null else j.optString("nextPageToken").ifBlank { null }
        } while (token != null)
        return names
    }

    private fun ver(name: String): Int {
        val m = Regex("""gemini-(\d+)(?:\.(\d+))?""").find(name) ?: return 0
        return m.groupValues[1].toInt() * 1000 + (m.groupValues[2].toIntOrNull() ?: 0)
    }

    /** أحدث نموذج Flash مستقر من القائمة الفعلية للحساب (لا نعتمد على اسم ثابت قد يُسحب). */
    fun pickModel(all: List<String>): String? {
        val banned = listOf(
            "lite", "image", "tts", "live", "audio", "embedding",
            "thinking", "robotics", "computer", "exp", "vision", "customtools",
        )
        val cands = all.filter { it.startsWith("gemini-") && "flash" in it && banned.none { b -> b in it } }
        val stable = cands.filter { "preview" !in it && "latest" !in it }
        val pool = stable.ifEmpty { cands }
        return pool.maxWithOrNull(compareBy<String>({ ver(it) }).thenBy { -it.length })
    }

    /** النماذج التي نعرضها للاختيار اليدوي. */
    fun selectable(all: List<String>): List<String> {
        val banned = listOf("image", "tts", "live", "audio", "embedding", "robotics", "computer", "vision", "customtools")
        return all
            .filter { it.startsWith("gemini-") && banned.none { b -> b in it } }
            .sortedByDescending { ver(it) }
    }

    // ───────────────────────── التحليل ─────────────────────────

    fun analyzeTranscript(
        key: String,
        model: String,
        instructions: String,
        lines: List<TLine>,
        onProgress: (current: Int, total: Int) -> Unit,
    ): List<RawClip> {
        val chunks = chunk(lines)
        val all = ArrayList<RawClip>()
        chunks.forEachIndexed { i, part ->
            onProgress(i + 1, chunks.size)
            val text = part.joinToString("\n") { "${hms(it.sec)} | ${it.text}" }
            val parts = listOf(JSONObject().put("text", "نص الفيديو بصيغة «HH:MM:SS | النص»:\n$text"))
            all.addAll(parseClips(generate(key, model, body(instructions, parts))))
            if (i < chunks.size - 1) Thread.sleep(1000)
        }
        return dedupe(all)
    }

    /** احتياطي عند غياب الترجمة: يحلّل Gemini الفيديو مباشرة من الرابط. */
    fun analyzeVideo(key: String, model: String, instructions: String, url: String): List<RawClip> {
        val parts = listOf(
            JSONObject().put("fileData", JSONObject().put("fileUri", url)),
            JSONObject().put(
                "text",
                "حلّل الفيديو المرفق كاملاً واستخرج المسائل المطلوبة. أعطِ الأوقات بصيغة HH:MM:SS حسب زمن الفيديو.",
            ),
        )
        return dedupe(parseClips(generate(key, model, body(instructions, parts))))
    }

    /** نقسّم الترجمة إلى نوافذ من 60 دقيقة مع تداخل دقيقتين كي لا تُقطع مسألة عند الحدود. */
    private fun chunk(lines: List<TLine>, windowSec: Int = 3600, overlapSec: Int = 120): List<List<TLine>> {
        if (lines.isEmpty()) return emptyList()
        val end = lines.last().sec
        val out = ArrayList<List<TLine>>()
        var s = 0
        while (s <= end) {
            val part = lines.filter { it.sec >= s && it.sec < s + windowSec + overlapSec }
            if (part.isNotEmpty()) out.add(part)
            s += windowSec
        }
        return out
    }

    /** يحذف المقاطع المكررة الناتجة عن تداخل الأجزاء. */
    private fun dedupe(list: List<RawClip>): List<RawClip> {
        val out = ArrayList<RawClip>()
        for (c in list.sortedBy { it.start }) {
            val prev = out.lastOrNull()
            if (prev != null && c.start - prev.start < 15) continue
            out.add(c)
        }
        return out
    }

    private fun body(instructions: String, parts: List<JSONObject>): JSONObject = JSONObject()
        .put(
            "systemInstruction",
            JSONObject().put(
                "parts",
                JSONArray().put(JSONObject().put("text", instructions.trim() + "\n\n" + FORMAT_RULES)),
            ),
        )
        .put(
            "contents",
            JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray(parts))),
        )
        .put(
            "generationConfig",
            JSONObject().put("temperature", 0.2).put("responseMimeType", "application/json"),
        )

    private fun parseClips(text: String): List<RawClip> {
        val arr = extractArray(text)
        val out = ArrayList<RawClip>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val s = parseTime(o.optString("start")) ?: continue
            val e = parseTime(o.optString("end")) ?: continue
            val title = o.optString("title").replace("*", "").trim().ifBlank { "مقطع" }
            out.add(RawClip(s, e, title))
        }
        return out
    }

    private fun extractArray(text: String): JSONArray {
        val t = text.trim()
        try {
            return JSONArray(t)
        } catch (e: Exception) {
            // نكمل بالمحاولات التالية
        }
        val s = t.indexOf('[')
        val e = t.lastIndexOf(']')
        if (s in 0 until e) {
            try {
                return JSONArray(t.substring(s, e + 1))
            } catch (ex: Exception) {
                // نكمل
            }
        }
        try {
            val o = JSONObject(t)
            for (k in o.keys()) {
                val a = o.optJSONArray(k)
                if (a != null) return a
            }
        } catch (ex: Exception) {
            // نكمل
        }
        throw GeminiException(Kind.Other, "تعذّر فهم رد Gemini. أعد المحاولة.")
    }

    // ───────────────────────── الشبكة ─────────────────────────

    private fun generate(key: String, model: String, body: JSONObject): String {
        var attempt = 0
        while (true) {
            try {
                val req = Request.Builder()
                    .url("$BASE/models/$model:generateContent")
                    .header("x-goog-api-key", key)
                    .post(body.toString().toRequestBody(JSON_TYPE))
                    .build()
                return extractText(JSONObject(call(req)))
            } catch (e: GeminiException) {
                attempt++
                val retryable = e.kind == Kind.Server || e.kind == Kind.RateLimit
                if (!retryable || attempt >= 3) throw e
                Thread.sleep(8_000L * attempt)
            }
        }
    }

    private fun extractText(resp: JSONObject): String {
        val cands = resp.optJSONArray("candidates")
        if (cands == null || cands.length() == 0) {
            val reason = resp.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            throw GeminiException(
                Kind.Other,
                "لم يُرجع Gemini نتيجة" + if (reason.isNotEmpty()) " (السبب: $reason)." else ".",
            )
        }
        val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            ?: throw GeminiException(Kind.Other, "رد Gemini فارغ (ربما أوقفه مرشّح الأمان). جرّب نموذجاً آخر من الإعدادات.")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (p.optBoolean("thought", false)) continue
            sb.append(p.optString("text"))
        }
        return sb.toString()
    }

    private fun call(req: Request): String {
        val (code, text) = try {
            http.newCall(req).execute().use { it.code to it.body?.string().orEmpty() }
        } catch (e: IOException) {
            throw GeminiException(Kind.Network, "تعذّر الاتصال بخوادم Gemini. تحقق من الإنترنت وأعد المحاولة.")
        }
        if (code !in 200..299) throw httpError(code, text)
        return text
    }

    private fun httpError(code: Int, body: String): GeminiException {
        val msg = try {
            JSONObject(body).getJSONObject("error").getString("message")
        } catch (e: Exception) {
            body.take(200)
        }
        val lower = msg.lowercase()
        return when {
            code == 401 || ("api key" in lower && code in 400..403) ->
                GeminiException(Kind.InvalidKey, "مفتاح API غير صالح.")
            code == 429 ->
                GeminiException(Kind.RateLimit, "تجاوزت حد الاستخدام المسموح في Gemini. انتظر دقيقة ثم أعد المحاولة.")
            code == 404 ->
                GeminiException(Kind.ModelNotFound, "النموذج غير متاح لهذا المفتاح.")
            code >= 500 ->
                GeminiException(Kind.Server, "خدمة Gemini مشغولة حالياً (خطأ $code).")
            else ->
                GeminiException(Kind.Other, "خطأ من Gemini ($code): ${msg.take(200)}")
        }
    }
}
