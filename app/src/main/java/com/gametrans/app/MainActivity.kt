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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
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

    // Launcher for MediaProjection Screen Capture Intent
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
                        targetLang = targetLanguage,
                        autoStopOnExit = autoStopOnExit,
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
        // If autoStopOnExit is enabled, cleanly stop floating service when activity finishes
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
        Toast.makeText(this, "Gelembung melayang aktif! Silakan buka game Anda.", Toast.LENGTH_SHORT).show()
    }

    private fun stopFloatingService() {
        val serviceIntent = Intent(this, FloatingBubbleService::class.java)
        stopService(serviceIntent)
        isServiceRunning = false
        Toast.makeText(this, "Layanan gelembung dihentikan.", Toast.LENGTH_SHORT).show()
    }
}

data class LangItem(val code: String, val flag: String, val name: String, val nativeName: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    isServiceRunning: Boolean,
    hasOverlayPermission: Boolean,
    sourceLang: String,
    targetLang: String,
    autoStopOnExit: Boolean,
    onSourceLangChange: (String) -> Unit,
    onAutoStopChange: (Boolean) -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onToggleService: () -> Unit
) {
    val languages = listOf(
        LangItem("ja", "🇯🇵", "Jepang", "日本語"),
        LangItem("en", "🇺🇸", "Inggris", "English"),
        LangItem("zh", "🇨🇳", "Mandarin", "简体中文"),
        LangItem("ko", "🇰🇷", "Korea", "한국어")
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Header Section
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF0EA5E9), Color(0xFF6366F1))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Translate,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "GameTrans",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF1E293B))
                                .border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "v1.0.2",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyberSky
                            )
                        }
                    }
                    Text(
                        text = "Realtime In-Game Subtitle Screen Translator",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Service Status Indicator Card
        val statusBgColor by animateColorAsState(
            targetValue = if (isServiceRunning) Color(0xFF064E3B) else Color(0xFF1E293B),
            animationSpec = tween(300),
            label = "statusBg"
        )
        val statusBorderColor by animateColorAsState(
            targetValue = if (isServiceRunning) Color(0xFF10B981) else Color(0xFF334155),
            animationSpec = tween(300),
            label = "statusBorder"
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = statusBgColor),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.2.dp, statusBorderColor, RoundedCornerShape(16.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(if (isServiceRunning) Color(0xFF34D399) else Color(0xFF64748B))
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = if (isServiceRunning) "STATUS: GELEMBUNG AKTIF" else "STATUS: NONAKTIF",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isServiceRunning) Color(0xFFA7F3D0) else Color(0xFFCBD5E1)
                        )
                        Text(
                            text = if (isServiceRunning) "Gelembung siap ditekan di atas game" else "Gelembung belum dimunculkan",
                            fontSize = 11.sp,
                            color = if (isServiceRunning) Color(0xFF6EE7B7) else Color(0xFF94A3B8)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Permission Card (If overlay not granted)
        if (!hasOverlayPermission) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF450A0A)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFF87171))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "IZIN OVERLAY DIBUTUHKAN",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color(0xFFFCA5A5)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Izin 'Tampilkan di atas aplikasi lain' diperlukan agar tombol gelembung melayang dapat muncul di atas game Anda.",
                        fontSize = 12.sp,
                        color = Color(0xFFE2E8F0)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onRequestOverlayPermission,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Buka Pengaturan Izin", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(modifier = Modifier.height(18.dp))
        }

        // Primary Action Start / Stop Button
        val buttonGradient = if (isServiceRunning) {
            Brush.horizontalGradient(listOf(Color(0xFFDC2626), Color(0xFF991B1B)))
        } else {
            Brush.horizontalGradient(listOf(Color(0xFF059669), Color(0xFF0284C7)))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(buttonGradient)
                .clickable { onToggleService() },
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isServiceRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (isServiceRunning) "HENTIKAN LAYANAN TRANSLATE" else "MULAI LAYANAN TRANSLATE",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    letterSpacing = 0.5.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Language Selection Card
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0x2E38BDF8), RoundedCornerShape(18.dp))
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "PILIHAN BAHASA GAME",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = CyberSky,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Bahasa Asal Game (Source):",
                    fontSize = 12.sp,
                    color = TextMuted,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Source Language Grid (2x2)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        languages.take(2).forEach { item ->
                            val isSelected = sourceLang == item.code
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) Color(0xFF0369A1) else DarkSurfaceVariant)
                                    .border(
                                        width = if (isSelected) 1.5.dp else 1.dp,
                                        color = if (isSelected) CyberSky else Color(0x22FFFFFF),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .clickable { onSourceLangChange(item.code) }
                                    .padding(vertical = 10.dp, horizontal = 12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(item.flag, fontSize = 20.sp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = item.name,
                                            fontSize = 13.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) Color.White else Color(0xFFCBD5E1)
                                        )
                                        Text(
                                            text = item.nativeName,
                                            fontSize = 10.sp,
                                            color = if (isSelected) Color(0xFFBAE6FD) else TextMuted
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        languages.drop(2).forEach { item ->
                            val isSelected = sourceLang == item.code
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) Color(0xFF0369A1) else DarkSurfaceVariant)
                                    .border(
                                        width = if (isSelected) 1.5.dp else 1.dp,
                                        color = if (isSelected) CyberSky else Color(0x22FFFFFF),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .clickable { onSourceLangChange(item.code) }
                                    .padding(vertical = 10.dp, horizontal = 12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(item.flag, fontSize = 20.sp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = item.name,
                                            fontSize = 13.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) Color.White else Color(0xFFCBD5E1)
                                        )
                                        Text(
                                            text = item.nativeName,
                                            fontSize = 10.sp,
                                            color = if (isSelected) Color(0xFFBAE6FD) else TextMuted
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Target Language Box
                Text(
                    text = "Diterjemahkan Ke (Target):",
                    fontSize = 12.sp,
                    color = TextMuted,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurfaceVariant)
                        .border(1.dp, Color(0x2610B981), RoundedCornerShape(12.dp))
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🇮🇩", fontSize = 22.sp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Bahasa Indonesia",
                                color = TextLight,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "Terjemahan offline/online realtime berkecepatan tinggi",
                                color = EmeraldGreen,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Settings / Lifecycle Control Card (Addresses user request for auto stop on exit)
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0x2E38BDF8), RoundedCornerShape(18.dp))
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "PENGATURAN SISTEM",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = CyberSky,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Tutup Layanan Saat Aplikasi Keluar",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = TextLight
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Otomatis hentikan gelembung jika GameTrans ditutup dari Recent Apps / tombol kembali.",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = autoStopOnExit,
                        onCheckedChange = onAutoStopChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = CyberBlue,
                            uncheckedThumbColor = Color(0xFF94A3B8),
                            uncheckedTrackColor = Color(0xFF334155)
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Step-by-step Game Guide
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SportsEsports, contentDescription = null, tint = SubtitleYellow)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "FITUR & CARA PAKAI DI DALAM GAME",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = SubtitleYellow
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))

                GuideItem(
                    icon = "🎯",
                    title = "Terjemahkan Dialog",
                    desc = "Ketuk gelembung melayang 1x saat teks dialog dalam game muncul."
                )
                Spacer(modifier = Modifier.height(10.dp))
                GuideItem(
                    icon = "✋",
                    title = "Geser Bebas & Snap",
                    desc = "Gelembung bisa digeser ke mana saja dan otomatis menempel di tepi layar."
                )
                Spacer(modifier = Modifier.height(10.dp))
                GuideItem(
                    icon = "🗑️",
                    title = "Tutup Cepat (Drag-to-Delete)",
                    desc = "Tarik gelembung ke ikon sampah merah di bawah layar untuk menutup."
                )
                Spacer(modifier = Modifier.height(10.dp))
                GuideItem(
                    icon = "🔠",
                    title = "Ukuran Font HUD Subtitle",
                    desc = "Klik ikon 'A' di kotak terjemahan untuk memperbesar font (Normal/Besar/Ekstra)."
                )
                Spacer(modifier = Modifier.height(10.dp))
                GuideItem(
                    icon = "🔊",
                    title = "Fitur Suara & Salin",
                    desc = "Tersedia tombol speaker (TTS) untuk bersuara dan tombol copy untuk menyalin teks."
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Footer
        Text(
            text = "GameTrans Android • Dibuat khusus untuk gamer Indonesia",
            fontSize = 11.sp,
            color = TextMuted
        )
    }
}

@Composable
fun GuideItem(icon: String, title: String, desc: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(text = icon, fontSize = 16.sp)
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(text = title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextLight)
            Text(text = desc, fontSize = 11.sp, color = TextMuted)
        }
    }
}
