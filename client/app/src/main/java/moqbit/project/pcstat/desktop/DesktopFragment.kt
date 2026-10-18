package moqbit.project.pcstat.desktop

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import moqbit.project.pcstat.ConfigActivity
import moqbit.project.pcstat.R
import java.util.Locale
import java.util.concurrent.Executors

class DesktopFragment : Fragment() {

    private lateinit var layoutNotConnected: LinearLayout
    private lateinit var layoutDesktopStats: LinearLayout
    private var progressBarDesktopLoading: ProgressBar? = null

    private lateinit var btnScanQr: Button
    private lateinit var btnManualIpDesktop: Button

    private lateinit var tvDesktopStatus: TextView
    private lateinit var tvPcHost: TextView
    private lateinit var tvCpuUsageDesktop: TextView
    private lateinit var tvCpuTempDesktop: TextView
    private lateinit var tvRamUsageDesktop: TextView
    private lateinit var tvGpuName: TextView
    private lateinit var tvGpuUsage: TextView
    private lateinit var tvNetworkDesktop: TextView
    private lateinit var tvFpsDesktop: TextView
    private lateinit var tvDriveC: TextView
    private lateinit var tvVolumeValue: TextView
    private lateinit var seekBarVolume: SeekBar

    private var isUserDraggingVolume = false
    private var lastVolumeSendTime = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private val volumeSendRunnable = Runnable {
        if (::seekBarVolume.isInitialized) {
            val currentProgress = seekBarVolume.progress
            sendVolumeToPc(currentProgress)
        }
    }

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val rawText = result.contents
            val formatted = if (rawText.startsWith("http://") || rawText.startsWith("https://")) rawText else "http://$rawText"
            val statsUrl = if (formatted.endsWith("/stats")) formatted else "$formatted/stats"
            val context = context ?: return@registerForActivityResult
            val prefs = context.getSharedPreferences("pcstat_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("server_url", statsUrl).apply()

            progressBarDesktopLoading?.visibility = View.VISIBLE
            updateDesktopStats()
            Toast.makeText(requireContext(), "Kode QR Berhasil Dipindai!", Toast.LENGTH_SHORT).show()
        }
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateDesktopStats()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_desktop, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        layoutNotConnected = view.findViewById<LinearLayout>(R.id.layoutNotConnected)
        layoutDesktopStats = view.findViewById<LinearLayout>(R.id.layoutDesktopStats)
        progressBarDesktopLoading = view.findViewById<ProgressBar>(R.id.progressBarDesktopLoading)

        btnScanQr = view.findViewById<Button>(R.id.btnScanQr)
        btnManualIpDesktop = view.findViewById<Button>(R.id.btnManualIpDesktop)

