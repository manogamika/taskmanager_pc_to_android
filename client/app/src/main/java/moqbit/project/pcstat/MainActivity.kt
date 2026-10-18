package moqbit.project.pcstat

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.viewpager2.widget.ViewPager2

class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: ConstraintLayout
    private lateinit var viewPager: ViewPager2
    private var videoViewBg: FullScreenVideoView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply saved orientation
        applyOrientation()

        // Enable edge-to-edge transparent immersive fullscreen
        BackgroundManager.applyImmersiveFullscreen(window)

        setContentView(R.layout.activity_main)

        rootLayout = findViewById(R.id.rootLayout)
        viewPager = findViewById(R.id.viewPager)
        videoViewBg = findViewById(R.id.videoViewBg)

        // Set adapter for ViewPager2
        viewPager.adapter = MainPagerAdapter(this)

        // Handle back press to return to Dashboard page (Page 0) before exiting
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewPager.currentItem != 0) {
                    viewPager.currentItem = 0
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
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
        loadBackground()
        BackgroundManager.resumeVideo(videoViewBg)
    }

    override fun onPause() {
        super.onPause()
        BackgroundManager.pauseVideo(videoViewBg)
    }

    fun selectPage(page: Int) {
        if (::viewPager.isInitialized) {
            viewPager.currentItem = page
        }
    }

    private fun applyOrientation() {
        val prefs = getSharedPreferences("pcstat_prefs", MODE_PRIVATE)
        val orientation = prefs.getInt("orientation", ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        if (requestedOrientation != orientation) {
            requestedOrientation = orientation
        }
    }

    private fun loadBackground() {
        if (!::rootLayout.isInitialized) return
        BackgroundManager.setupBackground(this, rootLayout, videoViewBg)
    }
}
