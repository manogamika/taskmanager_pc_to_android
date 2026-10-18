package moqbit.project.pcstat

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DashboardFragment : Fragment() {

    private lateinit var tvTime: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvCpuUsage: TextView
    private lateinit var tvRamUsage: TextView
    private lateinit var tvTemperature: TextView
    private lateinit var tvBatteryUsage: TextView
    private lateinit var tvFpsMobile: TextView

    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()
    private var currentFps = 60

    private val choreographerCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameCount++
            val now = System.currentTimeMillis()
            if (now - lastFpsTime >= 1000) {
                currentFps = frameCount
                frameCount = 0
                lastFpsTime = now
                if (isAdded && ::tvFpsMobile.isInitialized) {
                    tvFpsMobile.text = "$currentFps FPS"
                }
            }
            if (isAdded) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateStats()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvTime = view.findViewById(R.id.tvTime)
        tvDate = view.findViewById(R.id.tvDate)
        tvCpuUsage = view.findViewById(R.id.tvCpuUsage)
        tvRamUsage = view.findViewById(R.id.tvRamUsage)
        tvTemperature = view.findViewById(R.id.tvTemperature)
        tvBatteryUsage = view.findViewById(R.id.tvBatteryUsage)
        tvFpsMobile = view.findViewById(R.id.tvFpsMobile)

        val btnBurger = view.findViewById<ImageButton>(R.id.btnBurger)
        btnBurger?.setOnClickListener { anchor ->
            val popup = PopupMenu(requireContext(), anchor)
            popup.menu.add(0, 1, 0, "🔄 Refresh Status HP")
            popup.menu.add(0, 2, 1, "⚙️ Pengaturan / Config")

            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        updateStats()
                        Toast.makeText(requireContext(), "Status HP Diperbarui", Toast.LENGTH_SHORT).show()
                        true
                    }
                    2 -> {
                        val intent = Intent(requireContext(), ConfigActivity::class.java)
                        startActivity(intent)
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(updateRunnable)
        frameCount = 0
        lastFpsTime = System.currentTimeMillis()
        Choreographer.getInstance().postFrameCallback(choreographerCallback)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)
        Choreographer.getInstance().removeFrameCallback(choreographerCallback)
    }

    private fun updateStats() {
        if (!isAdded) return

        val now = Date()
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now)
        val dateStr = SimpleDateFormat("EEEE, dd MMM yyyy", Locale.getDefault()).format(now)

        tvTime.text = timeStr
        tvDate.text = dateStr

        val batteryIntent = requireContext().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (batteryIntent != null) {
            val tempRaw = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            val tempC = tempRaw / 10.0
            tvTemperature.text = "$tempC °C"

            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 0

            val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            val chargingStatusStr = if (isCharging) " (Charging)" else ""
            tvBatteryUsage.text = "$batteryPct%$chargingStatusStr"
        }

        val activityManager = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val totalRamMb = memoryInfo.totalMem / (1024 * 1024)
        val availableRamMb = memoryInfo.availMem / (1024 * 1024)
        val usedRamMb = totalRamMb - availableRamMb
        val ramPercent = if (totalRamMb > 0) (usedRamMb * 100 / totalRamMb) else 0

        tvRamUsage.text = "$usedRamMb MB / $totalRamMb MB ($ramPercent%)"

        val cpuSimulated = 10 + (Math.sin(System.currentTimeMillis() / 1000.0) * 10 + 15).toInt()
        tvCpuUsage.text = "$cpuSimulated%"
    }
}
