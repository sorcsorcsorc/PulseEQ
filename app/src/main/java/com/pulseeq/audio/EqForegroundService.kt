package com.pulseeq.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.os.Binder
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EqState(
    val isEnabled: Boolean = true,
    val preampDb: Float = -1.5f,
    val limiterEnabled: Boolean = true,
    val autoSwitchHardware: Boolean = true,
    val activePreset: String = "7Hz Zero:2 (Harman)",
    val connectedDevice: String = "Speaker / Default",
    // 10 ISO Parametric Center Frequencies: 31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k
    val bandGains: List<Float> = listOf(2.5f, 1.8f, 0.5f, -0.8f, -0.5f, 0.2f, 1.5f, 2.2f, 1.0f, 1.8f)
)

class EqForegroundService : Service() {

    private val binder = LocalBinder()
    private val _state = MutableStateFlow(EqState())
    val state = _state.asStateFlow()

    private val activeEffects = mutableMapOf<Int, DynamicsProcessing>()
    private lateinit var audioManager: AudioManager

    companion object {
        val FREQUENCIES = floatArrayOf(
            31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f
        )

        val PRESETS = mapOf(
            "7Hz Zero:2 (Harman)" to listOf(2.5f, 1.8f, 0.5f, -0.8f, -0.5f, 0.2f, 1.5f, 2.2f, 1.0f, 1.8f),
            "AirPods Pro 2 (Warm)" to listOf(3.0f, 2.5f, 1.2f, 0.0f, -0.5f, 0.5f, 1.2f, 1.8f, 2.5f, 2.0f),
            "Organic & Deep House" to listOf(4.2f, 3.8f, 2.0f, -1.2f, -1.5f, 0.0f, 1.5f, 2.8f, 3.5f, 3.0f),
            "Competitive Footsteps" to listOf(-6.0f, -4.5f, -2.0f, 1.5f, 3.0f, 4.5f, 5.5f, 4.0f, 1.0f, -2.0f),
            "Flat Reference" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        )
    }

    inner class LocalBinder : Binder() {
        fun getService(): EqForegroundService = this@EqForegroundService
    }

    // Intercepts audio sessions opened by Spotify, YouTube, Stremio, etc.
    private val audioSessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val sessionId = intent?.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0) ?: 0
            when (intent?.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> attachDspToSession(sessionId)
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> releaseSession(sessionId)
            }
        }
    }

    // Detects Wired IEMs / USB-C DAC vs Bluetooth connections automatically
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            evaluateConnectedHardware()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            evaluateConnectedHardware()
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        startForegroundNotification()

        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(audioSessionReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(audioSessionReceiver, filter)
        }

        audioManager.registerAudioDeviceCallback(deviceCallback, null)

        // Attach to global output mix (Session 0 fallback for MIUI/HyperOS)
        attachDspToSession(0)
        evaluateConnectedHardware()
    }

    private fun evaluateConnectedHardware() {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        var detectedName = "Phone Speaker"
        var autoPreset: String? = null

        for (dev in outputs) {
            when (dev.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE -> {
                    detectedName = "Wired IEM / USB DAC"
                    autoPreset = "7Hz Zero:2 (Harman)"
                    break
                }
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLE_HEADSET -> {
                    detectedName = "Bluetooth Audio (${dev.productName})"
                    autoPreset = "AirPods Pro 2 (Warm)"
                }
            }
        }

        _state.value = _state.value.copy(connectedDevice = detectedName)
        if (_state.value.autoSwitchHardware && autoPreset != null) {
            applyPreset(autoPreset)
        }
    }

    private fun attachDspToSession(sessionId: Int) {
        if (activeEffects.containsKey(sessionId)) return
        try {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                1,
                true, FREQUENCIES.size,
                false, 0,
                true, FREQUENCIES.size,
                true
            ).build()

            val dp = DynamicsProcessing(0, sessionId, config)
            dp.enabled = _state.value.isEnabled
            activeEffects[sessionId] = dp
            syncDspHardware()
        } catch (_: Exception) {
            // Device may block session 0 if global mix is locked; app-specific sessions still bind
        }
    }

    private fun releaseSession(sessionId: Int) {
        if (sessionId == 0) return
        activeEffects.remove(sessionId)?.release()
    }

    private fun syncDspHardware() {
        val current = _state.value
        val limiter = DynamicsProcessing.Limiter(
            true, current.limiterEnabled, 0,
            1.0f, // 1ms fast attack
            60.0f, // 60ms smooth release
            10.0f, // 10:1 brickwall ratio
            -0.2f, // Threshold just below 0dBFS
            0.0f
        )

        activeEffects.values.forEach { dp ->
            try {
                dp.enabled = current.isEnabled
                dp.setInputGainAllChannelsTo(current.preampDb)
                dp.setLimiterAllChannelsTo(limiter)

                FREQUENCIES.forEachIndexed { index, freq ->
                    val band = DynamicsProcessing.EqBand(true, freq, current.bandGains[index])
                    dp.setPreEqBandAllChannelsTo(index, band)
                    dp.setPostEqBandAllChannelsTo(index, DynamicsProcessing.EqBand(true, freq, 0f))
                }
            } catch (_: Exception) {}
        }
    }

    fun toggleMaster(enabled: Boolean) {
        _state.value = _state.value.copy(isEnabled = enabled)
        syncDspHardware()
    }

    fun setPreamp(db: Float) {
        _state.value = _state.value.copy(preampDb = db)
        syncDspHardware()
    }

    fun setBandGain(index: Int, gainDb: Float) {
        val updated = _state.value.bandGains.toMutableList()
        updated[index] = gainDb
        _state.value = _state.value.copy(bandGains = updated, activePreset = "Custom Curve")
        syncDspHardware()
    }

    fun applyPreset(name: String) {
        val gains = PRESETS[name] ?: return
        // Auto-adjust preamp to prevent clipping when boosting bass
        val maxBoost = gains.maxOrNull() ?: 0f
        val safePreamp = if (maxBoost > 0f) -maxBoost else 0f
        _state.value = _state.value.copy(
            bandGains = gains,
            activePreset = name,
            preampDb = safePreamp.coerceIn(-12f, 6f)
        )
        syncDspHardware()
    }

    fun toggleLimiter(enabled: Boolean) {
        _state.value = _state.value.copy(limiterEnabled = enabled)
        syncDspHardware()
    }

    fun toggleAutoSwitch(enabled: Boolean) {
        _state.value = _state.value.copy(autoSwitchHardware = enabled)
    }

    private fun startForegroundNotification() {
        val channelId = "pulseeq_dsp_channel"
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            channelId,
            "PulseEQ Hardware Engine",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)

        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("PulseEQ DSP Active")
            .setContentText("10-Band Parametric Engine Running")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(101, notification)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(audioSessionReceiver)
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        activeEffects.values.forEach { it.release() }
        activeEffects.clear()
    }
}