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
                        val blocks = visionText.textBlocks.map { block ->
                            GameTextBlock(
                                text = block.text,
                                boundingBox = block.boundingBox
                            )
                        }
                        recognizer.close()
                        if (continuation.isActive) {
                            continuation.resume(OcrResult(fullText = visionText.text, blocks = blocks), null)
                        }
                    }
                    .addOnFailureListener { e ->
                        recognizer.close()
                        // Fallback to Latin recognizer if Asian language model is unavailable
                        tryFallbackLatin(bitmap, continuation)
                    }
            } catch (e: Exception) {
                tryFallbackLatin(bitmap, continuation)
            }
        }

    private fun tryFallbackLatin(
        bitmap: Bitmap,
        continuation: kotlinx.coroutines.CancellableContinuation<OcrResult>
    ) {
        try {
            val fallbackRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val fallbackImage = InputImage.fromBitmap(bitmap, 0)
            fallbackRecognizer.process(fallbackImage)
                .addOnSuccessListener { visionText: Text ->
                    val blocks = visionText.textBlocks.map { block ->
                        GameTextBlock(
                            text = block.text,
                            boundingBox = block.boundingBox
                        )
                    }
                    fallbackRecognizer.close()
                    if (continuation.isActive) {
                        continuation.resume(OcrResult(fullText = visionText.text, blocks = blocks), null)
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
}
