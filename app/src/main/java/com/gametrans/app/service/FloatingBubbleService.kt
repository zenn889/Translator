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
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.gametrans.app.MainActivity
import com.gametrans.app.R
import com.gametrans.app.ocr.GameOcrManager
import com.gametrans.app.ocr.GameTextBlock
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
    private var inPlaceOverlayView: View? = null

    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var dialogParams: WindowManager.LayoutParams
    private lateinit var removeTargetParams: WindowManager.LayoutParams
    private lateinit var inPlaceOverlayParams: WindowManager.LayoutParams

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
                // 1. Hide bubble and previous overlays briefly to avoid capturing in screenshot
                bubbleView?.visibility = View.INVISIBLE
                dismissInPlaceOverlay()
                dismissTranslationDialog()
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
                if (ocrResult.fullText.isBlank() || ocrResult.blocks.isEmpty()) {
                    Toast.makeText(this@FloatingBubbleService, getString(R.string.no_text_found), Toast.LENGTH_SHORT).show()
                    isTranslating = false
                    setBubbleLoading(false)
                    return@launch
                }

                val prefs = getSharedPreferences("gametrans_prefs", Context.MODE_PRIVATE)
                val displayMode = prefs.getString("display_mode", "inplace") ?: "inplace"

                if (displayMode == "subtitle") {
                    showTranslationDialog(ocrResult.fullText, getString(R.string.translating))
                    val translated = translationManager.translate(
                        text = ocrResult.fullText,
                        sourceLang = sourceLang,
                        targetLang = targetLang
                    )
                    showTranslationDialog(ocrResult.fullText, translated)
                } else {
                    // In-Place Mode: Nimpa teks asli langsung di atas layar game!
                    val translatedBlocks = translationManager.translateBlocks(
                        blocks = ocrResult.blocks,
                        sourceLang = sourceLang,
                        targetLang = targetLang
                    )
                    val fullTranslated = translatedBlocks.joinToString("\n") { it.second }
                    showInPlaceOverlay(
                        translatedBlocks = translatedBlocks,
                        fullOriginalText = ocrResult.fullText,
                        fullTranslatedText = fullTranslated
                    )
                }

            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@FloatingBubbleService, "Gagal menerjemahkan: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isTranslating = false
                setBubbleLoading(false)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showInPlaceOverlay(
        translatedBlocks: List<Pair<GameTextBlock, String>>,
        fullOriginalText: String,
        fullTranslatedText: String
    ) {
        dismissInPlaceOverlay()
        dismissTranslationDialog()

        try {
            inPlaceOverlayView = LayoutInflater.from(this).inflate(R.layout.layout_inplace_overlay, null)

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            inPlaceOverlayParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            val overlayRoot = inPlaceOverlayView?.findViewById<FrameLayout>(R.id.overlayRoot)
            val cardsContainer = inPlaceOverlayView?.findViewById<FrameLayout>(R.id.overlayCardsContainer)
            val tvLang = inPlaceOverlayView?.findViewById<TextView>(R.id.tvOverlayLang)
            val btnTts = inPlaceOverlayView?.findViewById<ImageButton>(R.id.btnOverlayTts)
            val btnSwitch = inPlaceOverlayView?.findViewById<ImageButton>(R.id.btnSwitchToSubtitle)
            val btnClose = inPlaceOverlayView?.findViewById<ImageButton>(R.id.btnCloseOverlay)

            tvLang?.text = "${sourceLang.uppercase()} ➔ ${targetLang.uppercase()}"

            // Tapping background closes the overlay so the gamer can resume playing immediately
            overlayRoot?.setOnClickListener {
                dismissInPlaceOverlay()
            }

            btnSwitch?.setOnClickListener {
                dismissInPlaceOverlay()
                showTranslationDialog(fullOriginalText, fullTranslatedText)
            }

            btnClose?.setOnClickListener {
                dismissInPlaceOverlay()
            }

            btnTts?.setOnClickListener {
                val toSpeak = fullTranslatedText.ifBlank {
                    translatedBlocks.joinToString(". ") { it.second }
                }
                if (toSpeak.isNotBlank()) {
                    try {
                        tts?.speak(toSpeak, TextToSpeech.QUEUE_FLUSH, null, "GameTransTTS")
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            val density = resources.displayMetrics.density
            val curMetrics = resources.displayMetrics
            val curWidth = curMetrics.widthPixels
            val curHeight = curMetrics.heightPixels

            val prefs = getSharedPreferences("gametrans_prefs", Context.MODE_PRIVATE)
            val overlayOpacity = prefs.getInt("overlay_opacity", 45).coerceIn(0, 100)

            for (pair in translatedBlocks) {
                val block = pair.first
                val translated = pair.second
                val rect = block.boundingBox ?: continue
                if (translated.isBlank()) continue

                val isWideDialogue = (rect.width() / density) > 170

                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    if (overlayOpacity <= 0) {
                        // 100% Fully Transparent - No background card (like Bubble Translate Video Subtitle mode)
                        background = null
                        elevation = 0f
                    } else {
                        // Translucent glass pill (like Bubble Translate Manga / Game mode)
                        val alpha = (overlayOpacity * 255 / 100).coerceIn(0, 255)
                        val bgColor = Color.argb(alpha, 15, 23, 42) // Slate dark glass with user's selected opacity
                        val pillShape = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = 8 * density
                            setColor(bgColor)
                        }
                        background = pillShape
                        elevation = if (overlayOpacity > 20) 6 * density else 0f
                    }
                    val padH = (8 * density).toInt()
                    val padV = (4 * density).toInt()
                    setPadding(padH, padV, padH, padV)
                    setOnClickListener {
                        dismissInPlaceOverlay()
                    }
                    setOnLongClickListener {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("GameTrans", translated)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(this@FloatingBubbleService, "Teks disalin: $translated", Toast.LENGTH_SHORT).show()
                        try {
                            tts?.speak(translated, TextToSpeech.QUEUE_FLUSH, null, "GameTransTTS")
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        true
                    }
                }

                // Available width on screen (dynamically adapted for portrait and landscape)
                val marginStart = (rect.left - (4 * density).toInt()).coerceIn(8, curWidth - 80)
                val maxAllowedWidth = (curWidth - marginStart - 12).coerceAtLeast((100 * density).toInt())
                val targetMinWidth = Math.min(rect.width() + (8 * density).toInt(), maxAllowedWidth)

                val tv = TextView(this).apply {
                    text = translated
                    if (overlayOpacity <= 0) {
                        setTextColor(Color.parseColor("#FDE047")) // Bright yellow for zero-bg mode
                        setShadowLayer(8f, 0f, 2f, Color.BLACK)
                    } else {
                        setTextColor(Color.WHITE) // Crisp pure white like Bubble Translate
                        setShadowLayer(6f, 0f, 2f, Color.BLACK)
                    }
                    setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
                    gravity = if (isWideDialogue) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
                    maxWidth = maxAllowedWidth - (16 * density).toInt()
                    setLineSpacing(2.5f * density, 1.15f)

                    val heightDp = rect.height() / density
                    textSize = when {
                        heightDp >= 65 -> 15.5f
                        heightDp >= 42 -> 14f
                        heightDp >= 26 -> 13f
                        else -> 11.5f
                    }
                }
                card.addView(tv)

                card.minimumWidth = targetMinWidth
                card.minimumHeight = rect.height().coerceAtLeast((22 * density).toInt())

                val marginTop = (rect.top - (2 * density).toInt()).coerceIn(8, curHeight - 40)

                val lp = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = marginStart
                    topMargin = marginTop
                }

                cardsContainer?.addView(card, lp)
            }

            windowManager.addView(inPlaceOverlayView, inPlaceOverlayParams)

        } catch (e: Exception) {
            e.printStackTrace()
            showTranslationDialog(fullOriginalText, fullTranslatedText)
        }
    }

    private fun dismissInPlaceOverlay() {
        try {
            if (inPlaceOverlayView != null && inPlaceOverlayView?.isAttachedToWindow == true) {
                windowManager.removeView(inPlaceOverlayView)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            inPlaceOverlayView = null
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
        dismissInPlaceOverlay()
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
