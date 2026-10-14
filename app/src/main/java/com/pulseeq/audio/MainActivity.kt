package com.pulseeq.audio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private var eqService by mutableStateOf<EqForegroundService?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as EqForegroundService.LocalBinder
            eqService = binder.getService()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            eqService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val serviceIntent = Intent(this, EqForegroundService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFFF9E44),
                    secondary = Color(0xFF38BDF8),
                    background = Color(0xFF0B0D12),
                    surface = Color(0xFF151922)
                )
            ) {
                val service = eqService
                if (service != null) {
                    val state by service.state.collectAsState()
                    PulseEqScreen(state = state, service = service)
                } else {
                    Box(Modifier.fillMaxSize().background(Color(0xFF0B0D12)), contentAlignment = Alignment.Center) {
                        Text("Initializing Hardware DSP...", color = Color.White)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindService(connection)
    }
}

@Composable
fun PulseEqScreen(state: EqState, service: EqForegroundService) {
    val freqLabels = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0D12))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header + Master Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("PULSE EQ // DSP", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = "Output: ${state.connectedDevice}",
                    color = Color(0xFF38BDF8),
                    fontSize = 12.sp
                )
            }
            Switch(
                checked = state.isEnabled,
                onCheckedChange = { service.toggleMaster(it) }
            )
        }

        // Real-Time Parametric Frequency Response Spline Graph
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(155.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF131720))
                .border(1.dp, Color(0xFF262D3D), RoundedCornerShape(14.dp))
                .padding(12.dp)
        ) {
            FrequencyCurveCanvas(gains = state.bandGains, preamp = state.preampDb, enabled = state.isEnabled)
            Text(
                text = "${state.activePreset}  |  Preamp: ${String.format("%.1f", state.preampDb)} dB",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }

        // AutoEQ Preset Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EqForegroundService.PRESETS.keys.forEach { presetName ->
                val selected = state.activePreset == presetName
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (selected) Color(0xFFFF9E44) else Color(0xFF1A202C))
                        .clickable { service.applyPreset(presetName) }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = presetName,
                        color = if (selected) Color.Black else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Preamp Gain Slider
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF151922))
                .padding(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Pre-Amplifier Headroom", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text("${String.format("%.1f", state.preampDb)} dB", color = Color(0xFFFF9E44), fontSize = 13.sp)
            }
            Slider(
                value = state.preampDb,
                onValueChange = { service.setPreamp(it) },
                valueRange = -12f..6f
            )
        }

        // 10-Band Parametric Sliders
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF151922))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("10-Band Hardware Filter Bank (-12dB to +12dB)", color = Color(0xFF94A3B8), fontSize = 12.sp)

            state.bandGains.forEachIndexed { index, gain ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${freqLabels[index]} Hz",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.width(52.dp)
                    )
                    Slider(
                        value = gain,
                        onValueChange = { service.setBandGain((index), (it * 10f).roundToInt() / 10f) },
                        valueRange = -12f..12f,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${if (gain >= 0) "+" else ""}${String.format("%.1f", gain)}",
                        color = if (gain != 0f) Color(0xFFFF9E44) else Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.width(44.dp)
                    )
                }
            }
        }

        // Hardware Limiter & Auto-Switch Toggles
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF151922))
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Brickwall Anti-Clip Limiter", color = Color.White, fontSize = 13.sp)
                Text("Prevents distortion on heavy sub-bass drops", color = Color.Gray, fontSize = 11.sp)
            }
            Switch(checked = state.limiterEnabled, onCheckedChange = { service.toggleLimiter(it) })
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF151922))
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Auto-Detect IEM vs Bluetooth", color = Color.White, fontSize = 13.sp)
                Text("Switches preset when DAC/Headset plugs in", color = Color.Gray, fontSize = 11.sp)
            }
            Switch(checked = state.autoSwitchHardware, onCheckedChange = { service.toggleAutoSwitch(it) })
        }
    }
}

@Composable
fun FrequencyCurveCanvas(gains: List<Float>, preamp: Float, enabled: Boolean) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val midY = h / 2f

        // 0dB Center Reference Line
        drawLine(
            color = Color(0xFF2A3244),
            start = Offset(0f, midY),
            end = Offset(w, midY),
            strokeWidth = 2f
        )

        val points = gains.mapIndexed { idx, gain ->
            val x = (idx.toFloat() / (gains.size - 1)) * w
            val normalizedY = midY - ((gain / 15f) * (h / 2f))
            Offset(x, normalizedY.coerceIn(8f, h - 8f))
        }

        val path = Path().apply {
            if (points.isNotEmpty()) {
                moveTo(points.first().x, points.first().y)
                for (i in 0 until points.size - 1) {
                    val p0 = points[i]
                    val p1 = points[i + 1]
                    val controlX = (p0.x + p1.x) / 2f
                    cubicTo(controlX, p0.y, controlX, p1.y, p1.x, p1.y)
                }
            }
        }

        val lineColor = if (enabled) Color(0xFFFF9E44) else Color.Gray
        drawPath(path = path, color = lineColor, style = Stroke(width = 5f))

        // Fill gradient under curve
        val fillPath = Path().apply {
            addPath(path)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = 0.32f), Color.Transparent)
            )
        )
    }
}