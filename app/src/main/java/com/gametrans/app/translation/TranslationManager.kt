package com.gametrans.app.translation

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.gametrans.app.ocr.GameTextBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class TranslationManager(context: Context) {
    private val cache = TranslationCache(context)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    // Regex to protect game variables and formatting tags
    private val tagPattern = Pattern.compile(
        "(\\\\[A-Za-z]+\\[\\d+\\]|\\{[^\\}]+\\}|\\[[A-Za-z0-9_]+\\]|%[0-9]*\\.?[0-9]*[sdif]|<[^>]+>|\\\\n|\\\\t)"
    )

    suspend fun translateBlocks(
        blocks: List<GameTextBlock>,
        sourceLang: String = "ja",
        targetLang: String = "id"
    ): List<Pair<GameTextBlock, String>> = withContext(Dispatchers.IO) {
        if (blocks.isEmpty()) return@withContext emptyList()

        val validBlocks = blocks.filter {
            it.text.isNotBlank() &&
            it.boundingBox != null &&
            it.boundingBox.width() > 10 &&
            it.boundingBox.height() > 8
        }

        coroutineScope {
            validBlocks.map { block ->
                async {
                    val translated = translate(block.text, sourceLang, targetLang)
                    Pair(block, translated)
                }
            }.awaitAll()
        }
    }

    suspend fun translate(
        text: String,
        sourceLang: String = "ja",
        targetLang: String = "id"
    ): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext text

        val trimmed = text.trim()
        // Check cache first
        val cached = cache.get(trimmed, sourceLang, targetLang)
        if (cached != null) return@withContext cached

        // 1. Mask tags
        val (maskedText, tags) = maskTags(trimmed)

        // 2. Try online Google Translate endpoint
        var translated = translateOnline(maskedText, sourceLang, targetLang)

        // 3. If online fails or no internet, fallback to ML Kit On-Device Translate
        if (translated == null) {
            translated = translateOfflineMLKit(maskedText, sourceLang, targetLang)
        }

        // 4. If all fail, return original
        var finalResult = translated?.let { unmaskTags(it, tags) } ?: trimmed
        if (targetLang == "id") {
            finalResult = postProcessIndonesianText(finalResult)
        }

        // Save to cache
        cache.put(trimmed, sourceLang, targetLang, finalResult)

        return@withContext finalResult
    }

    private fun postProcessIndonesianText(text: String): String {
        var res = text.trim()
        // Fix space before punctuation: "Halo , apa kabar ?" -> "Halo, apa kabar?"
        res = res.replace(Regex("""\s+([,.:;!?])"""), "$1")
        // Fix missing space after punctuation: "Halo,apa" -> "Halo, apa"
        res = res.replace(Regex("""([,.:;!?])([A-Za-z0-9])"""), "$1 $2")
        // Capitalize first letter
        if (res.isNotEmpty() && res[0].isLowerCase()) {
            res = res.replaceFirstChar { it.uppercaseChar() }
        }
        return res
    }

    private fun maskTags(input: String): Pair<String, List<String>> {
        val tags = mutableListOf<String>()
        val matcher = tagPattern.matcher(input)
        val sb = StringBuffer()
        while (matcher.find()) {
            val tag = matcher.group()
            val placeholder = "⟦TAG${tags.size}⟧"
            tags.add(tag)
            matcher.appendReplacement(sb, placeholder)
        }
        matcher.appendTail(sb)
        return Pair(sb.toString(), tags)
    }

    private fun unmaskTags(input: String, tags: List<String>): String {
        var res = input
        for (i in tags.indices) {
            val orig = tags[i]
            res = res.replace("⟦TAG$i⟧", orig)
                .replace("⟦ TAG$i ⟧", orig)
                .replace("[[TAG$i]]", orig)
                .replace("TAG$i", orig)
        }
        return res
    }

    private fun translateOnline(text: String, src: String, tgt: String): String? {
        return try {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$src&tl=$tgt&dt=t&q=$encoded"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: return null
            val jsonArray = JsonParser.parseString(body).asJsonArray
            val sentences = jsonArray.get(0).asJsonArray

            val sb = StringBuilder()
            for (element in sentences) {
                val sentence = element.asJsonArray
                if (sentence.size() > 0 && !sentence.get(0).isJsonNull) {
                    val segment = sentence.get(0).asString
                    if (segment.isNotBlank()) {
                        if (sb.isNotEmpty() && !sb.endsWith(" ") && !segment.startsWith(" ")) {
                            sb.append(" ")
                        }
                        sb.append(segment.trim())
                    }
                }
            }
            val res = sb.toString().trim()
            if (res.isNotBlank()) res else null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun translateOfflineMLKit(text: String, src: String, tgt: String): String? =
        suspendCancellableCoroutine { continuation ->
            try {
                val srcMl = mapToMlKitLang(src)
                val tgtMl = mapToMlKitLang(tgt)

                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(srcMl)
                    .setTargetLanguage(tgtMl)
                    .build()

                val translator = Translation.getClient(options)
                translator.downloadModelIfNeeded()
                    .addOnSuccessListener {
                        translator.translate(text)
                            .addOnSuccessListener { result ->
                                translator.close()
                                if (continuation.isActive) {
                                    continuation.resume(result, null)
                                }
                            }
                            .addOnFailureListener {
                                translator.close()
                                if (continuation.isActive) {
                                    continuation.resume(null, null)
                                }
                            }
                    }
                    .addOnFailureListener {
                        translator.close()
                        if (continuation.isActive) {
                            continuation.resume(null, null)
                        }
                    }
            } catch (e: Exception) {
                if (continuation.isActive) {
                    continuation.resume(null, null)
                }
            }
        }

    private fun mapToMlKitLang(code: String): String {
        return when (code.lowercase()) {
            "ja" -> TranslateLanguage.JAPANESE
            "en" -> TranslateLanguage.ENGLISH
            "zh", "zh-cn" -> TranslateLanguage.CHINESE
            "ko" -> TranslateLanguage.KOREAN
            "id" -> TranslateLanguage.INDONESIAN
            "es" -> TranslateLanguage.SPANISH
            else -> TranslateLanguage.ENGLISH
        }
    }
}
