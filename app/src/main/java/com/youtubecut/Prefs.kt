package com.youtubecut

import android.content.Context

/** الإعدادات المحفوظة في تخزين التطبيق الخاص. مفتاح Gemini يُحفظ مرة واحدة ولا يُطلب بعدها. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("youtubecut", Context.MODE_PRIVATE)

    var apiKey: String?
        get() = sp.getString("api_key", null)?.takeIf { it.isNotBlank() }
        set(v) {
            sp.edit().putString("api_key", v).commit()
        }

    /** نموذج يختاره المستخدم يدوياً (null = تلقائي). */
    var model: String?
        get() = sp.getString("model", null)?.takeIf { it.isNotBlank() }
        set(v) {
            sp.edit().putString("model", v).apply()
        }

    var autoModel: String?
        get() = sp.getString("auto_model", null)?.takeIf { it.isNotBlank() }
        set(v) {
            sp.edit().putString("auto_model", v).apply()
        }

    var autoModelAt: Long
        get() = sp.getLong("auto_model_at", 0L)
        set(v) {
            sp.edit().putLong("auto_model_at", v).apply()
        }

    /** "720" | "480" | "360" | "audio" */
    var quality: String
        get() = sp.getString("quality", "720") ?: "720"
        set(v) {
            sp.edit().putString("quality", v).apply()
        }

    var marginStart: Int
        get() = sp.getInt("margin_start", 10)
        set(v) {
            sp.edit().putInt("margin_start", v).apply()
        }

    var marginEnd: Int
        get() = sp.getInt("margin_end", 30)
        set(v) {
            sp.edit().putInt("margin_end", v).apply()
        }

    var instructions: String
        get() = sp.getString("instructions", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_INSTRUCTIONS
        set(v) {
            sp.edit().putString("instructions", v).apply()
        }

    var lastUpdate: Long
        get() = sp.getLong("last_update", 0L)
        set(v) {
            sp.edit().putLong("last_update", v).apply()
        }
}
