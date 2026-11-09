package com.example.standup

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.*
import androidx.core.app.NotificationCompat
import kotlin.math.atan2
import kotlin.math.sqrt

class StandUpService : Service(), SensorEventListener {

    enum class State { IDLE, SITTING_TIMER, ALERTING, STANDING_TIMER, WAITING_TO_SIT }
    private var currentState = State.IDLE

    private var sitDurationSec = 45 * 60
    private var standDurationSec = 2 * 60
    private var isPocketMode = true

    private var sitEndTimeMillis = 0L
    private var remainingSitSec = 0
    private var remainingStandSec = 0

    private var standConfirmSec = 0
    private var sitConfirmSec = 0
    private var isCurrentlyStanding = false
    private var debugSensorText = "传感器未激活 (省电中)"

    private lateinit var prefs: SharedPreferences
    private lateinit var sensorManager: SensorManager
    private lateinit var vibrator: Vibrator
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var notificationManager: NotificationManager
    private lateinit var alarmManager: AlarmManager
    private var silentAudioTrack: AudioTrack? = null

    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            onTick()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("StandUpPrefs", Context.MODE_PRIVATE)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StandUp::WakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        // 1. 用户主动点击停止
        if (action == "STOP") {
            stopServiceCompletely()
            return START_NOT_STICKY
        }

        // 启动前台通知与唤醒锁与无声音乐保活
        startForeground(1, createNotification("正在后台强力运行中..."))
        if (!wakeLock.isHeld) wakeLock.acquire()
        startSilentAudioLoop()