        val btnBurgerDesktop = view.findViewById<ImageView>(R.id.btnBurgerDesktop)
        btnBurgerDesktop?.setOnClickListener { anchor ->
            val popup = PopupMenu(requireContext(), anchor)
            popup.menu.add(0, 1, 0, "🔄 Refresh Status PC")
            popup.menu.add(0, 2, 1, "⚙️ Pengaturan / Config")
            popup.menu.add(0, 3, 2, "🔌 Putuskan Koneksi")

            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        progressBarDesktopLoading?.visibility = View.VISIBLE
                        updateDesktopStats()
                        Toast.makeText(requireContext(), "Memeriksa status PC...", Toast.LENGTH_SHORT).show()
                        true
                    }
                    2 -> {
                        val intent = Intent(requireContext(), ConfigActivity::class.java)
                        startActivity(intent)
                        true
                    }
                    3 -> {
                        disconnectServer()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        tvDesktopStatus = view.findViewById<TextView>(R.id.tvDesktopStatus)
        tvPcHost = view.findViewById<TextView>(R.id.tvPcHost)
        tvCpuUsageDesktop = view.findViewById<TextView>(R.id.tvCpuUsageDesktop)
        tvCpuTempDesktop = view.findViewById<TextView>(R.id.tvCpuTempDesktop)
        tvRamUsageDesktop = view.findViewById<TextView>(R.id.tvRamUsageDesktop)
        tvGpuName = view.findViewById<TextView>(R.id.tvGpuName)
        tvGpuUsage = view.findViewById<TextView>(R.id.tvGpuUsage)
        tvNetworkDesktop = view.findViewById<TextView>(R.id.tvNetworkDesktop)
        tvFpsDesktop = view.findViewById<TextView>(R.id.tvFpsDesktop)
        tvDriveC = view.findViewById<TextView>(R.id.tvDriveC)
        tvVolumeValue = view.findViewById<TextView>(R.id.tvVolumeValue)
        seekBarVolume = view.findViewById<SeekBar>(R.id.seekBarVolume)

        seekBarVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvVolumeValue.text = "$progress%"

                    // Live real-time volume adjustment while sliding
                    val now = System.currentTimeMillis()
                    handler.removeCallbacks(volumeSendRunnable)
                    if (now - lastVolumeSendTime >= 100) {
                        lastVolumeSendTime = now
                        sendVolumeToPc(progress)
                    } else {
                        handler.postDelayed(volumeSendRunnable, 100)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserDraggingVolume = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserDraggingVolume = false
                handler.removeCallbacks(volumeSendRunnable)
                val finalVolume = seekBar?.progress ?: return
                sendVolumeToPc(finalVolume)
            }
        })

        btnScanQr.setOnClickListener {
            val options = ScanOptions()
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            options.setPrompt("Arahkan kamera ke Kode QR di layar PC")
            options.setCameraId(0)
            options.setBeepEnabled(true)
            options.setBarcodeImageEnabled(false)
            options.setOrientationLocked(false)
            qrScanLauncher.launch(options)
        }

        btnManualIpDesktop.setOnClickListener {
            showManualIpDialog()
        }

        updateDesktopStats()
    }

    private fun sendVolumeToPc(volumePercent: Int) {
        val context = context ?: return
        val prefs = context.getSharedPreferences("pcstat_prefs", Context.MODE_PRIVATE)
        val serverUrl = prefs.getString("server_url", null) ?: return

        executor.execute {
            try {
                val baseUrl = serverUrl.replace("/stats", "")
                val volumeUrl = "$baseUrl/volume?val=$volumePercent"
                val conn = java.net.URL(volumeUrl).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                if (code == 200) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(updateRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)
        handler.removeCallbacks(volumeSendRunnable)
    }

    private fun updateDesktopStats() {
        if (!isAdded) return

        val prefs = requireContext().getSharedPreferences("pcstat_prefs", Context.MODE_PRIVATE)
        val serverUrl = prefs.getString("server_url", null)

        if (!serverUrl.isNullOrEmpty()) {
            executor.execute {
                var jsonRes: org.json.JSONObject? = null
                var activeUrl = serverUrl

                try {
                    val conn = java.net.URL(activeUrl).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 1500
                    conn.readTimeout = 1500
                    if (conn.responseCode == 200) {
                        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                        jsonRes = org.json.JSONObject(responseText)
                    }
                } catch (e: Exception) {
                    if (activeUrl.contains("127.0.0.1") || activeUrl.contains("localhost")) {
                        val fallbackUrl = activeUrl.replace("127.0.0.1", "10.0.2.2").replace("localhost", "10.0.2.2")
                        try {
                            val conn = java.net.URL(fallbackUrl).openConnection() as java.net.HttpURLConnection
                            conn.connectTimeout = 1500
                            conn.readTimeout = 1500
                            if (conn.responseCode == 200) {
                                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                                jsonRes = org.json.JSONObject(responseText)
                                activeUrl = fallbackUrl
                            }
                        } catch (ignored: Exception) {}
                    }
                }

                val finalJson = jsonRes
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread

                    progressBarDesktopLoading?.visibility = View.GONE

                    if (finalJson != null) {
                        // Connected State: Show real stats
                        layoutNotConnected.visibility = View.GONE
                        layoutDesktopStats.visibility = View.VISIBLE

                        val hostname = finalJson.optString("hostname", "PC")
                        val cpuPercent = finalJson.optDouble("cpu_percent", 0.0)
                        val cpuCount = finalJson.optInt("cpu_count", 0)
                        val cpuTemp = finalJson.optDouble("cpu_temp_c", 0.0)

                        val ramPercent = finalJson.optDouble("ram_percent", 0.0)
                        val ramUsedMb = finalJson.optDouble("ram_used_mb", 0.0).toInt()
                        val ramTotalMb = finalJson.optDouble("ram_total_mb", 0.0).toInt()

                        val gpuName = finalJson.optString("gpu_name", "N/A")
                        val gpuPercent = finalJson.optDouble("gpu_percent", 0.0)
                        val gpuMemUsed = finalJson.optInt("gpu_mem_used_mb", 0)
                        val gpuMemTotal = finalJson.optInt("gpu_mem_total_mb", 0)
                        val gpuTemp = finalJson.optInt("gpu_temp_c", 0)

                        val netDown = finalJson.optString("net_down", "0.0 KB/s")
                        val netUp = finalJson.optString("net_up", "0.0 KB/s")

                        val fps = finalJson.optInt("fps", 0)
                        val focusedApp = finalJson.optString("focused_app", "")
                        val volume = finalJson.optInt("volume", 50)

                        val storageArray = finalJson.optJSONArray("storage")

                        tvDesktopStatus.text = "● Terhubung ($hostname)"
                        tvDesktopStatus.setTextColor(Color.parseColor("#00FF66"))

                        tvPcHost.text = hostname
                        tvCpuUsageDesktop.text = String.format(Locale.getDefault(), "%.1f%% (%d cores)", cpuPercent, cpuCount)

                        tvCpuTempDesktop.text = if (cpuTemp > 0) {
                            String.format(Locale.getDefault(), "%.1f °C", cpuTemp)
                        } else {
                            "N/A"
                        }

                        tvRamUsageDesktop.text = String.format(Locale.getDefault(), "%d MB / %d MB (%.1f%%)", ramUsedMb, ramTotalMb, ramPercent)

                        tvGpuName.text = gpuName
                        val gpuDetails = mutableListOf<String>()
                        gpuDetails.add(String.format(Locale.getDefault(), "%.1f%%", gpuPercent))

                        if (gpuMemTotal > 0) {
                            if (gpuMemUsed > 0) {
                                gpuDetails.add(String.format(Locale.getDefault(), "VRAM: %d MB / %d MB", gpuMemUsed, gpuMemTotal))
                            } else {
                                gpuDetails.add(String.format(Locale.getDefault(), "VRAM: %d MB", gpuMemTotal))
                            }
                        }

                        if (gpuTemp > 0) {
                            gpuDetails.add(String.format(Locale.getDefault(), "%d°C", gpuTemp))
                        }

                        tvGpuUsage.text = gpuDetails.joinToString(" • ")

                        tvNetworkDesktop.text = "⬇️ $netDown  ⬆️ $netUp"

                        tvFpsDesktop.text = if (fps > 0) {
                            if (focusedApp.isNotEmpty() && !focusedApp.equals("Explorer", ignoreCase = true)) {
                                "$fps FPS ($focusedApp)"
                            } else {
                                "$fps FPS"
                            }
                        } else {
                            "60 FPS"
                        }

                        if (!isUserDraggingVolume) {
                            seekBarVolume.progress = volume
                            tvVolumeValue.text = "$volume%"
                        }

                        if (storageArray != null && storageArray.length() > 0) {
                            val drive0 = storageArray.optJSONObject(0)
                            if (drive0 != null) {
                                val used = drive0.optDouble("used_gb", 0.0)
                                val total = drive0.optDouble("total_gb", 0.0)
                                val pct = drive0.optDouble("percent", 0.0)
                                val mount = drive0.optString("mountpoint", "Storage")
                                tvDriveC.text = String.format(Locale.getDefault(), "%s: %.1f GB / %.1f GB (%.0f%%)", mount, used, total, pct)
                            }
                        }
                    } else {
                        // Disconnected / Server offline
                        layoutDesktopStats.visibility = View.GONE
                        layoutNotConnected.visibility = View.VISIBLE
                    }
                }
            }
        } else {
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                progressBarDesktopLoading?.visibility = View.GONE
                layoutDesktopStats.visibility = View.GONE
                layoutNotConnected.visibility = View.VISIBLE
            }
        }
    }

    private fun disconnectServer() {
        val context = context ?: return
        val prefs = context.getSharedPreferences("pcstat_prefs", Context.MODE_PRIVATE)
        prefs.edit().remove("server_url").apply()
        progressBarDesktopLoading?.visibility = View.GONE
        layoutDesktopStats.visibility = View.GONE
        layoutNotConnected.visibility = View.VISIBLE
        Toast.makeText(requireContext(), "Koneksi ke PC diputuskan", Toast.LENGTH_SHORT).show()
    }

    private fun showManualIpDialog() {
        val context = requireContext()
        val builder = AlertDialog.Builder(context)
        builder.setTitle("Input IP PC Manual")

        val input = EditText(context)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.hint = "Contoh: 192.168.1.4:8080"

        val prefs = context.getSharedPreferences("pcstat_prefs", Context.MODE_PRIVATE)
        val existingUrl = prefs.getString("server_url", "")
        if (!existingUrl.isNullOrEmpty()) {
            input.setText(existingUrl.replace("http://", "").replace("/stats", ""))
        }

        val container = LinearLayout(context)
        container.orientation = LinearLayout.VERTICAL
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(48, 20, 48, 20)
        input.layoutParams = params
        container.addView(input)

        builder.setView(container)

        builder.setPositiveButton("Hubungkan") { dialog, _ ->
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                val formatted = if (text.startsWith("http://") || text.startsWith("https://")) text else "http://$text"
                val statsUrl = if (formatted.endsWith("/stats")) formatted else "$formatted/stats"
                prefs.edit().putString("server_url", statsUrl).apply()
                progressBarDesktopLoading?.visibility = View.VISIBLE
                updateDesktopStats()
            } else {
                Toast.makeText(context, "IP tidak boleh kosong", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }

        builder.setNegativeButton("Batal") { dialog, _ ->
            dialog.cancel()
        }

        builder.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        executor.shutdown()
    }
}
