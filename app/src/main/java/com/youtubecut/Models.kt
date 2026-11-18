package com.youtubecut

import java.util.Locale

data class VideoMeta(
    val id: String,
    val title: String,
    val durationSec: Int,
    val url: String,
    val lang: String?,
)

/** سطر من الترجمة النصية: ثانية البداية + النص. */
data class TLine(val sec: Int, val text: String)

/** مقطع مقترح كما أرجعه Gemini (بالثواني). */
data class RawClip(val start: Int, val end: Int, val title: String)

/** مقطع جاهز للعرض والتحميل. cutStart/cutEnd تشمل هوامش الأمان. */
data class ClipUi(
    val index: Int,
    val title: String,
    val start: Int,
    val end: Int,
    val cutStart: Int,
    val cutEnd: Int,
    val selected: Boolean = true,
)

/** HH:MM:SS (يُستخدم مع yt-dlp وفي نص Gemini). */
fun hms(sec: Int): String =
    String.format(Locale.ROOT, "%02d:%02d:%02d", sec / 3600, sec % 3600 / 60, sec % 60)

/** صيغة العرض: MM:SS أو H:MM:SS. */
fun fmt(sec: Int): String {
    val h = sec / 3600
    val m = sec % 3600 / 60
    val s = sec % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.ROOT, "%02d:%02d", m, s)
}

/** يقبل SS أو MM:SS أو HH:MM:SS (وبالأرقام العربية أيضاً). */
fun parseTime(raw: String): Int? {
    val ascii = raw.map { if (it in '\u0660'..'\u0669') '0' + (it - '\u0660') else it }.joinToString("")
    val clean = ascii.replace(Regex("[^0-9:.]"), "")
    val parts = clean.split(":").filter { it.isNotEmpty() }
    if (parts.isEmpty() || parts.size > 3) return null
    var total = 0.0
    for (p in parts) {
        val n = p.toDoubleOrNull() ?: return null
        total = total * 60 + n
    }
    return total.toInt()
}

/** يجبر النص على الاتجاه من اليسار لليمين داخل واجهة عربية (للأوقات). */
fun ltr(s: String): String = "\u2066$s\u2069"

private val ID_PATTERNS = listOf(
    Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
    Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
    Regex("""youtube\.com/(?:embed|live|shorts|v)/([A-Za-z0-9_-]{11})"""),
)

fun extractVideoId(text: String): String? =
    ID_PATTERNS.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1) }

/** اسم ملف/مجلد آمن (يحافظ على الحروف العربية). */
fun safeName(s: String, max: Int = 80): String {
    val cleaned = s
        .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim('.')
    return cleaned.take(max).trim()
}
