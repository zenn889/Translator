package com.gametrans.app.translation

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

class TranslationCache(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("gametrans_cache", Context.MODE_PRIVATE)
    private val memoryCache = LinkedHashMap<String, String>(100, 0.75f, true)

    private fun hashKey(text: String, src: String, tgt: String): String {
        val input = "$src->$tgt:${text.trim()}"
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun get(text: String, src: String, tgt: String): String? {
        val key = hashKey(text, src, tgt)
        // 1. Check memory cache
        memoryCache[key]?.let { return it }

        // 2. Check disk cache (SharedPreferences)
        val cached = prefs.getString(key, null)
        if (cached != null) {
            memoryCache[key] = cached
        }
        return cached
    }

    @Synchronized
    fun put(text: String, src: String, tgt: String, translated: String) {
        val key = hashKey(text, src, tgt)
        memoryCache[key] = translated
        prefs.edit().putString(key, translated).apply()
    }
}
