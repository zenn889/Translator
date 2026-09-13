package com.gametrans.app.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine

data class GameTextBlock(
    val text: String,
    val boundingBox: Rect?
)

data class OcrResult(
    val fullText: String,
    val blocks: List<GameTextBlock>
)

class GameOcrManager {

    suspend fun recognizeText(bitmap: Bitmap, lang: String = "ja"): OcrResult =
        suspendCancellableCoroutine { continuation ->
            try {
                val recognizer = when (lang.lowercase()) {
                    "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                    "zh", "zh-cn" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                    "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                    else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                }

                val image = InputImage.fromBitmap(bitmap, 0)
                recognizer.process(image)
                    .addOnSuccessListener { visionText: Text ->
                        val result = processVisionText(visionText, lang)
                        recognizer.close()
                        if (continuation.isActive) {
                            continuation.resume(result, null)
                        }
                    }
                    .addOnFailureListener {
                        recognizer.close()
                        tryFallbackLatin(bitmap, lang, continuation)
                    }
            } catch (e: Exception) {
                tryFallbackLatin(bitmap, lang, continuation)
            }
        }

    private fun tryFallbackLatin(
        bitmap: Bitmap,
        lang: String,
        continuation: kotlinx.coroutines.CancellableContinuation<OcrResult>
    ) {
        try {
            val fallbackRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val fallbackImage = InputImage.fromBitmap(bitmap, 0)
            fallbackRecognizer.process(fallbackImage)
                .addOnSuccessListener { visionText: Text ->
                    val result = processVisionText(visionText, lang)
                    fallbackRecognizer.close()
                    if (continuation.isActive) {
                        continuation.resume(result, null)
                    }
                }
                .addOnFailureListener {
                    fallbackRecognizer.close()
                    if (continuation.isActive) {
                        continuation.resume(OcrResult(fullText = "", blocks = emptyList()), null)
                    }
                }
        } catch (e: Exception) {
            if (continuation.isActive) {
                continuation.resume(OcrResult(fullText = "", blocks = emptyList()), null)
            }
        }
    }

    private fun processVisionText(visionText: Text, lang: String): OcrResult {
        val rawBlocks = visionText.textBlocks.mapNotNull { block ->
            val cleaned = cleanGameText(block.text, lang)
            if (cleaned.isNotBlank() && block.boundingBox != null) {
                GameTextBlock(text = cleaned, boundingBox = Rect(block.boundingBox!!))
            } else null
        }

        val mergedBlocks = mergeNearbyBlocks(rawBlocks, lang)
        val separator = if (lang.lowercase() in listOf("ja", "zh", "zh-cn")) "" else " "
        val fullText = mergedBlocks.joinToString(separator) { it.text }

        return OcrResult(fullText = fullText, blocks = mergedBlocks)
    }

    private fun cleanGameText(text: String, lang: String): String {
        if (text.isBlank()) return ""
        var cleaned = text.trim()

        // Strip stray OCR edge artifacts
        cleaned = cleaned.replace(Regex("""^[|_\-~.`'\s]+"""), "")
        cleaned = cleaned.replace(Regex("""[|_\-~.`'\s]+$"""), "")

        when (lang.lowercase()) {
            "ja" -> {
                // Strip newlines inside dialogue unless preceded by sentence boundary
                cleaned = cleaned.replace(Regex("""(?<![。！？\n])\n(?![。！？\n])"""), "")
                // Remove spaces between Japanese characters
                cleaned = cleaned.replace(Regex("""(?<=[\u3040-\u30ff\u4e00-\u9fa5])\s+(?=[\u3040-\u30ff\u4e00-\u9fa5])"""), "")
            }
            "zh", "zh-cn" -> {
                cleaned = cleaned.replace(Regex("""(?<![。！？\n])\n(?![。！？\n])"""), "")
                cleaned = cleaned.replace(Regex("""(?<=[\u4e00-\u9fa5])\s+(?=[\u4e00-\u9fa5])"""), "")
            }
            "en" -> {
                // Fix hyphenated wrapped words
                cleaned = cleaned.replace(Regex("""(\w+)-\s*\n\s*(\w+)"""), "$1$2")
                cleaned = cleaned.replace(Regex("""(?<!\n)\n(?!\n)"""), " ")
                cleaned = cleaned.replace(Regex("""\s{2,}"""), " ")
            }
            "ko" -> {
                cleaned = cleaned.replace(Regex("""(?<!\n)\n(?!\n)"""), " ")
                cleaned = cleaned.replace(Regex("""\s{2,}"""), " ")
            }
        }

        return cleaned.trim()
    }

    private fun mergeNearbyBlocks(blocks: List<GameTextBlock>, lang: String): List<GameTextBlock> {
        if (blocks.size <= 1) return blocks

        val valid = blocks.filter {
            it.text.isNotBlank() &&
            it.boundingBox != null &&
            it.boundingBox.width() > 12 &&
            it.boundingBox.height() > 8
        }.sortedWith(compareBy({ it.boundingBox!!.top }, { it.boundingBox!!.left }))

        if (valid.isEmpty()) return emptyList()

        val merged = mutableListOf<GameTextBlock>()
        val visited = BooleanArray(valid.size)
        val isAsian = lang.lowercase() in listOf("ja", "zh", "zh-cn")

        for (i in valid.indices) {
            if (visited[i]) continue

            var currentText = valid[i].text
            val currentRect = Rect(valid[i].boundingBox!!)
            visited[i] = true

            for (j in i + 1 until valid.size) {
                if (visited[j]) continue

                val other = valid[j]
                val otherRect = other.boundingBox!!

                // Determine if other block belongs to the same visual text box / dialogue
                val lineHeight = currentRect.height().coerceIn(16, 80)
                val verticalDist = otherRect.top - currentRect.bottom
                val horizontalOverlap = Math.max(0, Math.min(currentRect.right, otherRect.right) - Math.max(currentRect.left, otherRect.left))
                val isHorizontallyClose = horizontalOverlap > 0 || Math.abs(currentRect.left - otherRect.left) < 120

                // If vertically close (within 1.5 lines) and horizontally aligned
                if (verticalDist in -10..(lineHeight * 2) && isHorizontallyClose) {
                    currentText = if (isAsian) {
                        currentText.trimEnd() + other.text.trimStart()
                    } else {
                        currentText.trimEnd() + " " + other.text.trimStart()
                    }
                    currentRect.union(otherRect)
                    visited[j] = true
                }
            }

            merged.add(GameTextBlock(currentText, currentRect))
        }

        return merged
    }
}
