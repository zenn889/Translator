package com.gametrans.app.service

import android.animation.ValueAnimator
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
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
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
import java.util.Locale

class FloatingBubbleService : Service(), TextToSpeech.OnInitListener {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var windowManager: WindowManager

    // UI Views
    private var bubbleView: View? = null
    private var dialogView: View? = null
    private var removeTargetView: View? = null

    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var dialogParams: WindowManager.LayoutParams
    private lateinit var removeTargetParams: WindowManager.LayoutParams

    // Screen Dimensions
    private var screenWidth = 1080
    private var screenHeight = 1920

    // Managers
    private lateinit var screenCaptureManager: ScreenCaptureManager
    private lateinit var ocrManager: GameOcrManager
    private lateinit var translationManager: TranslationManager
    private var tts: TextToSpeech? = null

    // Preferences / State
    private var sourceLang = "ja"
    private var targetLang = "id"
    private var isTranslating = false
    private var currentFontSizeSp = 20f

    companion object {
        var isRunning = false
        const val ACTION_STOP_SERVICE = "action_stop_service"
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
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        updateScreenDimensions()

        screenCaptureManager = ScreenCaptureManager(this)
        ocrManager = GameOcrManager()
        translationManager = TranslationManager(this)
        try {
            tts = TextToSpeech(this, this)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        createNotificationChannel()
    }

    private fun updateScreenDimensions() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle stop action from notification
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = createNotification()

        // Required for Android 10+ and Android 14+ MediaProjection
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        intent?.let {
            val resultCode = it.getIntExtra(EXTRA_RESULT_CODE, 0)
            val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                it.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            }
            sourceLang = it.getStringExtra(EXTRA_SOURCE_LANG) ?: "ja"
            targetLang = it.getStringExtra(EXTRA_TARGET_LANG) ?: "id"

            if (resultCode != 0 && resultData != null) {
                screenCaptureManager.initProjection(resultCode, resultData)
            }
        }

        if (bubbleView == null) {
            initFloatingBubble()
            initRemoveTarget()
        }

