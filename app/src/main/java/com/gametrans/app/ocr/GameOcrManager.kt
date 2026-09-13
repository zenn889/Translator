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
import kotlin.coroutines.resumeWithException

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
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }
        }
}