        // 2. 用户点击“开始监控”传入了新参数
        if (action == "START_NEW") {
            sitDurationSec = intent.getIntExtra("SIT_MIN", 45) * 60
            standDurationSec = intent.getIntExtra("STAND_MIN", 2) * 60
            isPocketMode = intent.getBooleanExtra("POCKET_MODE", true)
            prefs.edit().putBoolean("IS_RUNNING", true).apply()
            transitionTo(State.SITTING_TIMER)
        } 
        // 3. 被划掉卡片后自动复活、或者被硬件闹钟唤醒，从硬盘恢复进度！
        else {
            val wasRunning = prefs.getBoolean("IS_RUNNING", false)
            if (!wasRunning) {
                stopServiceCompletely()
                return START_NOT_STICKY
            }
            if (currentState == State.IDLE) {
                restoreStateFromDisk()
            }
        }

        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)

        return START_STICKY
    }

    // 核心黑科技1：生成内存级 0分贝 静音音频循环播放，彻底阻止系统熄屏冻结CPU！
    private fun startSilentAudioLoop() {
        if (silentAudioTrack != null) return
        try {
            val sampleRate = 8000
            val bufferSize = sampleRate * 2 // 1秒钟的 16-bit 单声道数据
            val silentBuffer = ByteArray(bufferSize) // 全是0，绝对静音

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            silentAudioTrack = AudioTrack(
                audioAttributes, audioFormat, bufferSize,
                AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE
            ).apply {
                write(silentBuffer, 0, bufferSize)
                setLoopPoints(0, sampleRate, -1) // 无限硬件循环，不占CPU
                play()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopSilentAudioLoop() {
        try {
            silentAudioTrack?.stop()
            silentAudioTrack?.release()
        } catch (e: Exception) {}
        silentAudioTrack = null
    }

    // 核心黑科技2：当卡片被划掉时，立即保存进度，并定一个1秒后的硬件闹钟把自己复活！
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (prefs.getBoolean("IS_RUNNING", false)) {
            saveStateToDisk()
            scheduleHardwareAlarm(System.currentTimeMillis() + 1000L)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (prefs.getBoolean("IS_RUNNING", false)) {
            saveStateToDisk()
            scheduleHardwareAlarm(System.currentTimeMillis() + 1000L)
        }
    }

    // 注册系统级硬件闹钟（即使进程死透，时间一到也会被系统强制拉起）
    private fun scheduleHardwareAlarm(triggerAtWallTimeMillis: Long) {
        val intent = Intent(this, StandUpService::class.java).apply { action = "ALARM_WAKEUP" }
        val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, 999, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.getService(this, 999, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val showIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val clockInfo = AlarmManager.AlarmClockInfo(triggerAtWallTimeMillis, showIntent)
        alarmManager.setAlarmClock(clockInfo, pi)
    }

    private fun cancelHardwareAlarm() {
        val intent = Intent(this, StandUpService::class.java).apply { action = "ALARM_WAKEUP" }
        val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, 999, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.getService(this, 999, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        alarmManager.cancel(pi)
    }

    private fun saveStateToDisk() {
        prefs.edit().apply {
            putString("SAVED_STATE", currentState.name)
            putLong("SIT_END_MILLIS", sitEndTimeMillis)
            putInt("REM_STAND_SEC", remainingStandSec)
            putInt("SIT_DUR_SEC", sitDurationSec)
            putInt("STAND_DUR_SEC", standDurationSec)
            putBoolean("POCKET_MODE", isPocketMode)
            apply()
        }
    }

    private fun restoreStateFromDisk() {
        sitDurationSec = prefs.getInt("SIT_DUR_SEC", 45 * 60)
        standDurationSec = prefs.getInt("STAND_DUR_SEC", 2 * 60)
        isPocketMode = prefs.getBoolean("POCKET_MODE", true)
        sitEndTimeMillis = prefs.getLong("SIT_END_MILLIS", System.currentTimeMillis() + 60000L)
        remainingStandSec = prefs.getInt("REM_STAND_SEC", standDurationSec)

        val savedStateName = prefs.getString("SAVED_STATE", State.SITTING_TIMER.name) ?: State.SITTING_TIMER.name
        currentState = State.valueOf(savedStateName)

        if (currentState == State.SITTING_TIMER) {
            val diff = sitEndTimeMillis - System.currentTimeMillis()
            if (diff <= 0) {
                transitionTo(State.ALERTING)
            } else {
                scheduleHardwareAlarm(sitEndTimeMillis)
            }
        } else if (currentState == State.ALERTING) {
            transitionTo(State.ALERTING)
        } else if (currentState == State.STANDING_TIMER || currentState == State.WAITING_TO_SIT) {
            startSensors()
        }
    }

    private fun transitionTo(newState: State) {
        currentState = newState
        standConfirmSec = 0
        sitConfirmSec = 0

        when (newState) {
            State.SITTING_TIMER -> {
                stopVibration()
                stopSensors()
                sitEndTimeMillis = System.currentTimeMillis() + (sitDurationSec * 1000L)
                remainingSitSec = sitDurationSec
                remainingStandSec = standDurationSec
                // 设定久坐结束时的硬件闹钟保底
                scheduleHardwareAlarm(sitEndTimeMillis)
            }
            State.ALERTING -> {
                cancelHardwareAlarm()
                popupActivityOverLockScreen() // 破壁点亮锁屏并弹出APP界面
                startSensors()
                startContinuousVibration()
            }
            State.STANDING_TIMER -> {
                stopVibration()
            }
            State.WAITING_TO_SIT -> {
                stopVibration()
                playSuccessVibration()
            }
            else -> {}
        }
        saveStateToDisk()
        broadcastUpdate()
    }

    private fun onTick() {
        when (currentState) {
            State.SITTING_TIMER -> {
                val diff = sitEndTimeMillis - System.currentTimeMillis()
                remainingSitSec = (diff / 1000).toInt()
                if (remainingSitSec <= 0) {
                    remainingSitSec = 0
                    transitionTo(State.ALERTING)
                }
            }
            State.ALERTING -> {
                // 持续触发震动
                startContinuousVibration()
                if (isCurrentlyStanding) {
                    standConfirmSec++
                    if (standConfirmSec >= 2) {
                        transitionTo(State.STANDING_TIMER)
                    }
                } else {
                    standConfirmSec = 0
                }
            }
            State.STANDING_TIMER -> {
                if (isCurrentlyStanding) {
                    sitConfirmSec = 0
                    remainingStandSec--
                    saveStateToDisk()
                    if (remainingStandSec <= 0) {
                        transitionTo(State.WAITING_TO_SIT)
                    }
                } else {
                    sitConfirmSec++
                    if (sitConfirmSec >= 3) {
                        transitionTo(State.ALERTING)
                    }
                }
            }
            State.WAITING_TO_SIT -> {
                if (!isCurrentlyStanding) {
                    sitConfirmSec++
                    if (sitConfirmSec >= 4) {
                        transitionTo(State.SITTING_TIMER)
                    }
                } else {
                    sitConfirmSec = 0
                }
            }
            else -> {}
        }
        broadcastUpdate()
    }

    // 核心黑科技3：时间一到，直接点亮锁屏并把界面弹到最前面，彻底解除后台传感器与震动限制！
    private fun popupActivityOverLockScreen() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val screenLock = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "StandUp::PopupWake"
            )
            screenLock.acquire(10000)

            val popupIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(popupIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        if (isPocketMode && event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val xy = sqrt(x * x + y * y)
            val angle = atan2(xy, kotlin.math.abs(z)) * (180 / Math.PI)

            if (angle > 65) isCurrentlyStanding = true
            else if (angle < 35) isCurrentlyStanding = false
            debugSensorText = "当前角度: ${angle.toInt()}° (${if (isCurrentlyStanding) "站立" else "坐下"})"

        } else if (!isPocketMode && event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            val acc = sqrt(x * x + y * y + z * z)
            if (acc > 1.2) isCurrentlyStanding = true
            else if (acc < 0.4) isCurrentlyStanding = false
            debugSensorText = "运动强度: ${String.format("%.1f", acc)} (${if (isCurrentlyStanding) "活动中" else "静止"})"
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun startSensors() {
        val sensorType = if (isPocketMode) Sensor.TYPE_ACCELEROMETER else Sensor.TYPE_LINEAR_ACCELERATION
        val sensor = sensorManager.getDefaultSensor(sensorType)
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    private fun stopSensors() {
        sensorManager.unregisterListener(this)
        debugSensorText = "传感器未激活 (省电中)"
    }

    private fun startContinuousVibration() {
        val pattern = longArrayOf(0, 800, 300)
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ALARM)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0), audioAttributes)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, 0, audioAttributes)
        }
    }

    private fun playSuccessVibration() {
        val pattern = longArrayOf(0, 150, 100, 150)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1), audioAttributes)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1, audioAttributes)
        }
    }

    private fun stopVibration() {
        vibrator.cancel()
    }

    private fun broadcastUpdate() {
        val statusText = when (currentState) {
            State.IDLE -> "待机中"
            State.SITTING_TIMER -> "久坐计时中..."
            State.ALERTING -> "⚠️ 请立刻站起来！"
            State.STANDING_TIMER -> "保持站立/活动..."
            State.WAITING_TO_SIT -> "✅ 达标！请坐下开启下一轮"
        }
        val timeSec = if (currentState == State.SITTING_TIMER) remainingSitSec else remainingStandSec
        val timeStr = String.format("%02d:%02d", timeSec / 60, timeSec % 60)

        if (currentState != State.IDLE) {
            notificationManager.notify(1, createNotification("$statusText ($timeStr)"))
        }

        val intent = Intent("STANDUP_UPDATE")
        intent.setPackage(packageName)
        intent.putExtra("STATUS", statusText)
        intent.putExtra("TIME_SEC", timeSec)
        intent.putExtra("DEBUG", debugSensorText)
        intent.putExtra("IS_RUNNING", currentState != State.IDLE)
        sendBroadcast(intent)
    }

    private fun createNotification(text: String): Notification {
        val channelId = "standup_keepalive_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "防久坐核心服务", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
        val fullScreenIntent = Intent(this, MainActivity::class.java)
        val fullScreenPendingIntent = PendingIntent.getActivity(this, 0, fullScreenIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("起立 - 防久坐运行中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .build()
    }

    private fun stopServiceCompletely() {
        prefs.edit().putBoolean("IS_RUNNING", false).apply()
        handler.removeCallbacks(tickRunnable)
        cancelHardwareAlarm()
        stopVibration()
        stopSensors()
        stopSilentAudioLoop()
        if (wakeLock.isHeld) wakeLock.release()
        currentState = State.IDLE
        broadcastUpdate()
        stopForeground(true)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
