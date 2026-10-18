package moqbit.project.pcstat

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

class LanguageActivity : AppCompatActivity() {

    private lateinit var rootLayoutLanguage: View
    private var videoViewBg: FullScreenVideoView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply saved orientation
        val prefs = getSharedPreferences("pcstat_prefs", MODE_PRIVATE)
        val orientation = prefs.getInt("orientation", ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        requestedOrientation = orientation

        // Enable edge-to-edge and transparent immersive fullscreen
        BackgroundManager.applyImmersiveFullscreen(window)

        setContentView(R.layout.activity_language)

        rootLayoutLanguage = findViewById(R.id.rootLayoutLanguage)
        videoViewBg = findViewById(R.id.videoViewBg)
        loadBackground()

        val btnBack = findViewById<ImageView>(R.id.btnBack)
        btnBack.setOnClickListener {
            finish()
        }

        val rowEnglish = findViewById<LinearLayout>(R.id.rowEnglish)
        rowEnglish.setOnClickListener {
            changeLanguage("en", "Language changed to English")
        }

        val rowIndonesia = findViewById<LinearLayout>(R.id.rowIndonesia)
        rowIndonesia.setOnClickListener {
            changeLanguage("id", "Bahasa diubah ke Indonesia")
        }

        val rowMandarin = findViewById<LinearLayout>(R.id.rowMandarin)
        rowMandarin.setOnClickListener {
            changeLanguage("zh", "语言已更改为简体中文")
        }
    }

    private fun changeLanguage(langCode: String, message: String) {
        val prefs = getSharedPreferences("pcstat_prefs", MODE_PRIVATE)
        prefs.edit().putString("language", langCode).apply()

        val localeList = LocaleListCompat.forLanguageTags(langCode)
        AppCompatDelegate.setApplicationLocales(localeList)

        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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
        if (!::rootLayoutLanguage.isInitialized) return
        BackgroundManager.setupBackground(this, rootLayoutLanguage, videoViewBg)
    }
}
