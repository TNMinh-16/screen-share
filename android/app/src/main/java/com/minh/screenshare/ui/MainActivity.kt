package com.minh.screenshare.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.minh.screenshare.R
import com.minh.screenshare.config.VideoConfig
import com.minh.screenshare.databinding.ActivityMainBinding
import com.minh.screenshare.service.ScreenStreamService
import com.minh.screenshare.state.AppState
import com.minh.screenshare.state.StreamState

/**
 * Màn hình chính MainActivity:
 * - Giao diện đơn giản, sử dụng ViewBinding.
 * - Quản lý nhập IP/Port và lưu trữ SharedPreferences.
 * - Xin quyền POST_NOTIFICATIONS (Android 13+) và quyền quay màn hình qua MediaProjectionManager.
 * - Điều khiển Start/Stop Service và lắng nghe trạng thái LiveData từ AppState.
 * - Hỗ trợ nút mở cài đặt pin cho máy Oppo ColorOS.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Launcher xin quyền quay màn hình hệ thống
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val ip = binding.etServerIp.text?.toString()?.trim().orEmpty()
            val portStr = binding.etServerPort.text?.toString()?.trim().orEmpty()
            val port = portStr.toIntOrNull() ?: VideoConfig.DEFAULT_PORT

            // Lưu cấu hình vào SharedPreferences
            savePreferences(ip, port)

            // Bắt đầu Foreground Service
            val serviceIntent = Intent(this, ScreenStreamService::class.java).apply {
                action = ScreenStreamService.ACTION_START
                putExtra(ScreenStreamService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenStreamService.EXTRA_DATA, result.data)
                putExtra(ScreenStreamService.EXTRA_HOST, ip)
                putExtra(ScreenStreamService.EXTRA_PORT, port)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            Toast.makeText(this, "Bạn đã từ chối cấp quyền quay màn hình!", Toast.LENGTH_SHORT).show()
            AppState.updateState(StreamState.Idle)
        }
    }

    // Launcher xin quyền thông báo trên Android 13+
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Dù người dùng đồng ý hay từ chối, ứng dụng vẫn tiếp tục bước xin quyền quay màn hình
        requestScreenCapturePermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSavedPreferences()
        setupListeners()
        observeStreamState()
    }

    private fun loadSavedPreferences() {
        val prefs = getSharedPreferences(VideoConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val savedIp = prefs.getString(VideoConfig.PREF_KEY_IP, "")
        val savedPort = prefs.getInt(VideoConfig.PREF_KEY_PORT, VideoConfig.DEFAULT_PORT)

        if (!savedIp.isNullOrEmpty()) {
            binding.etServerIp.setText(savedIp)
        }
        binding.etServerPort.setText(savedPort.toString())
    }

    private fun savePreferences(ip: String, port: Int) {
        val prefs = getSharedPreferences(VideoConfig.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(VideoConfig.PREF_KEY_IP, ip)
            .putInt(VideoConfig.PREF_KEY_PORT, port)
            .apply()
    }

    private fun setupListeners() {
        // Nút Bắt đầu / Dừng
        binding.btnToggleStream.setOnClickListener {
            val currentState = AppState.streamState.value
            if (currentState is StreamState.Streaming || currentState is StreamState.Connecting) {
                // Đang chạy -> Bấm để Dừng
                val stopIntent = Intent(this, ScreenStreamService::class.java).apply {
                    action = ScreenStreamService.ACTION_STOP
                }
                startService(stopIntent)
            } else {
                // Đang dừng -> Bấm để Bắt đầu
                startStreamFlow()
            }
        }

        // Nút mở cài đặt Pin (hỗ trợ riêng cho máy Oppo / ColorOS)
        binding.btnBatterySettings.setOnClickListener {
            openBatterySettings()
        }
    }

    private fun startStreamFlow() {
        val ip = binding.etServerIp.text?.toString()?.trim().orEmpty()
        if (ip.isEmpty()) {
            binding.etServerIp.error = "Vui lòng nhập IP máy tính hiển thị trên màn hình PC!"
            return
        }

        // 1. Trên Android 13+ (API 33), kiểm tra quyền thông báo trước
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        // 2. Tiến hành xin quyền quay màn hình
        requestScreenCapturePermission()
    }

    private fun requestScreenCapturePermission() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun observeStreamState() {
        AppState.streamState.observe(this) { state ->
            when (state) {
                is StreamState.Idle -> {
                    binding.tvStatus.text = getString(R.string.status_idle)
                    binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                    binding.btnToggleStream.text = getString(R.string.btn_start)
                    binding.btnToggleStream.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
                    binding.etServerIp.isEnabled = true
                    binding.etServerPort.isEnabled = true
                }
                is StreamState.Connecting -> {
                    binding.tvStatus.text = getString(R.string.status_connecting)
                    binding.tvStatus.setTextColor(Color.parseColor("#FFC107")) // Vàng
                    binding.btnToggleStream.text = getString(R.string.btn_stop)
                    binding.btnToggleStream.setBackgroundColor(ContextCompat.getColor(this, R.color.stop_red))
                    binding.etServerIp.isEnabled = false
                    binding.etServerPort.isEnabled = false
                }
                is StreamState.Streaming -> {
                    binding.tvStatus.text = "Trạng thái: Đang truyền trực tiếp tới ${state.ip}:${state.port}"
                    binding.tvStatus.setTextColor(Color.parseColor("#4CAF50")) // Xanh lá
                    binding.btnToggleStream.text = getString(R.string.btn_stop)
                    binding.btnToggleStream.setBackgroundColor(ContextCompat.getColor(this, R.color.stop_red))
                    binding.etServerIp.isEnabled = false
                    binding.etServerPort.isEnabled = false
                }
                is StreamState.Error -> {
                    binding.tvStatus.text = "Lỗi: ${state.message}"
                    binding.tvStatus.setTextColor(Color.parseColor("#EF5350")) // Đỏ
                    binding.btnToggleStream.text = getString(R.string.btn_start)
                    binding.btnToggleStream.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
                    binding.etServerIp.isEnabled = true
                    binding.etServerPort.isEnabled = true
                }
            }
        }
    }

    /**
     * Mở cài đặt tiết kiệm pin để người dùng tắt tối ưu hóa (tránh Oppo tắt app chạy ngầm).
     */
    private fun openBatterySettings() {
        try {
            // Mở trực tiếp màn hình chi tiết ứng dụng
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
            Toast.makeText(this, "Hãy chọn mục 'Pin' -> bật 'Cho phép hoạt động dưới nền'", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            try {
                // Fallback mở cài đặt bỏ qua tối ưu pin
                val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(fallbackIntent)
            } catch (e: Exception) {
                Toast.makeText(this, "Không thể mở cài đặt pin: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
