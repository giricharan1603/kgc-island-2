package com.kgc.island2

import android.Manifest
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
import com.kgc.island2.model.CircleAnimationStyle
import com.kgc.island2.model.IslandPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        IslandStateEngine.loadPersistedPreferences(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                KgcIslandControlCenter()
            }
        }
    }

    @Composable
    private fun KgcIslandControlCenter() {
        val cutoutConfig by IslandStateEngine.cutoutConfig.collectAsState()
        val pinnedPackages by IslandStateEngine.pinnedShortcutPackages.collectAsState()
        val lifecycleOwner = LocalLifecycleOwner.current

        var isAccessibilityEnabled by remember { mutableStateOf(checkAccessibilityPermission()) }
        var isNotificationEnabled by remember { mutableStateOf(checkNotificationPermission()) }
        var isWriteSettingsEnabled by remember { mutableStateOf(Settings.System.canWrite(this)) }
        var isBluetoothGranted by remember { mutableStateOf(checkBluetoothPermission()) }
        var isBatteryExempt by remember { mutableStateOf(checkBatteryOptimization()) }
        var installedApps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

        LaunchedEffect(Unit) {
            installedApps = withContext(Dispatchers.IO) {
                val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                packageManager.queryIntentActivities(mainIntent, 0)
                    .mapNotNull { info ->
                        val pkg = info.activityInfo.packageName
                        if (pkg == packageName) return@mapNotNull null
                        val label = info.loadLabel(packageManager).toString()
                        Pair(label, pkg)
                    }
                    .distinctBy { it.second }
                    .sortedBy { it.first.lowercase() }
            }
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
                text = "Hold & Drag • 5 Circle Styles • Custom App Dock",
                color = Color(0xFF38BDF8),
                fontSize = 13.sp
            )

            // 1. Permissions Card
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

            // 2. Circle Animation Style Selector Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("2. Camera Circle Animation Style", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)

                    CircleAnimationStyle.entries.forEach { style ->
                        val selected = cutoutConfig.circleAnimationStyle == style
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected) Color(0xFF0284C7).copy(alpha = 0.3f) else Color(0xFF0F172A))
                                .clickable {
                                    IslandStateEngine.saveCutoutConfigToPrefs(
                                        this@MainActivity,
                                        cutoutConfig.copy(circleAnimationStyle = style)
                                    )
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(style.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            RadioButton(
                                selected = selected,
                                onClick = {
                                    IslandStateEngine.saveCutoutConfigToPrefs(
                                        this@MainActivity,
                                        cutoutConfig.copy(circleAnimationStyle = style)
                                    )
                                }
                            )
                        }
                    }
                }
            }

            // 3. Hold & Drag + Cutout Calibration + Charging Duration Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("3. Hold & Drag Position & Timing", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Touch, Hold & Drag Island", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Long-press & drag the island in Portrait or Landscape", color = Color.Gray, fontSize = 11.sp)
                        }
                        Switch(
                            checked = cutoutConfig.isHoldAndDragEnabled,
                            onCheckedChange = { enabled ->
                                IslandStateEngine.saveCutoutConfigToPrefs(
                                    this@MainActivity,
                                    cutoutConfig.copy(isHoldAndDragEnabled = enabled)
                                )
                            }
                        )
                    }

                    Text("Portrait Offset X: ${cutoutConfig.offsetX} px", color = Color.LightGray, fontSize = 12.sp)
                    Slider(
                        value = cutoutConfig.offsetX.toFloat(),
                        onValueChange = {
                            IslandStateEngine.saveCutoutConfigToPrefs(
                                this@MainActivity,
                                cutoutConfig.copy(offsetX = it.toInt()),
                                isCustomPort = true
                            )
                        },
                        valueRange = -300f..300f
                    )

                    Text("Portrait Offset Y: ${cutoutConfig.offsetY} px", color = Color.LightGray, fontSize = 12.sp)
                    Slider(
                        value = cutoutConfig.offsetY.toFloat(),
                        onValueChange = {
                            IslandStateEngine.saveCutoutConfigToPrefs(
                                this@MainActivity,
                                cutoutConfig.copy(offsetY = it.toInt()),
                                isCustomPort = true
                            )
                        },
                        valueRange = -20f..250f
                    )

                    Text("Landscape Offset X: ${cutoutConfig.landscapeCutoutX} px", color = Color.LightGray, fontSize = 12.sp)
                    Slider(
                        value = cutoutConfig.landscapeCutoutX.toFloat(),
                        onValueChange = {
                            IslandStateEngine.saveCutoutConfigToPrefs(
                                this@MainActivity,
                                cutoutConfig.copy(landscapeCutoutX = it.toInt()),
                                isCustomLand = true
                            )
                        },
                        valueRange = -200f..400f
                    )

                    Text("Landscape Offset Y: ${cutoutConfig.landscapeCutoutY} px", color = Color.LightGray, fontSize = 12.sp)
                    Slider(
                        value = cutoutConfig.landscapeCutoutY.toFloat(),
                        onValueChange = {
                            IslandStateEngine.saveCutoutConfigToPrefs(
                                this@MainActivity,
                                cutoutConfig.copy(landscapeCutoutY = it.toInt()),
                                isCustomLand = true
                            )
                        },
                        valueRange = -300f..300f
                    )

                    Text("Punch-Hole Ring Diameter: ${cutoutConfig.idleCircleSizeDp} dp", color = Color.LightGray, fontSize = 12.sp)
                    Slider(
                        value = cutoutConfig.idleCircleSizeDp.toFloat(),
                        onValueChange = {
                            val size = it.toInt()
                            IslandStateEngine.saveCutoutConfigToPrefs(
                                this@MainActivity,
                                cutoutConfig.copy(
                                    baseWidthDp = size,
                                    baseHeightDp = size,
                                    idleCircleSizeDp = size
                                )
                            )
                        },
                        valueRange = 20f..56f
                    )

                    val chargeSec = (cutoutConfig.chargingDurationMs / 1000f)
                    Text("Charging Animation Duration: ${"%.1f".format(chargeSec)} sec (5–8s)", color = Color(0xFF4ADE80), fontSize = 12.sp)
                    Slider(
                        value = cutoutConfig.chargingDurationMs.toFloat(),
                        onValueChange = {
                            IslandStateEngine.saveCutoutConfigToPrefs(
                                this@MainActivity,
                                cutoutConfig.copy(chargingDurationMs = it.toLong())
                            )
                        },
                        valueRange = 5000f..8000f
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                IslandStateEngine.resetOffsetsToHardwareCutout(this@MainActivity, 0, 10, 14, 0)
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
                                        subtitle = "Super Charge • 3850mA",
                                        accentColor = Color(0xFF4ADE80),
                                        iconType = AlertIconType.CHARGING,
                                        autoDismissMs = cutoutConfig.chargingDurationMs
                                    )
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Test Charging", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }

            // 4. Pin Custom Apps to Island Dock Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("4. Pin Apps to Island Quick Dock", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Tap apps below to add/remove them inside Kgc Island (${pinnedPackages.size}/8 selected):", color = Color.Gray, fontSize = 12.sp)

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(installedApps) { (label, pkg) ->
                            val isPinned = pinnedPackages.contains(pkg)
                            FilterChip(
                                selected = isPinned,
                                onClick = {
                                    IslandStateEngine.togglePinnedPackage(this@MainActivity, pkg)
                                },
                                label = { Text(label, maxLines = 1) }
                            )
                        }
                    }
                }
            }
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