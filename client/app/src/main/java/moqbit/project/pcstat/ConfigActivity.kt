package moqbit.project.pcstat

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.yalantis.ucrop.UCrop
import java.io.File

class ConfigActivity : AppCompatActivity() {

    private lateinit var rootLayoutConfig: View
    private lateinit var tvLanguageValue: TextView
    private lateinit var tvOrientationValue: TextView
    private var videoViewBg: FullScreenVideoView? = null

    private val pickMediaLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { sourceUri ->
            val mimeType = contentResolver.getType(sourceUri) ?: ""
            val isVideo = mimeType.startsWith("video/") ||
                    sourceUri.path?.endsWith(".mp4", ignoreCase = true) == true ||
                    sourceUri.toString().contains(".mp4", ignoreCase = true)

            if (isVideo) {
                try {
                    val videoFile = File(cacheDir, "bg_cached.mp4")
                    val imageFile = File(cacheDir, "bg_cached.jpg")
                    if (videoFile.exists()) videoFile.delete()
                    if (imageFile.exists()) imageFile.delete()

                    contentResolver.openInputStream(sourceUri)?.use { input ->
                        videoFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    Toast.makeText(this, "Background video (.mp4) berhasil dipasang!", Toast.LENGTH_SHORT).show()
                    loadBackground()
                } catch (e: Exception) {
                    Toast.makeText(this, "Gagal menyimpan video: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } else {
                val videoFile = File(cacheDir, "bg_cached.mp4")
                if (videoFile.exists()) videoFile.delete()

                val destinationFile = File(cacheDir, "bg_cached.jpg")
                if (destinationFile.exists()) destinationFile.delete()
                val destinationUri = Uri.fromFile(destinationFile)

                val options = UCrop.Options().apply {
                    setFreeStyleCropEnabled(true)
                }
                UCrop.of(sourceUri, destinationUri)
                    .withOptions(options)
                    .withMaxResultSize(2560, 2560)
                    .start(this)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply saved orientation
        applyOrientation()

        // Enable edge-to-edge and transparent immersive fullscreen
        BackgroundManager.applyImmersiveFullscreen(window)

        setContentView(R.layout.activity_config)

        rootLayoutConfig = findViewById(R.id.rootLayoutConfig)
        videoViewBg = findViewById(R.id.videoViewBg)
        tvLanguageValue = findViewById(R.id.tvLanguageValue)
        tvOrientationValue = findViewById(R.id.tvOrientationValue)

        val btnBack = findViewById<ImageView>(R.id.btnBack)
        btnBack.setOnClickListener {
            finish()
        }

        val barChangeBackground = findViewById<LinearLayout>(R.id.barChangeBackground)
        barChangeBackground.setOnClickListener {
            pickMediaLauncher.launch(arrayOf("image/*", "video/mp4", "video/*"))
        }

        val barLanguage = findViewById<LinearLayout>(R.id.barLanguage)
        barLanguage.setOnClickListener {
            val intent = Intent(this, LanguageActivity::class.java)
            startActivity(intent)
        }

        val barOrientation = findViewById<LinearLayout>(R.id.barOrientation)
        barOrientation.setOnClickListener {
            val intent = Intent(this, OrientationActivity::class.java)
            startActivity(intent)
        }

        val barExit = findViewById<LinearLayout>(R.id.barExit)
        barExit.setOnClickListener {
            finishAffinity()
        }

        updateDisplayValues()
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("pcstat_prefs", MODE_PRIVATE)
        val orientation = prefs.getInt("orientation", ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        if (requestedOrientation != orientation) {
            requestedOrientation = orientation
            recreate()
            return
        }

        updateDisplayValues()
        loadBackground()
        BackgroundManager.resumeVideo(videoViewBg)
    }

    override fun onPause() {
        super.onPause()
        BackgroundManager.pauseVideo(videoViewBg)
    }

    private fun applyOrientation() {
        val prefs = getSharedPreferences("pcstat_prefs", MODE_PRIVATE)
        val orientation = prefs.getInt("orientation", ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        if (requestedOrientation != orientation) {
            requestedOrientation = orientation
        }
    }

    private fun updateDisplayValues() {
        val prefs = getSharedPreferences("pcstat_prefs", MODE_PRIVATE)
        val langCode = prefs.getString("language", "id") ?: "id"
        tvLanguageValue.text = when (langCode) {
            "en" -> "English"
            "zh" -> "简体中文"
            else -> "Indonesia"
        }

        val orientation = prefs.getInt("orientation", ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        tvOrientationValue.text = if (orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
            getString(R.string.portrait)
        } else {
            getString(R.string.landscape)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == UCrop.REQUEST_CROP) {
            if (resultCode == RESULT_OK && data != null) {
                val videoFile = File(cacheDir, "bg_cached.mp4")
                if (videoFile.exists()) videoFile.delete()

                Toast.makeText(this, "Background berhasil dipotong & disimpan ke cache", Toast.LENGTH_SHORT).show()
                loadBackground()
            } else if (resultCode == UCrop.RESULT_ERROR) {
                val cropError = UCrop.getError(data!!)
                Toast.makeText(this, "Gagal memotong gambar: ${cropError?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadBackground() {
        if (!::rootLayoutConfig.isInitialized) return
        BackgroundManager.setupBackground(this, rootLayoutConfig, videoViewBg)
    }
}
