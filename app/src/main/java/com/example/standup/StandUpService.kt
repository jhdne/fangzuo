package com.example.standup

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
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

    // 使用硬件级时间戳，防止熄屏后读秒变慢或卡住
    private var sitEndTimeMillis = 0L
    private var remainingSitSec = 0
    private var remainingStandSec = 0

    private var standConfirmSec = 0
    private var sitConfirmSec = 0
    private var isCurrentlyStanding = false
    private var debugSensorText = "传感器未激活 (省电中)"

    private lateinit var sensorManager: SensorManager
    private lateinit var vibrator: Vibrator
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var notificationManager: NotificationManager
    private val handler = Handler(Looper.getMainLooper())

    private val tickRunnable = object : Runnable {
        override fun run() {
            onTick()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StandUp::WakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopService()
            return START_NOT_STICKY
        }

        // 如果已经在运行且只是重新打开界面，不重置计时
        if (currentState != State.IDLE && intent?.hasExtra("SIT_MIN") == false) {
            return START_STICKY
        }

        sitDurationSec = intent?.getIntExtra("SIT_MIN", 45)?.times(60) ?: (45 * 60)
        standDurationSec = intent?.getIntExtra("STAND_MIN", 2)?.times(60) ?: (2 * 60)
        isPocketMode = intent?.getBooleanExtra("POCKET_MODE", true) ?: true

        if (!wakeLock.isHeld) wakeLock.acquire()
        startForeground(1, createNotification("监控启动中..."))

        transitionTo(State.SITTING_TIMER)
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)

        return START_STICKY
    }

    // 防止用户从最近任务列表“划掉”APP时服务被杀
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 保持服务继续运行，不做销毁
    }

    private fun transitionTo(newState: State) {
        currentState = newState
        standConfirmSec = 0
        sitConfirmSec = 0

        when (newState) {
            State.SITTING_TIMER -> {
                stopVibration()
                stopSensors()
                // 记录未来的硬件目标时间点
                sitEndTimeMillis = SystemClock.elapsedRealtime() + (sitDurationSec * 1000L)
                remainingSitSec = sitDurationSec
                remainingStandSec = standDurationSec
            }
            State.ALERTING -> {
                wakeUpScreen()
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
        broadcastUpdate()
    }

    private fun onTick() {
        when (currentState) {
            State.SITTING_TIMER -> {
                // 用硬件时钟相减，哪怕CPU休眠也不会漏算时间
                val diff = sitEndTimeMillis - SystemClock.elapsedRealtime()
                remainingSitSec = (diff / 1000).toInt()
                if (remainingSitSec <= 0) {
                    remainingSitSec = 0
                    transitionTo(State.ALERTING)
                }
            }
            State.ALERTING -> {
                // 每隔5秒重新触发一次震动，防止部分手机熄屏自动掐断震动
                if (standConfirmSec == 0) startContinuousVibration()
                
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
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun stopSensors() {
        sensorManager.unregisterListener(this)
        debugSensorText = "传感器未激活 (省电中)"
    }

    // 关键修复：使用 USAGE_ALARM 闹钟通道，允许在后台和熄屏状态下强制震动！
    private fun startContinuousVibration() {
        val pattern = longArrayOf(0, 800, 400)
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ALARM) // 伪装成系统闹钟
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

    private fun wakeUpScreen() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val screenLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "StandUp::ScreenWake")
        screenLock.acquire(5000)
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

        // 实时刷新锁屏通知栏，防止系统把服务当成僵尸进程杀掉
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
        val channelId = "standup_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "防久坐实时状态", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("起立 - 防久坐监控")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true) // 设置为不可划掉的常驻通知
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun stopService() {
        handler.removeCallbacks(tickRunnable)
        stopVibration()
        stopSensors()
        if (wakeLock.isHeld) wakeLock.release()
        currentState = State.IDLE
        broadcastUpdate()
        stopForeground(true)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
