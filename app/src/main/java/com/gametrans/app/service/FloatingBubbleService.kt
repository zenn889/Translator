package com.gametrans.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.gametrans.app.MainActivity
import com.gametrans.app.R
import com.gametrans.app.ocr.GameOcrManager
import com.gametrans.app.translation.TranslationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class FloatingBubbleService : Service(), TextToSpeech.OnInitListener {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var windowManager: WindowManager

    // UI Views
    private var bubbleView: View? = null
    private var dialogView: View? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var dialogParams: WindowManager.LayoutParams

    // Managers
    private lateinit var screenCaptureManager: ScreenCaptureManager
    private lateinit var ocrManager: GameOcrManager
    private lateinit var translationManager: TranslationManager
    private var tts: TextToSpeech? = null

    // Preferences / State
    private var sourceLang = "ja"
    private var targetLang = "id"
    private var isTranslating = false

    companion object {
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_SOURCE_LANG = "extra_source_lang"
        const val EXTRA_TARGET_LANG = "extra_target_lang"
        const val CHANNEL_ID = "GameTransServiceChannel"
        const val NOTIFICATION_ID = 101
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        screenCaptureManager = ScreenCaptureManager(this)
        ocrManager = GameOcrManager()
        translationManager = TranslationManager(this)
        tts = TextToSpeech(this, this)

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        intent?.let {
            val resultCode = it.getIntExtra(EXTRA_RESULT_CODE, 0)
            val resultData = it.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            sourceLang = it.getStringExtra(EXTRA_SOURCE_LANG) ?: "ja"
            targetLang = it.getStringExtra(EXTRA_TARGET_LANG) ?: "id"

            if (resultCode != 0 && resultData != null) {
                screenCaptureManager.initProjection(resultCode, resultData)
            }
        }

        if (bubbleView == null) {
            initFloatingBubble()
        }

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_text)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_translate_bubble)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun initFloatingBubble() {
        bubbleView = LayoutInflater.from(this).inflate(R.layout.layout_floating_bubble, null)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 300
        }

        val badgeLang = bubbleView?.findViewById<TextView>(R.id.badgeLang)
        badgeLang?.text = targetLang.uppercase()

        // Draggable bubble logic
        bubbleView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isMoved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = bubbleParams.x
                        initialY = bubbleParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isMoved = true
                        }
                        bubbleParams.x = initialX + dx
                        bubbleParams.y = initialY + dy
                        windowManager.updateViewLayout(bubbleView, bubbleParams)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isMoved) {
                            onBubbleTapped()
                        }
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(bubbleView, bubbleParams)
    }

    private fun onBubbleTapped() {
        if (isTranslating) return
        isTranslating = true

        serviceScope.launch {
            try {
                // 1. Hide bubble briefly to avoid capturing it in screenshot
                bubbleView?.visibility = View.INVISIBLE
                delay(80)

                // 2. Capture screen bitmap
                val bitmap = screenCaptureManager.captureScreen()

                // 3. Restore bubble visibility
                bubbleView?.visibility = View.VISIBLE

                if (bitmap == null) {
                    Toast.makeText(this@FloatingBubbleService, "Gagal menangkap layar game", Toast.LENGTH_SHORT).show()
                    isTranslating = false
                    return@launch
                }

                // 4. Run OCR
                val ocrResult = ocrManager.recognizeText(bitmap, sourceLang)
                if (ocrResult.fullText.isBlank()) {
                    Toast.makeText(this@FloatingBubbleService, getString(R.string.no_text_found), Toast.LENGTH_SHORT).show()
                    isTranslating = false
                    return@launch
                }

                // 5. Show Dialog loading state
                showTranslationDialog(ocrResult.fullText, getString(R.string.translating))

                // 6. Translate text to Indonesian
                val translated = translationManager.translate(
                    text = ocrResult.fullText,
                    sourceLang = sourceLang,
                    targetLang = targetLang
                )

                // 7. Update dialog with translated result
                showTranslationDialog(ocrResult.fullText, translated)

            } catch (e: Exception) {
                Toast.makeText(this@FloatingBubbleService, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isTranslating = false
            }
        }
    }

    @SuppressLint("InflateParams")
    private fun showTranslationDialog(originalText: String, translatedText: String) {
        if (dialogView == null) {
            dialogView = LayoutInflater.from(this).inflate(R.layout.layout_translation_dialog, null)

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            dialogParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = 120
            }

            // Close button
            dialogView?.findViewById<ImageButton>(R.id.btnClose)?.setOnClickListener {
                dismissTranslationDialog()
            }

            // Copy button
            dialogView?.findViewById<ImageButton>(R.id.btnCopy)?.setOnClickListener {
                val tvTrans = dialogView?.findViewById<TextView>(R.id.tvTranslated)
                val textToCopy = tvTrans?.text?.toString() ?: ""
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("GameTrans", textToCopy)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
            }

            // Text-to-Speech button
            dialogView?.findViewById<ImageButton>(R.id.btnTts)?.setOnClickListener {
                val tvTrans = dialogView?.findViewById<TextView>(R.id.tvTranslated)
                val textToSpeak = tvTrans?.text?.toString() ?: ""
                if (textToSpeak.isNotBlank()) {
                    tts?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, "GameTransTTS")
                }
            }

            windowManager.addView(dialogView, dialogParams)
        }

        // Update texts
        dialogView?.findViewById<TextView>(R.id.tvOriginal)?.text = originalText
        dialogView?.findViewById<TextView>(R.id.tvTranslated)?.text = translatedText
    }

    private fun dismissTranslationDialog() {
        if (dialogView != null) {
            windowManager.removeView(dialogView)
            dialogView = null
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("id", "ID")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        dismissTranslationDialog()
        if (bubbleView != null) {
            windowManager.removeView(bubbleView)
            bubbleView = null
        }
        screenCaptureManager.release()
        tts?.stop()
        tts?.shutdown()
    }
}
