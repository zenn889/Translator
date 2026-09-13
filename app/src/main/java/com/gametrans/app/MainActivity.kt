package com.gametrans.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.gametrans.app.service.FloatingBubbleService
import com.gametrans.app.ui.theme.*

class MainActivity : ComponentActivity() {

    private var isServiceRunning by mutableStateOf(false)
    private var hasOverlayPermission by mutableStateOf(false)
    private var sourceLanguage by mutableStateOf("ja")
    private var targetLanguage by mutableStateOf("id")
    private var autoStopOnExit by mutableStateOf(true)
    private var displayMode by mutableStateOf("inplace")
    private var overlayOpacity by mutableStateOf(45)

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startFloatingService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Izin tangkapan layar diperlukan untuk menerjemahkan game.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("gametrans_prefs", Context.MODE_PRIVATE)
        autoStopOnExit = prefs.getBoolean("auto_stop_on_exit", true)
        sourceLanguage = prefs.getString("source_lang", "ja") ?: "ja"
        displayMode = prefs.getString("display_mode", "inplace") ?: "inplace"
        overlayOpacity = prefs.getInt("overlay_opacity", 45).coerceIn(0, 100)

        checkPermissions()
        isServiceRunning = FloatingBubbleService.isRunning

        setContent {
            GameTransTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    MainScreen(
                        isServiceRunning = isServiceRunning,
                        hasOverlayPermission = hasOverlayPermission,
                        sourceLang = sourceLanguage,
                        autoStopOnExit = autoStopOnExit,
                        displayMode = displayMode,
                        overlayOpacity = overlayOpacity,
                        onSourceLangChange = {
                            sourceLanguage = it
                            getSharedPreferences("gametrans_prefs", Context.MODE_PRIVATE)
                                .edit()
                                .putString("source_lang", it)
                                .apply()
                        },
                        onAutoStopChange = {
                            autoStopOnExit = it
                            getSharedPreferences("gametrans_prefs", Context.MODE_PRIVATE)
                                .edit()
                                .putBoolean("auto_stop_on_exit", it)
                                .apply()
                        },
                        onDisplayModeChange = {
                            displayMode = it
                            getSharedPreferences("gametrans_prefs", Context.MODE_PRIVATE)
                                .edit()
                                .putString("display_mode", it)
                                .apply()
                        },
                        onOverlayOpacityChange = {
                            overlayOpacity = it
                            getSharedPreferences("gametrans_prefs", Context.MODE_PRIVATE)
                                .edit()
                                .putInt("overlay_opacity", it)
                                .apply()
                        },
                        onRequestOverlayPermission = { requestOverlayPermission() },
                        onToggleService = { toggleService() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
        isServiceRunning = FloatingBubbleService.isRunning
    }

    override fun onDestroy() {
        super.onDestroy()
        val prefs = getSharedPreferences("gametrans_prefs", Context.MODE_PRIVATE)
        val stopOnExit = prefs.getBoolean("auto_stop_on_exit", true)
        if (isFinishing && stopOnExit) {
            stopFloatingService()
        }
    }

    private fun checkPermissions() {
        hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun toggleService() {
        if (isServiceRunning) {
            stopFloatingService()
        } else {
            if (!hasOverlayPermission) {
                requestOverlayPermission()
                return
            }
            val mediaProjectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }
    }

    private fun startFloatingService(resultCode: Int, resultData: Intent) {
        val serviceIntent = Intent(this, FloatingBubbleService::class.java).apply {
            putExtra(FloatingBubbleService.EXTRA_RESULT_CODE, resultCode)
            putExtra(FloatingBubbleService.EXTRA_RESULT_DATA, resultData)
            putExtra(FloatingBubbleService.EXTRA_SOURCE_LANG, sourceLanguage)
            putExtra(FloatingBubbleService.EXTRA_TARGET_LANG, targetLanguage)
        }

        ContextCompat.startForegroundService(this, serviceIntent)
        isServiceRunning = true
        Toast.makeText(this, "Gelembung aktif! Silakan buka game Anda.", Toast.LENGTH_SHORT).show()
    }

    private fun stopFloatingService() {
        val serviceIntent = Intent(this, FloatingBubbleService::class.java)
        stopService(serviceIntent)
        isServiceRunning = false
        Toast.makeText(this, "Layanan gelembung dihentikan.", Toast.LENGTH_SHORT).show()
    }
}

data class LangItem(val code: String, val flag: String, val name: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    isServiceRunning: Boolean,
    hasOverlayPermission: Boolean,
    sourceLang: String,
    autoStopOnExit: Boolean,
    displayMode: String,
    overlayOpacity: Int,
    onSourceLangChange: (String) -> Unit,
    onAutoStopChange: (Boolean) -> Unit,
    onDisplayModeChange: (String) -> Unit,
    onOverlayOpacityChange: (Int) -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onToggleService: () -> Unit
) {
    val languages = listOf(
        LangItem("ja", "🇯🇵", "Jepang"),
        LangItem("en", "🇺🇸", "Inggris"),
        LangItem("zh", "🇨🇳", "Mandarin"),
        LangItem("ko", "🇰🇷", "Korea")
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Simple Clean Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(CyberBlue),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Translate,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "GameTrans",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF1E293B))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "v1.0.6",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyberSky
                            )
                        }
                    }
                    Text(
                        text = "Penerjemah Layar Game Realtime",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Overlay Permission Warning (Only if not granted)
        if (!hasOverlayPermission) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF450A0A)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onRequestOverlayPermission() }
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFF87171))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Izin Menampilkan di Atas Layar Belum Aktif",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = Color(0xFFFCA5A5)
                        )
                        Text(
                            text = "Ketuk untuk mengaktifkan izin agar gelembung bisa muncul.",
                            fontSize = 11.sp,
                            color = Color(0xFFE2E8F0)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        // Hero Service Card with Start / Stop Button
        val statusBg by animateColorAsState(
            targetValue = if (isServiceRunning) Color(0xFF064E3B) else DarkSurface,
            animationSpec = tween(300),
            label = "bg"
        )
        val statusBorder by animateColorAsState(
            targetValue = if (isServiceRunning) Color(0xFF10B981) else Color(0x3338BDF8),
            animationSpec = tween(300),
            label = "border"
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = statusBg),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, statusBorder, RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isServiceRunning) Color(0xFF34D399) else Color(0xFF64748B))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isServiceRunning) "LAYANAN MELAYANG AKTIF" else "LAYANAN NONAKTIF",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isServiceRunning) Color(0xFFA7F3D0) else Color(0xFFCBD5E1)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Action Button
                val buttonColor = if (isServiceRunning) Color(0xFFDC2626) else Color(0xFF059669)
                Button(
                    onClick = onToggleService,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = buttonColor)
                ) {
                    Icon(
                        imageVector = if (isServiceRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isServiceRunning) "HENTIKAN GELEMBUNG" else "MUNCULKAN GELEMBUNG",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Language Setting (Simple Horizontal Row)
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "BAHASA ASAL GAME:",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = CyberSky
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    languages.forEach { item ->
                        val isSelected = sourceLang == item.code
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) CyberBlue else DarkSurfaceVariant)
                                .clickable { onSourceLangChange(item.code) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(item.flag, fontSize = 16.sp)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = item.name,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) Color.White else TextMuted
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkSurfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🇮🇩", fontSize = 16.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Diterjemahkan ke: ", fontSize = 12.sp, color = TextMuted)
                    Text("Bahasa Indonesia", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextLight)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Simple Settings (2 Clean Toggles)
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "PENGATURAN TAMPILAN & SISTEM:",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = CyberSky
                )
                Spacer(modifier = Modifier.height(10.dp))

                // Mode Selector: Nimpa Layar vs Kotak Subtitle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Gaya Terjemahan", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextLight)
                        Text(
                            text = if (displayMode == "inplace") "🖼️ Nimpa Transparan di Layar" else "📋 Kotak Subtitle di Bawah",
                            fontSize = 11.sp,
                            color = CyberSky
                        )
                    }
                    Row {
                        FilterChip(
                            selected = displayMode == "inplace",
                            onClick = { onDisplayModeChange("inplace") },
                            label = { Text("Nimpa", fontSize = 11.sp) }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        FilterChip(
                            selected = displayMode == "subtitle",
                            onClick = { onDisplayModeChange("subtitle") },
                            label = { Text("Kotak", fontSize = 11.sp) }
                        )
                    }
                }

                // Transparansi Terjemahan ala Bubble Translate
                if (displayMode == "inplace") {
                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = Color(0x1AFFFFFF))
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Transparansi Latar", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextLight)
                            Text(
                                text = when {
                                    overlayOpacity <= 0 -> "🪟 100% Transparan (Tanpa Kotak)"
                                    overlayOpacity <= 55 -> "🌫️ Kaca Transparan ($overlayOpacity%)"
                                    else -> "⬛ Kotak Gelap ($overlayOpacity%)"
                                },
                                fontSize = 11.sp,
                                color = CyberSky
                            )
                        }
                        Row {
                            FilterChip(
                                selected = overlayOpacity in 30..60,
                                onClick = { onOverlayOpacityChange(45) },
                                label = { Text("Kaca 45%", fontSize = 11.sp) }
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            FilterChip(
                                selected = overlayOpacity <= 0,
                                onClick = { onOverlayOpacityChange(0) },
                                label = { Text("0%", fontSize = 11.sp) }
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            FilterChip(
                                selected = overlayOpacity > 60,
                                onClick = { onOverlayOpacityChange(85) },
                                label = { Text("85%", fontSize = 11.sp) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Slider(
                        value = overlayOpacity.toFloat(),
                        onValueChange = { onOverlayOpacityChange(it.toInt()) },
                        valueRange = 0f..100f,
                        steps = 9,
                        colors = SliderDefaults.colors(
                            thumbColor = CyberSky,
                            activeTrackColor = CyberBlue,
                            inactiveTrackColor = Color(0x33FFFFFF)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = Color(0x1AFFFFFF))
                Spacer(modifier = Modifier.height(10.dp))

                // Auto stop switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Tutup Saat Aplikasi Keluar", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextLight)
                        Text("Gelembung otomatis berhenti saat GameTrans ditutup.", fontSize = 11.sp, color = TextMuted)
                    }
                    Switch(
                        checked = autoStopOnExit,
                        onCheckedChange = onAutoStopChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = CyberBlue
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Simple Tip Box
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(DarkSurfaceVariant)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("💡", fontSize = 16.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Buka game, ketuk gelembung 1x saat ada dialog. Ketuk layar di mana saja untuk menutup terjemahan.",
                fontSize = 11.sp,
                color = TextMuted
            )
        }
    }
}
