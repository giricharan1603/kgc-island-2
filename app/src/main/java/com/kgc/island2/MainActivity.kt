package com.kgc.island2

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.kgc.island2.engine.IslandStateEngine
import com.kgc.island2.model.AlertIconType
import com.kgc.island2.model.CutoutConfig
import com.kgc.island2.model.IslandPayload

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF090D16)
                ) {
                    DashboardScreen()
                }
            }
        }
    }
}

@Composable
private fun DashboardScreen() {
    val context = LocalContext.current
    val cutoutConfig by IslandStateEngine.cutoutConfig.collectAsState()

    var offsetX by remember { mutableFloatStateOf(cutoutConfig.offsetX.toFloat()) }
    var offsetY by remember { mutableFloatStateOf(cutoutConfig.offsetY.toFloat()) }
    var circleSize by remember { mutableFloatStateOf(cutoutConfig.idleCircleSizeDp.toFloat()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Kgc Island 2",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "System Overlay & Cutout Calibration",
            color = Color(0xFF94A3B8),
            fontSize = 14.sp
        )

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "1. Required System Permissions",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )

                Button(
                    onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        } catch (_: Exception) {}
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isAccessibilityEnabled(context)) Color(0xFF22C55E) else Color(0xFF38BDF8)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Enable Accessibility Overlay Engine", color = Color.Black, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        } catch (_: Exception) {}
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isNotificationEnabled(context)) Color(0xFF22C55E) else Color(0xFF38BDF8)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Enable Notification & Media Bridge", color = Color.Black, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        try {
                            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        "package:${context.packageName}".toUri()
                                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                )
                            }
                        } catch (_: Exception) {}
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Exempt from Battery Optimization", color = Color.White)
                }
            }
        }

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
                    text = "2. Camera Cutout Calibration",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )

                Text("Horizontal Offset (X): ${offsetX.toInt()} px", color = Color.White, fontSize = 13.sp)
                Slider(
                    value = offsetX,
                    onValueChange = {
                        offsetX = it
                        IslandStateEngine.updateCutoutConfig(
                            CutoutConfig(
                                offsetX = it.toInt(),
                                offsetY = offsetY.toInt(),
                                idleCircleSizeDp = circleSize.toInt()
                            )
                        )
                    },
                    valueRange = -200f..200f
                )

                Text("Vertical Offset (Y): ${offsetY.toInt()} px", color = Color.White, fontSize = 13.sp)
                Slider(
                    value = offsetY,
                    onValueChange = {
                        offsetY = it
                        IslandStateEngine.updateCutoutConfig(
                            CutoutConfig(
                                offsetX = offsetX.toInt(),
                                offsetY = it.toInt(),
                                idleCircleSizeDp = circleSize.toInt()
                            )
                        )
                    },
                    valueRange = -50f..150f
                )

                Text("Circle Size: ${circleSize.toInt()} dp", color = Color.White, fontSize = 13.sp)
                Slider(
                    value = circleSize,
                    onValueChange = {
                        circleSize = it
                        IslandStateEngine.updateCutoutConfig(
                            CutoutConfig(
                                offsetX = offsetX.toInt(),
                                offsetY = offsetY.toInt(),
                                idleCircleSizeDp = it.toInt()
                            )
                        )
                    },
                    valueRange = 20f..60f
                )
            }
        }

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "3. Verify Island Overlay",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )

                Button(
                    onClick = {
                        IslandStateEngine.submitPayload(
                            IslandPayload.SystemAlert(
                                id = "test_alert",
                                title = "Kgc Island 2",
                                subtitle = "Active",
                                accentColor = Color(0xFF22C55E),
                                iconType = AlertIconType.CHARGING
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Test Island Expansion")
                }
            }
        }
    }
}

private fun isAccessibilityEnabled(context: Context): Boolean {
    return try {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        enabled.contains(context.packageName)
    } catch (_: Exception) {
        false
    }
}

private fun isNotificationEnabled(context: Context): Boolean {
    return try {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    } catch (_: Exception) {
        false
    }
}