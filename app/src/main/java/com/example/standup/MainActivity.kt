package com.example.standup

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvTimer: TextView
    private lateinit var tvSensorDebug: TextView
    private lateinit var tvSitLabel: TextView
    private lateinit var tvStandLabel: TextView
    private lateinit var sbSitTime: SeekBar
    private lateinit var sbStandTime: SeekBar
    private lateinit var rgMode: RadioGroup
    private lateinit var rbPocket: RadioButton
    private lateinit var rbDesk: RadioButton
    private lateinit var btnToggle: Button

    private var isServiceRunning = false
    private lateinit var prefs: SharedPreferences

    // 接收后台Service发来的每秒更新
    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            tvStatus.text = intent.getStringExtra("STATUS")
            tvSensorDebug.text = intent.getStringExtra("DEBUG")
            isServiceRunning = intent.getBooleanExtra("IS_RUNNING", false)

            val sec = intent.getIntExtra("TIME_SEC", 0)
            tvTimer.text = String.format("%02d:%02d", sec / 60, sec % 60)

            updateUIState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 请求通知权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        initViews()
        loadSettings()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvTimer = findViewById(R.id.tvTimer)
        tvSensorDebug = findViewById(R.id.tvSensorDebug)
        tvSitLabel = findViewById(R.id.tvSitLabel)
        tvStandLabel = findViewById(R.id.tvStandLabel)
        sbSitTime = findViewById(R.id.sbSitTime)
        sbStandTime = findViewById(R.id.sbStandTime)
        rgMode = findViewById(R.id.rgMode)
        rbPocket = findViewById(R.id.rbPocket)
        rbDesk = findViewById(R.id.rbDesk)
        btnToggle = findViewById(R.id.btnToggle)

        sbSitTime.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val min = if (progress < 1) 1 else progress
                tvSitLabel.text = "久坐时长: $min 分钟"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) { saveSettings() }
        })

        sbStandTime.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val min = if (progress < 1) 1 else progress
                tvStandLabel.text = "站立时长: $min 分钟"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) { saveSettings() }
        })

        rgMode.setOnCheckedChangeListener { _, _ -> saveSettings() }

        btnToggle.setOnClickListener {
            if (isServiceRunning) {
                stopStandUpService()
            } else {
                startStandUpService()
            }
        }
    }

    private fun startStandUpService() {
        saveSettings()
        val sitMin = if (sbSitTime.progress < 1) 1 else sbSitTime.progress
        val standMin = if (sbStandTime.progress < 1) 1 else sbStandTime.progress

        val intent = Intent(this, StandUpService::class.java).apply {
            putExtra("SIT_MIN", sitMin)
            putExtra("STAND_MIN", standMin)
            putExtra("POCKET_MODE", rbPocket.isChecked)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        isServiceRunning = true
        updateUIState()
    }

    private fun stopStandUpService() {
        val intent = Intent(this, StandUpService::class.java).apply { action = "STOP" }
        startService(intent)
        isServiceRunning = false
        tvStatus.text = "已停止"
        tvTimer.text = "00:00"
        tvSensorDebug.text = "传感器未激活"
        updateUIState()
    }

    private fun updateUIState() {
        sbSitTime.isEnabled = !isServiceRunning
        sbStandTime.isEnabled = !isServiceRunning
        rbPocket.isEnabled = !isServiceRunning
        rbDesk.isEnabled = !isServiceRunning

        if (isServiceRunning) {
            btnToggle.text = "停 止 监 控"
            btnToggle.setBackgroundColor(Color.parseColor("#D32F2F")) // 红色
        } else {
            btnToggle.text = "开 始 监 控"
            btnToggle.setBackgroundColor(Color.parseColor("#1976D2")) // 蓝色
        }
    }

    private fun loadSettings() {
        prefs = getSharedPreferences("StandUpPrefs", Context.MODE_PRIVATE)
        val sitMin = prefs.getInt("SIT_MIN", 45)
        val standMin = prefs.getInt("STAND_MIN", 2)
        val isPocket = prefs.getBoolean("POCKET_MODE", true)

        sbSitTime.progress = sitMin
        sbStandTime.progress = standMin
        tvSitLabel.text = "久坐时长: $sitMin 分钟"
        tvStandLabel.text = "站立时长: $standMin 分钟"
        if (isPocket) rbPocket.isChecked = true else rbDesk.isChecked = true
    }

    private fun saveSettings() {
        val sitMin = if (sbSitTime.progress < 1) 1 else sbSitTime.progress
        val standMin = if (sbStandTime.progress < 1) 1 else sbStandTime.progress
        prefs.edit().apply {
            putInt("SIT_MIN", sitMin)
            putInt("STAND_MIN", standMin)
            putBoolean("POCKET_MODE", rbPocket.isChecked)
            apply()
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("STANDUP_UPDATE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(updateReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(updateReceiver)
    }
}