        return START_NOT_STICKY
    }

    /**
     * FIX: When the user closes or swipes away MainActivity from Recent Apps,
     * automatically stop this service and remove the floating bubble cleanly!
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val prefs = getSharedPreferences("gametrans_prefs", Context.MODE_PRIVATE)
        val stopOnExit = prefs.getBoolean("auto_stop_on_exit", true)
        if (stopOnExit) {
            stopSelf()
        }
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
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FloatingBubbleService::class.java).apply {
                action = ACTION_STOP_SERVICE
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🎮 GameTrans Aktif")
            .setContentText("Ketuk gelembung di layar untuk menerjemahkan game.")
            .setSmallIcon(R.drawable.ic_translate_bubble)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_close, "Hentikan", stopIntent)
            .setOngoing(true)
            .build()
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun initFloatingBubble() {
        try {
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
                x = 24
                y = screenHeight / 3
            }

            val badgeLang = bubbleView?.findViewById<TextView>(R.id.badgeLang)
            badgeLang?.text = targetLang.uppercase()

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
                            showRemoveTarget(true)
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (event.rawX - initialTouchX).toInt()
                            val dy = (event.rawY - initialTouchY).toInt()

                            if (Math.abs(dx) > 12 || Math.abs(dy) > 12) {
                                isMoved = true
                            }

                            bubbleParams.x = initialX + dx
                            bubbleParams.y = initialY + dy

                            try {
                                windowManager.updateViewLayout(bubbleView, bubbleParams)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }

                            // Check if hovering over remove target at the bottom
                            checkOverRemoveTarget(event.rawX, event.rawY)
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            showRemoveTarget(false)

                            // Check if dropped into remove target
                            if (isOverRemoveTarget(event.rawX, event.rawY)) {
                                Toast.makeText(this@FloatingBubbleService, "Layanan GameTrans ditutup", Toast.LENGTH_SHORT).show()
                                stopSelf()
                                return true
                            }

                            if (!isMoved) {
                                onBubbleTapped()
                            } else {
                                snapBubbleToNearestEdge()
                            }
                            return true
                        }
                    }
                    return false
                }
            })

            windowManager.addView(bubbleView, bubbleParams)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Izin overlay belum aktif: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("InflateParams")
    private fun initRemoveTarget() {
        try {
            removeTargetView = LayoutInflater.from(this).inflate(R.layout.layout_remove_bubble, null)

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            removeTargetParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = 60
            }

            removeTargetView?.visibility = View.GONE
            windowManager.addView(removeTargetView, removeTargetParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showRemoveTarget(show: Boolean) {
        removeTargetView?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun isOverRemoveTarget(rawX: Float, rawY: Float): Boolean {
        val targetCenterX = screenWidth / 2f
        val targetCenterY = screenHeight - 140f
        val distance = Math.hypot((rawX - targetCenterX).toDouble(), (rawY - targetCenterY).toDouble())
        return distance < 150
    }

    private fun checkOverRemoveTarget(rawX: Float, rawY: Float) {
        val isOver = isOverRemoveTarget(rawX, rawY)
        val targetIcon = removeTargetView?.findViewById<ImageView>(R.id.removeTargetIcon)
        targetIcon?.scaleX = if (isOver) 1.25f else 1.0f
        targetIcon?.scaleY = if (isOver) 1.25f else 1.0f
    }

    private fun snapBubbleToNearestEdge() {
        updateScreenDimensions()
        val currentX = bubbleParams.x
        val targetX = if (currentX + 60 < screenWidth / 2) 20 else screenWidth - 160

        val animator = ValueAnimator.ofInt(currentX, targetX)
        animator.interpolator = DecelerateInterpolator()
        animator.duration = 220
        animator.addUpdateListener { anim ->
            bubbleParams.x = anim.animatedValue as Int
            try {
                if (bubbleView?.isAttachedToWindow == true) {
                    windowManager.updateViewLayout(bubbleView, bubbleParams)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        animator.start()
    }

    private fun setBubbleLoading(loading: Boolean) {
        val progressBar = bubbleView?.findViewById<ProgressBar>(R.id.bubbleLoading)
        val icon = bubbleView?.findViewById<ImageView>(R.id.bubbleIcon)
        progressBar?.visibility = if (loading) View.VISIBLE else View.GONE
        icon?.visibility = if (loading) View.INVISIBLE else View.VISIBLE
    }

    private fun onBubbleTapped() {
        if (isTranslating) return
        isTranslating = true
        setBubbleLoading(true)

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
                    Toast.makeText(this@FloatingBubbleService, "Menunggu frame layar game...", Toast.LENGTH_SHORT).show()
                    isTranslating = false
                    setBubbleLoading(false)
                    return@launch
                }

                // 4. Run OCR
                val ocrResult = ocrManager.recognizeText(bitmap, sourceLang)
                if (ocrResult.fullText.isBlank()) {
                    Toast.makeText(this@FloatingBubbleService, getString(R.string.no_text_found), Toast.LENGTH_SHORT).show()
                    isTranslating = false
                    setBubbleLoading(false)
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
                e.printStackTrace()
                Toast.makeText(this@FloatingBubbleService, "Gagal menerjemahkan: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isTranslating = false
                setBubbleLoading(false)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun showTranslationDialog(originalText: String, translatedText: String) {
        try {
            if (dialogView != null && dialogView?.isAttachedToWindow == true) {
                dialogView?.findViewById<TextView>(R.id.tvOriginal)?.text = originalText
                dialogView?.findViewById<TextView>(R.id.tvTranslated)?.text = translatedText
                return
            }

            dismissTranslationDialog()

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
                y = 100
            }

            val tvOriginal = dialogView?.findViewById<TextView>(R.id.tvOriginal)
            val tvTranslated = dialogView?.findViewById<TextView>(R.id.tvTranslated)
            val tvLangBadge = dialogView?.findViewById<TextView>(R.id.tvLangPairBadge)

            tvOriginal?.text = originalText
            tvTranslated?.text = translatedText
            tvTranslated?.textSize = currentFontSizeSp
            tvLangBadge?.text = "${sourceLang.uppercase()} ➔ ${targetLang.uppercase()}"

            // Draggable Dialog Handle
            val dragHandle = dialogView?.findViewById<View>(R.id.dialogDragHandle)
            dragHandle?.setOnTouchListener(object : View.OnTouchListener {
                private var initialY = 0
                private var initialTouchY = 0f

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialY = dialogParams.y
                            initialTouchY = event.rawY
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dy = (initialTouchY - event.rawY).toInt()
                            dialogParams.y = Math.max(20, Math.min(initialY + dy, screenHeight - 300))
                            try {
                                windowManager.updateViewLayout(dialogView, dialogParams)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            return true
                        }
                    }
                    return false
                }
            })

            // Font size cycle button (18sp -> 22sp -> 26sp)
            dialogView?.findViewById<ImageButton>(R.id.btnFontSize)?.setOnClickListener {
                currentFontSizeSp = when (currentFontSizeSp) {
                    18f -> 22f
                    22f -> 26f
                    else -> 18f
                }
                tvTranslated?.textSize = currentFontSizeSp
                val label = when (currentFontSizeSp) {
                    18f -> "Ukuran: Normal"
                    22f -> "Ukuran: Besar"
                    else -> "Ukuran: Ekstra Besar"
                }
                Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
            }

            // Close button
            dialogView?.findViewById<ImageButton>(R.id.btnClose)?.setOnClickListener {
                dismissTranslationDialog()
            }

            // Copy button
            dialogView?.findViewById<ImageButton>(R.id.btnCopy)?.setOnClickListener {
                val textToCopy = tvTranslated?.text?.toString() ?: ""
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("GameTrans", textToCopy)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
            }

            // Text-to-Speech button
            dialogView?.findViewById<ImageButton>(R.id.btnTts)?.setOnClickListener {
                val textToSpeak = tvTranslated?.text?.toString() ?: ""
                if (textToSpeak.isNotBlank()) {
                    try {
                        tts?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, "GameTransTTS")
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            windowManager.addView(dialogView, dialogParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun dismissTranslationDialog() {
        try {
            if (dialogView != null && dialogView?.isAttachedToWindow == true) {
                windowManager.removeView(dialogView)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            dialogView = null
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            try {
                tts?.language = Locale("id", "ID")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceScope.cancel()
        dismissTranslationDialog()

        try {
            if (removeTargetView != null && removeTargetView?.isAttachedToWindow == true) {
                windowManager.removeView(removeTargetView)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            removeTargetView = null
        }

        try {
            if (bubbleView != null && bubbleView?.isAttachedToWindow == true) {
                windowManager.removeView(bubbleView)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            bubbleView = null
        }

        screenCaptureManager.release()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
