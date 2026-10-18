package moqbit.project.pcstat

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File

object BackgroundManager {

    @Suppress("DEPRECATION")
    fun applyImmersiveFullscreen(window: Window) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    fun setupBackground(
        context: Context,
        rootView: View,
        videoView: FullScreenVideoView?,
    ) {
        val cacheDir = context.cacheDir
        val videoFile = File(cacheDir, "bg_cached.mp4")
        val imageFile = File(cacheDir, "bg_cached.jpg")

        if (videoFile.exists() && (videoView != null)) {
            rootView.background = null
            videoView.visibility = View.VISIBLE
            val videoPath = videoFile.absolutePath

            if (videoView.tag != videoPath) {
                videoView.setVideoPath(videoPath)
                videoView.tag = videoPath
                videoView.setOnPreparedListener { mp ->
                    mp.isLooping = true
                    mp.setVolume(0f, 0f) // Mute background audio
                    try {
                        mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                    } catch (ignored: Exception) {}
                    videoView.setVideoSize(mp.videoWidth, mp.videoHeight)
                    videoView.start()
                }
                videoView.setOnErrorListener { _, _, _ ->
                    videoView.visibility = View.GONE
                    true
                }
            } else if (!videoView.isPlaying) {
                videoView.start()
            }
        } else {
            videoView?.visibility = View.GONE
            if (imageFile.exists()) {
                try {
                    val drawable = Drawable.createFromPath(imageFile.absolutePath)
                    rootView.background = drawable
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                rootView.background = null
            }
        }
    }

    fun pauseVideo(videoView: FullScreenVideoView?) {
        if (videoView != null && videoView.isPlaying) {
            videoView.pause()
        }
    }

    fun resumeVideo(videoView: FullScreenVideoView?) {
        if (videoView != null && (videoView.visibility == View.VISIBLE) && !videoView.isPlaying) {
            videoView.start()
        }
    }
}
