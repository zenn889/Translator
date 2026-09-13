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
        checkPermissions()

        setContent {
            GameTransTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        isServiceRunning = isServiceRunning,
                        hasOverlayPermission = hasOverlayPermission,
                        sourceLang = sourceLanguage,
                        targetLang = targetLanguage,
                        onSourceLangChange = { sourceLanguage = it },
                        onTargetLangChange = { targetLanguage = it },
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
        Toast.makeText(this, "Tombol melayang telah muncul. Buka game Anda!", Toast.LENGTH_SHORT).show()
    }

    private fun stopFloatingService() {
        val serviceIntent = Intent(this, FloatingBubbleService::class.java)
        stopService(serviceIntent)
        isServiceRunning = false
        Toast.makeText(this, "Layanan dihentikan.", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    isServiceRunning: Boolean,
    hasOverlayPermission: Boolean,
    sourceLang: String,
    targetLang: String,
    onSourceLangChange: (String) -> Unit,
    onTargetLangChange: (String) -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onToggleService: () -> Unit
) {
    val languages = listOf(
        "ja" to "Jepang (日本語)",
        "en" to "Inggris (English)",
        "zh" to "Mandarin (简体中文)",
        "ko" to "Korea (한국어)"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Header
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(CyberBlue),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Translate,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.app_name),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = CyberSky
                )
                Text(
                    text = stringResource(R.string.app_tagline),
                    fontSize = 12.sp,
                    color = TextMuted
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Permission Card (If overlay not granted)
        if (!hasOverlayPermission) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF3B1E1E)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFF87171))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.permission_overlay_title),
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFCA5A5)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.permission_overlay_desc),
                        fontSize = 13.sp,
                        color = Color(0xFFE2E8F0)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onRequestOverlayPermission,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                    ) {
                        Text(stringResource(R.string.grant_permission), color = Color.White)
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Language Configuration Card
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0x3338BDF8), RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "PENGATURAN BAHASA GAME",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = CyberSky,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(14.dp))

                // Source Language
                Text(text = stringResource(R.string.source_language), fontSize = 13.sp, color = TextMuted)
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    languages.forEach { (code, name) ->
                        val isSelected = sourceLang == code
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) CyberBlue else DarkSurfaceVariant)
                                .clickable { onSourceLangChange(code) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = code.uppercase(),
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) Color.White else TextMuted
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Target Language (Indonesian default)
                Text(text = stringResource(R.string.target_language), fontSize = 13.sp, color = TextMuted)
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(DarkSurfaceVariant)
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🇮🇩", fontSize = 18.sp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Bahasa Indonesia", color = TextLight, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Big Action Start/Stop Button
        Button(
            onClick = onToggleService,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isServiceRunning) Color(0xFFDC2626) else EmeraldGreen
            )
        ) {
            Icon(
                imageVector = if (isServiceRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isServiceRunning) stringResource(R.string.stop_service) else stringResource(R.string.start_service),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Step-by-step How to Use Guide Card
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.HelpOutline, contentDescription = null, tint = SubtitleYellow)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "CARA MENGGUNAKAN SAAT MAIN GAME",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = SubtitleYellow
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text("1. Klik tombol hijau 'Mulai Layanan Gelembung Melayang' di atas.", fontSize = 13.sp, color = TextLight)
                Spacer(modifier = Modifier.height(6.dp))
                Text("2. Buka game Android Anda (misal game visual novel, JRPG, dll).", fontSize = 13.sp, color = TextLight)
                Spacer(modifier = Modifier.height(6.dp))
                Text("3. Ikon gelembung melayang akan selalu ada di sisi layar HP Anda.", fontSize = 13.sp, color = TextLight)
                Spacer(modifier = Modifier.height(6.dp))
                Text("4. Saat ada dialog yang ingin dibaca, ketuk gelembung melayang tersebut.", fontSize = 13.sp, color = TextLight)
                Spacer(modifier = Modifier.height(6.dp))
                Text("5. Teks dialog akan langsung terbaca dan muncul terjemahan Bahasa Indonesia!", fontSize = 13.sp, color = TextLight)
            }
        }
    }
}
