package moqbit.project.pcstat

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class OrientationActivity : AppCompatActivity() {

    private lateinit var rootLayoutOrientation: View
    private var videoViewBg: FullScreenVideoView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply saved orientation
        val prefs = getSharedPreferences("pcstat_prefs", MODE_PRIVATE)
        val orientation = prefs.getInt("orientation", ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        requestedOrientation = orientation

        // Enable edge-to-edge and transparent immersive fullscreen
        BackgroundManager.applyImmersiveFullscreen(window)

        setContentView(R.layout.activity_orientation)

        rootLayoutOrientation = findViewById(R.id.rootLayoutOrientation)
        videoViewBg = findViewById(R.id.videoViewBg)
        loadBackground()

        val btnBack = findViewById<ImageView>(R.id.btnBack)
        btnBack.setOnClickListener {
            finish()
        }

        val rowLandscape = findViewById<LinearLayout>(R.id.rowLandscape)
        rowLandscape.setOnClickListener {
            changeOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, getString(R.string.landscape))
        }

        val rowPortrait = findViewById<LinearLayout>(R.id.rowPortrait)
        rowPortrait.setOnClickListener {
            changeOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, getString(R.string.portrait))
        }
    }

    private fun changeOrientation(newOrientation: Int, name: String) {
        val prefs = getSharedPreferences("pcstat_prefs", MODE_PRIVATE)
        prefs.edit().putInt("orientation", newOrientation).apply()
        requestedOrientation = newOrientation
        Toast.makeText(this, "Orientasi diubah ke $name", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onResume() {
        super.onResume()
        loadBackground()
        BackgroundManager.resumeVideo(videoViewBg)
    }

    override fun onPause() {
        super.onPause()
        BackgroundManager.pauseVideo(videoViewBg)
    }

    private fun loadBackground() {
        if (!::rootLayoutOrientation.isInitialized) return
        BackgroundManager.setupBackground(this, rootLayoutOrientation, videoViewBg)
    }
}
