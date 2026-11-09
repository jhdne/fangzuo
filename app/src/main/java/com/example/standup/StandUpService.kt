package com.example.standup

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import androidx.core.app.NotificationCompat
import kotlin.math.atan2
import kotlin.math.sqrt

class StandUpService : Service(), SensorEventListener {

    // 5个核心状态
    enum class State { IDLE, SITTING_TIMER, ALERTING, STANDING_TIMER, WAITING_TO_SIT }
    private var currentState = State.IDLE

    private var sitDurationSec = 45 * 60
    private var standDurationSec = 2 * 60
    private var isPocketMode = true

    private var remainingSitSec = 0
    private var remainingStandSec = 0

    // 姿态判定防抖计数器
    private var standConfirmSec = 0
    private var sitConfirmSec = 0
    private var isCurrentlyStanding = false
    private var debugSensorText = "传感器未激活 (省电中)"

    private lateinit var sensorManager: SensorManager
    private lateinit var vibrator: Vibrator
    private lateinit var wakeLock: PowerManager.WakeLock
    private val handler = Handler(Looper.getMainLooper())

    // 每秒执行一次的心跳循环
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
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        // 获取唤醒锁，防止锁屏后CPU休眠
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StandUp::WakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopService()
            return START_NOT_STICKY
        }

        sitDurationSec = intent?.getIntExtra("SIT_MIN", 45)?.times(60) ?: (45 * 60)
        standDurationSec = intent?.getIntExtra("STAND_MIN", 2)?.times(60) ?: (2 * 60)
        isPocketMode = intent?.getBooleanExtra("POCKET_MODE", true) ?: true

        if (!wakeLock.isHeld) wakeLock.acquire()
        startForeground(1, createNotification("监控运行中..."))

        // 进入【状态1：久坐计时】
        transitionTo(State.SITTING_TIMER)
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)

        return START_STICKY
    }

    // 状态流转控制器
    private fun transitionTo(newState: State) {
        currentState = newState
        standConfirmSec = 0
        sitConfirmSec = 0

        when (newState) {
            State.SITTING_TIMER -> {
                stopVibration()
                stopSensors() // 久坐计时期间关闭传感器省电
                remainingSitSec = sitDurationSec
                remainingStandSec = standDurationSec // 重置站立时间
            }
            State.ALERTING -> {
                wakeUpScreen() // 唤醒屏幕确保传感器工作
                startSensors()
                startContinuousVibration()
            }
            State.STANDING_TIMER -> {
                stopVibration() // 站起来了，立刻停止震动
            }
            State.WAITING_TO_SIT -> {
                stopVibration()
                playSuccessVibration() // 叮叮两声短震提示达标
            }
            else -> {}
        }
        broadcastUpdate()
    }

    // 每秒钟的核心逻辑判断
    private fun onTick() {
        when (currentState) {
            State.SITTING_TIMER -> {
                remainingSitSec--
                if (remainingSitSec <= 0) {
                    transitionTo(State.ALERTING)
                }
            }
            State.ALERTING -> {
                if (isCurrentlyStanding) {
                    standConfirmSec++
                    // 持续站立/活动 2 秒，解除警报，进入站立倒计时
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
                    // 防作弊：检测是否提前坐下
                    sitConfirmSec++
                    if (sitConfirmSec >= 3) {
                        // 坐下超过3秒，退回警报状态继续震动（保留剩余站立时间）
                        transitionTo(State.ALERTING)
                    }
                }
            }
            State.WAITING_TO_SIT -> {
                if (!isCurrentlyStanding) {
                    sitConfirmSec++
                    // 达标后重新坐下超过 4 秒，自动开启下一轮
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

    // 传感器算法实现
    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        if (isPocketMode && event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            // 裤兜模式：计算手机与水平面的倾斜角 (0度为平放，90度为竖直)
            val xy = sqrt(x * x + y * y)
            val angle = atan2(xy, kotlin.math.abs(z)) * (180 / Math.PI)

            if (angle > 65) isCurrentlyStanding = true
            else if (angle < 35) isCurrentlyStanding = false
            debugSensorText = "当前角度: ${angle.toInt()}° (${if (isCurrentlyStanding) "站立" else "坐下"})"

        } else if (!isPocketMode && event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            // 桌面模式：计算去重力后的纯移动加速度
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

    private fun startContinuousVibration() {
        val pattern = longArrayOf(0, 800, 400) // 震0.8秒，停0.4秒
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            vibrator.vibrate(pattern, 0)
        }
    }

    private fun playSuccessVibration() {
        val pattern = longArrayOf(0, 150, 100, 150) // 轻快短震两下
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            vibrator.vibrate(pattern, -1)
        }
    }

    private fun stopVibration() {
        vibrator.cancel()
    }

    private fun wakeUpScreen() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val screenLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "StandUp::ScreenWake")
        screenLock.acquire(3000)
    }

    private fun broadcastUpdate() {
        val intent = Intent("STANDUP_UPDATE")
        intent.setPackage(packageName)
        val statusText = when (currentState) {
            State.IDLE -> "待机中"
            State.SITTING_TIMER -> "久坐计时中..."
            State.ALERTING -> "⚠️ 请立刻站起来！"
            State.STANDING_TIMER -> "保持站立/活动..."
            State.WAITING_TO_SIT -> "✅ 达标！请坐下开启下一轮"
        }
        val timeSec = if (currentState == State.SITTING_TIMER) remainingSitSec else remainingStandSec

        intent.putExtra("STATUS", statusText)
        intent.putExtra("TIME_SEC", timeSec)
        intent.putExtra("DEBUG", debugSensorText)
        intent.putExtra("IS_RUNNING", currentState != State.IDLE)
        sendBroadcast(intent)
    }

    private fun createNotification(text: String): Notification {
        val channelId = "standup_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "防久坐服务", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("起立 - 防久坐监控中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
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