package moqbit.project.pcstat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import java.util.Locale
import java.util.concurrent.Executors

class QrScannerFragment : Fragment() {

    private lateinit var barcodeScannerView: BarcodeView
    private lateinit var tvScanResult: TextView
    private lateinit var progressBarConnecting: ProgressBar
    private lateinit var permissionContainer: LinearLayout
    private lateinit var btnGrantPermission: Button
    private lateinit var btnFlashlight: ImageButton
    private lateinit var btnManualIp: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnViewStats: Button

    private lateinit var cardPcStats: LinearLayout
    private lateinit var tvPcName: TextView
    private lateinit var tvPcCpu: TextView
    private lateinit var tvPcRam: TextView

    private var isTorchOn = false
    private var isScanned = false
    private val netExecutor = Executors.newSingleThreadExecutor()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            permissionContainer.visibility = View.GONE
            startScanner()
        } else {
            permissionContainer.visibility = View.VISIBLE
            Toast.makeText(requireContext(), "Akses kamera ditolak", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_qr_scanner, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        barcodeScannerView = view.findViewById(R.id.barcodeScannerView)
        tvScanResult = view.findViewById(R.id.tvScanResult)
        progressBarConnecting = view.findViewById(R.id.progressBarConnecting)
        permissionContainer = view.findViewById(R.id.permissionContainer)
        btnGrantPermission = view.findViewById(R.id.btnGrantPermission)
        btnFlashlight = view.findViewById(R.id.btnFlashlight)
        btnManualIp = view.findViewById(R.id.btnManualIp)
        btnDisconnect = view.findViewById(R.id.btnDisconnect)
        btnViewStats = view.findViewById(R.id.btnViewStats)

        cardPcStats = view.findViewById(R.id.cardPcStats)
        tvPcName = view.findViewById(R.id.tvPcName)
        tvPcCpu = view.findViewById(R.id.tvPcCpu)
        tvPcRam = view.findViewById(R.id.tvPcRam)

        val btnBack = view.findViewById<ImageView>(R.id.btnBack)
        btnBack?.setOnClickListener {
            (activity as? MainActivity)?.selectPage(0)
        }

        btnGrantPermission.setOnClickListener {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        btnFlashlight.setOnClickListener {
            toggleFlashlight()
        }

        btnManualIp.setOnClickListener {
            showManualIpDialog()
        }

        btnDisconnect.setOnClickListener {
            disconnectServer()
        }

        btnViewStats.setOnClickListener {
            (activity as? MainActivity)?.selectPage(1)
        }

        checkCameraPermission()
    }

    override fun onResume() {
        super.onResume()
        isScanned = false
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            barcodeScannerView.resume()
        }
        updateConnectionStatus()
    }

    override fun onPause() {
        super.onPause()
        barcodeScannerView.pause()
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            permissionContainer.visibility = View.GONE
            startScanner()
        } else {
            permissionContainer.visibility = View.VISIBLE
        }
    }

    private fun startScanner() {
        val formats = listOf(BarcodeFormat.QR_CODE)
        barcodeScannerView.decoderFactory = DefaultDecoderFactory(formats)
        barcodeScannerView.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                if (result != null && !result.text.isNullOrEmpty() && !isScanned) {
                    isScanned = true
                    connectAndVerifyServer(result.text)
                }
            }

            override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>?) {}
        })
        barcodeScannerView.resume()
    }

    private fun connectAndVerifyServer(rawValue: String) {
        val cleaned = rawValue.trim().replace(" ", "").replace("\n", "").replace("\r", "")
        val formattedUrl = when {
            cleaned.startsWith("http://") || cleaned.startsWith("https://") -> cleaned
            else -> "http://$cleaned"
        }
        val statsUrl = if (formattedUrl.endsWith("/stats")) formattedUrl else "$formattedUrl/stats"

        activity?.runOnUiThread {
            progressBarConnecting.visibility = View.VISIBLE
            tvScanResult.visibility = View.VISIBLE
            tvScanResult.text = "🔄 Memeriksa koneksi ke PC ($statsUrl)..."
            tvScanResult.setTextColor(Color.YELLOW)
        }

        verifyNetworkConnection(statsUrl, autoNavigate = true)
    }

    private fun updateConnectionStatus() {
        val context = context ?: return
        val prefs = context.getSharedPreferences("pcstat_prefs", Context.MODE_PRIVATE)
        val serverUrl = prefs.getString("server_url", null)

        if (!serverUrl.isNullOrEmpty()) {
            tvScanResult.visibility = View.VISIBLE
            tvScanResult.text = "● Terhubung: $serverUrl"
            tvScanResult.setTextColor(Color.parseColor("#00FF66"))
            btnDisconnect.visibility = View.VISIBLE
            btnViewStats.visibility = View.VISIBLE
            cardPcStats.visibility = View.VISIBLE
            verifyNetworkConnection(serverUrl, autoNavigate = false)
        } else {
            progressBarConnecting.visibility = View.GONE
            tvScanResult.visibility = View.GONE
            btnDisconnect.visibility = View.GONE
            btnViewStats.visibility = View.GONE
            cardPcStats.visibility = View.GONE
        }
    }

    private fun verifyNetworkConnection(serverUrl: String, autoNavigate: Boolean) {
        netExecutor.execute {
            var targetUrl = serverUrl
            var jsonRes: org.json.JSONObject? = null

            try {
                val conn = java.net.URL(targetUrl).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                if (conn.responseCode == 200) {
                    val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                    jsonRes = org.json.JSONObject(responseText)
                }
            } catch (e: Exception) {
                if (targetUrl.contains("127.0.0.1") || targetUrl.contains("localhost")) {
                    val fallbackUrl = targetUrl.replace("127.0.0.1", "10.0.2.2").replace("localhost", "10.0.2.2")
                    try {
                        val conn = java.net.URL(fallbackUrl).openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 2000
                        conn.readTimeout = 2000
                        if (conn.responseCode == 200) {
                            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                            jsonRes = org.json.JSONObject(responseText)
                            targetUrl = fallbackUrl
                        }
                    } catch (ignored: Exception) {}
                }
            }

            val finalJson = jsonRes
            val validUrl = targetUrl

            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread

                progressBarConnecting.visibility = View.GONE

                if (finalJson != null) {
                    // Successful connection!
                    val context = context ?: return@runOnUiThread
                    val prefs = context.getSharedPreferences("pcstat_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("server_url", validUrl).apply()

                    val hostname = finalJson.optString("hostname", "PC")
                    val cpuPercent = finalJson.optDouble("cpu_percent", 0.0)
                    val ramPercent = finalJson.optDouble("ram_percent", 0.0)

                    tvScanResult.visibility = View.VISIBLE
                    tvScanResult.text = "● Terhubung: $validUrl"
                    tvScanResult.setTextColor(Color.parseColor("#00FF66"))

                    cardPcStats.visibility = View.VISIBLE
                    tvPcName.text = "🖥️ PC Terhubung: $hostname"
                    tvPcCpu.text = String.format(Locale.getDefault(), "CPU: %.1f%%", cpuPercent)
                    tvPcRam.text = String.format(Locale.getDefault(), "RAM: %.1f%%", ramPercent)

                    btnDisconnect.visibility = View.VISIBLE
                    btnViewStats.visibility = View.VISIBLE

                    if (autoNavigate) {
                        Toast.makeText(requireContext(), "Berhasil terhubung ke PC ($hostname)!", Toast.LENGTH_SHORT).show()
                        (activity as? MainActivity)?.selectPage(1)
                    }
                } else {
                    // Connection Failed!
                    isScanned = false
                    tvScanResult.visibility = View.VISIBLE
                    tvScanResult.text = "❌ Gagal terhubung ke $serverUrl\nPastikan HP & PC terhubung ke Wi-Fi yang sama!"
                    tvScanResult.setTextColor(Color.parseColor("#FF3B30"))

                    cardPcStats.visibility = View.GONE
                    btnViewStats.visibility = View.GONE

                    if (autoNavigate) {
                        Toast.makeText(requireContext(), "Gagal terhubung! Periksa koneksi Wi-Fi & Firewall PC.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun disconnectServer() {
        val context = context ?: return
        val prefs = context.getSharedPreferences("pcstat_prefs", Context.MODE_PRIVATE)
        prefs.edit().remove("server_url").apply()
        isScanned = false
        updateConnectionStatus()
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
                connectAndVerifyServer(text)
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

    private fun toggleFlashlight() {
        isTorchOn = !isTorchOn
        barcodeScannerView.setTorch(isTorchOn)
        btnFlashlight.setColorFilter(if (isTorchOn) Color.parseColor("#00FF66") else Color.WHITE)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        barcodeScannerView.pause()
        netExecutor.shutdown()
    }
}
