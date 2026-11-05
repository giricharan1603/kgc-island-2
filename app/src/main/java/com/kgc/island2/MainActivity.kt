package com.kgc.island2

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kgc.island2.engine.IslandStateEngine
import com.kgc.island2.model.AlertIconType
import com.kgc.island2.model.CutoutConfig
import com.kgc.island2.model.IslandPayload

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                KgcIslandControlCenter()
            }
        }
    }

    @Composable
    private fun KgcIslandControlCenter() {
        val cutoutConfig by IslandStateEngine.cutoutConfig.collectAsState()
        val lifecycleOwner = LocalLifecycleOwner.current
        val prefs = remember { getSharedPreferences("kgc_island2_prefs", Context.MODE_PRIVATE) }

        var isAccessibilityEnabled by remember { mutableStateOf(checkAccessibilityPermission()) }
        var isNotificationEnabled by remember { mutableStateOf(checkNotificationPermission()) }
        var isWriteSettingsEnabled by remember { mutableStateOf(Settings.System.canWrite(this)) }
        var isBluetoothGranted by remember { mutableStateOf(checkBluetoothPermission()) }
        var isBatteryExempt by remember { mutableStateOf(checkBatteryOptimization()) }

        var idleCustomText by remember { mutableStateOf(prefs.getString("idle_custom_text", "KGC ISLAND 2 | READY").orEmpty()) }
        var isIdleTextEnabled by remember { mutableStateOf(prefs.getBoolean("idle_text_enabled", false)) }
        var selectedThemeStyle by remember { mutableStateOf(prefs.getString("idle_theme_style", "CYBER_SWEEP").orEmpty()) }
        var selectedColorPalette by remember { mutableStateOf(prefs.getString("idle_color_palette", "CYBER_CYAN").orEmpty()) }
        var isLyricsEnabled by remember { mutableStateOf(prefs.getBoolean("lyrics_enabled", true)) }

        val themeOptions = remember {
            listOf(
                "CYBER_SWEEP" to "Cyber Neon Sweep",
                "RGB_CHROMA" to "RGB Gaming Chroma",
                "DUAL_SCANNER" to "Sci-Fi Dual Scanner",
                "STARLIGHT" to "Starlight Orbit Dots",
                "PULSE_GLOW" to "Breathing Pulse Aura",
                "STEALTH_STATIC" to "Minimal Stealth Ring"
            )
        }

        val paletteOptions = remember {
            listOf(
                "CYBER_CYAN" to "Cyber Cyan",
                "SUNSET_GOLD" to "Sunset Gold",
                "MATRIX_EMERALD" to "Matrix Emerald",
                "CRIMSON_BLAZE" to "Crimson Blaze",
                "ROYAL_AMETHYST" to "Royal Amethyst",
                "MONO_SILVER" to "Monochrome Silver"
            )
        }

        val btPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            isBluetoothGranted = granted
        }

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    isAccessibilityEnabled = checkAccessibilityPermission()
                    isNotificationEnabled = checkNotificationPermission()
                    isWriteSettingsEnabled = Settings.System.canWrite(this@MainActivity)
                    isBluetoothGranted = checkBluetoothPermission()
                    isBatteryExempt = checkBatteryOptimization()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0F172A))
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Kgc Island 2",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = "Idle Text Roll-Down • Live Song Lyrics • 6 Ring Themes",
                color = Color(0xFF38BDF8),
                fontSize = 13.sp
            )

            // 1. System Permissions
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("1. System Permissions", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)

                    PermissionRow("Accessibility Overlay", isAccessibilityEnabled) {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    PermissionRow("Notification & Media Bridge", isNotificationEnabled) {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                    PermissionRow("Auto-Rotate Control (Write Settings)", isWriteSettingsEnabled) {
                        startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:$packageName".toUri()))
                    }
                    PermissionRow("Bluetooth Silent Toggle", isBluetoothGranted) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        }
                    }
                    PermissionRow("Unrestricted Battery", isBatteryExempt) {
                        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri()))
                    }
                }
            }

            // 2. Idle Roll-Down Text & Live Song Lyrics Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("2. Idle Roll-Down Text & Live Lyrics", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Show Roll-Down Text When Idle", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Tip: Separate phrases with | to roll between them!", color = Color.Gray, fontSize = 11.sp)
                        }
                        Switch(
                            checked = isIdleTextEnabled,
                            onCheckedChange = { enabled ->
                                isIdleTextEnabled = enabled
                                prefs.edit()
                                    .putBoolean("idle_text_enabled", enabled)
                                    .putString("idle_custom_text", idleCustomText)
                                    .apply()
                            }
                        )
                    }

                    OutlinedTextField(
                        value = idleCustomText,
                        onValueChange = { newText ->
                            idleCustomText = newText
                            prefs.edit().putString("idle_custom_text", newText).apply()
                        },
                        label = { Text("Enter Idle Roll-Down Text (e.g. GIRI | STAY FOCUSED)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    HorizontalDivider(color = Color(0xFF334155))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Live Synchronized Song Lyrics", color = Color(0xFF4ADE80), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text("Automatically rolls down synced song lyrics in Spotify/Music", color = Color.Gray, fontSize = 11.sp)
                        }
                        Switch(
                            checked = isLyricsEnabled,
                            onCheckedChange = { enabled ->
                                isLyricsEnabled = enabled
                                prefs.edit().putBoolean("lyrics_enabled", enabled).apply()
                            }
                        )
                    }
                }
            }

            // 3. Idle Ring Themes & Colour Palettes Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("3. Idle Camera Ring Themes & Colours", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)

                    Text("Select Ring Animation:", color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    themeOptions.forEach { (key, label) ->
                        val isSelected = selectedThemeStyle == key
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) Color(0xFF0284C7).copy(alpha = 0.3f) else Color(0xFF0F172A))
                                .clickable {
                                    selectedThemeStyle = key
                                    prefs.edit().putString("idle_theme_style", key).apply()
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(label, color = Color.White, fontSize = 13.sp)
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    selectedThemeStyle = key
                                    prefs.edit().putString("idle_theme_style", key).apply()
                                }
                            )
                        }
                    }

                    Text("Select Colour Palette:", color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(paletteOptions) { (key, label) ->
                            FilterChip(
                                selected = selectedColorPalette == key,
                                onClick = {
                                    selectedColorPalette = key
                                    prefs.edit().putString("idle_color_palette", key).apply()
                                },
                                label = { Text(label) }
                            )
                        }
                    }

                    Button(
                        onClick = {
                            prefs.edit()
                                .putLong("edge_flash_color", 0xFF25D366L)
                                .putLong("edge_flash_timestamp", System.currentTimeMillis())
                                .apply()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Test Edge-Light Notification Flash", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // 4. Hold & Drag Position Calibration Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("4. Hold & Drag Position Calibration", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Tip: Long-press & drag the camera ring directly on your screen!", color = Color(0xFF38BDF8), fontSize = 12.sp)

                    Text("Portrait Offset X: ${cutoutConfig.offsetX} px", color = Color.LightGray, fontSize = 12.sp)
                    Slider(
                        value = cutoutConfig.offsetX.toFloat(),
                        onValueChange = {
                            val updated = cutoutConfig.copy(offsetX = it.toInt())
                            IslandStateEngine.updateCutoutConfig(updated)
                            saveCalibrationPrefs(updated, customPort = true)
                        },
                        valueRange = -300f..300f
                    )

                    Text("Portrait Offset Y: ${cutoutConfig.offsetY} px", color = Color.LightGray, fontSize = 12.sp)
                    Slider(
                        value = cutoutConfig.offsetY.toFloat(),
                        onValueChange = {
                            val updated = cutoutConfig.copy(offsetY = it.toInt())
                            IslandStateEngine.updateCutoutConfig(updated)
                            saveCalibrationPrefs(updated, customPort = true)
                        },
                        valueRange = -20f..250f
                    )

                    Text("Landscape Offset X: ${cutoutConfig.landscapeCutoutX} px", color = Color.LightGray, fontSize = 12.sp)
                    Slider(
                        value = cutoutConfig.landscapeCutoutX.toFloat(),
                        onValueChange = {
                            val updated = cutoutConfig.copy(landscapeCutoutX = it.toInt())
                            IslandStateEngine.updateCutoutConfig(updated)
                            saveCalibrationPrefs(updated, customLand = true)
                        },
                        valueRange = -200f..400f
                    )

                    Text("Landscape Offset Y: ${cutoutConfig.landscapeCutoutY} px", color = Color.LightGray, fontSize = 12.sp)
                    Slider(
                        value = cutoutConfig.landscapeCutoutY.toFloat(),
                        onValueChange = {
                            val updated = cutoutConfig.copy(landscapeCutoutY = it.toInt())
                            IslandStateEngine.updateCutoutConfig(updated)
                            saveCalibrationPrefs(updated, customLand = true)
                        },
                        valueRange = -300f..300f
                    )

                    Text("Punch-Hole Ring Diameter: ${cutoutConfig.idleCircleSizeDp} dp", color = Color.LightGray, fontSize = 12.sp)
                    Slider(
                        value = cutoutConfig.idleCircleSizeDp.toFloat(),
                        onValueChange = {
                            val size = it.toInt()
                            val updated = cutoutConfig.copy(
                                baseWidthDp = size,
                                baseHeightDp = size,
                                idleCircleSizeDp = size
                            )
                            IslandStateEngine.updateCutoutConfig(updated)
                            saveCalibrationPrefs(updated)
                        },
                        valueRange = 20f..56f
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                val reset = cutoutConfig.copy(
                                    offsetX = 0,
                                    offsetY = 10,
                                    landscapeCutoutX = 14,
                                    landscapeCutoutY = 0
                                )
                                IslandStateEngine.updateCutoutConfig(reset)
                                saveCalibrationPrefs(reset, customPort = false, customLand = false)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Reset Position", color = Color.White, fontSize = 12.sp)
                        }
                        Button(
                            onClick = {
                                IslandStateEngine.submitPayload(
                                    IslandPayload.SystemAlert(
                                        id = "charging_test",
                                        title = "⚡ 88%",
                                        subtitle = "Super Charge • 6.5s",
                                        accentColor = Color(0xFF4ADE80),
                                        iconType = AlertIconType.CHARGING,
                                        autoDismissMs = 6500L
                                    )
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Test 6.5s Charge", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    private fun saveCalibrationPrefs(
        cfg: CutoutConfig,
        customPort: Boolean? = null,
        customLand: Boolean? = null
    ) {
        try {
            getSharedPreferences("kgc_island2_prefs", Context.MODE_PRIVATE).edit().apply {
                putInt("port_x", cfg.offsetX)
                putInt("port_y", cfg.offsetY)
                putInt("land_x", cfg.landscapeCutoutX)
                putInt("land_y", cfg.landscapeCutoutY)
                putInt("circle_size_dp", cfg.idleCircleSizeDp)
                customPort?.let { putBoolean("has_custom_port", it) }
                customLand?.let { putBoolean("has_custom_land", it) }
                apply()
            }
        } catch (e: Exception) {
        }
    }

    @Composable
    private fun PermissionRow(label: String, isGranted: Boolean, onClick: () -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isGranted) Color(0xFF22C55E) else Color(0xFFEF4444))
                )
                Text(text = label, color = Color.White, fontSize = 13.sp)
            }
            Button(onClick = onClick) {
                Text(if (isGranted) "Active" else "Grant")
            }
        }
    }

    private fun checkAccessibilityPermission(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.contains(packageName)
    }

    private fun checkNotificationPermission(): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    }

    private fun checkBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun checkBatteryOptimization(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }
}