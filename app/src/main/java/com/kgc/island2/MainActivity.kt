package com.kgc.island2

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kgc.island2.engine.IslandStateEngine

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

        var isAccessibilityEnabled by remember { mutableStateOf(checkAccessibilityPermission()) }
        var isNotificationEnabled by remember { mutableStateOf(checkNotificationPermission()) }
        var isBatteryExempt by remember { mutableStateOf(checkBatteryOptimization()) }

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    isAccessibilityEnabled = checkAccessibilityPermission()
                    isNotificationEnabled = checkNotificationPermission()
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
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Kgc Island 2",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold
            )

            // 1. Live System Permissions Status Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PermissionRow(
                        label = "Accessibility Overlay",
                        isGranted = isAccessibilityEnabled,
                        onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    )

                    PermissionRow(
                        label = "Notification & Media Listener",
                        isGranted = isNotificationEnabled,
                        onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    )

                    PermissionRow(
                        label = "Unrestricted Battery",
                        isGranted = isBatteryExempt,
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:$packageName")
                            )
                            startActivity(intent)
                        }
                    )
                }
            }

            // 2. Camera Punch-Hole Circle Calibration Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "X: ${cutoutConfig.offsetX} px",
                        color = Color.LightGray,
                        fontSize = 12.sp
                    )
                    Slider(
                        value = cutoutConfig.offsetX.toFloat(),
                        onValueChange = {
                            IslandStateEngine.updateCutoutConfig(cutoutConfig.copy(offsetX = it.toInt()))
                        },
                        valueRange = -200f..200f
                    )

                    Text(
                        text = "Y: ${cutoutConfig.offsetY} px",
                        color = Color.LightGray,
                        fontSize = 12.sp
                    )
                    Slider(
                        value = cutoutConfig.offsetY.toFloat(),
                        onValueChange = {
                            IslandStateEngine.updateCutoutConfig(cutoutConfig.copy(offsetY = it.toInt()))
                        },
                        valueRange = -20f..120f
                    )

                    Text(
                        text = "Punch-Hole Circle Diameter: ${cutoutConfig.idleCircleSizeDp} dp",
                        color = Color.LightGray,
                        fontSize = 12.sp
                    )
                    Slider(
                        value = cutoutConfig.idleCircleSizeDp.toFloat(),
                        onValueChange = {
                            IslandStateEngine.updateCutoutConfig(cutoutConfig.copy(idleCircleSizeDp = it.toInt()))
                        },
                        valueRange = 20f..56f
                    )
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
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isGranted) Color(0xFF22C55E) else Color(0xFFEF4444))
                )
                Text(text = label, color = Color.White, fontSize = 14.sp)
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

    private fun checkBatteryOptimization(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }
}