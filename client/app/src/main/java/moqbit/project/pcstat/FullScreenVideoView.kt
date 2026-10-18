package moqbit.project.pcstat

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.AttributeSet
import android.widget.VideoView
import kotlin.math.ceil
import kotlin.math.max

class FullScreenVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : VideoView(context, attrs, defStyleAttr) {

    private var videoWidth = 0
    private var videoHeight = 0

    fun setVideoSize(width: Int, height: Int) {
        if (width > 0 && height > 0 && (videoWidth != width || videoHeight != height)) {
            videoWidth = width
            videoHeight = height
            requestLayout()
        }
    }

    override fun setVideoPath(path: String?) {
        super.setVideoPath(path)
        if (!path.isNullOrEmpty()) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(path)
                val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                retriever.release()

                var rawW = w?.toIntOrNull() ?: 0
                var rawH = h?.toIntOrNull() ?: 0

                if (rotation == "90" || rotation == "270") {
                    val temp = rawW
                    rawW = rawH
                    rawH = temp
                }

                if (rawW > 0 && rawH > 0) {
                    videoWidth = rawW
                    videoHeight = rawH
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val dm = context.resources.displayMetrics
        var targetWidth = getDefaultSize(0, widthMeasureSpec)
        var targetHeight = getDefaultSize(0, heightMeasureSpec)

        // Ensure target dimensions encompass 100% full screen pixels including status/cutout areas
        if (targetWidth < dm.widthPixels) {
            targetWidth = dm.widthPixels
        }
        if (targetHeight < dm.heightPixels) {
            targetHeight = dm.heightPixels
        }

        if (videoWidth > 0 && videoHeight > 0 && targetWidth > 0 && targetHeight > 0) {
            val screenRatio = targetWidth.toFloat() / targetHeight.toFloat()
            val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()

            val finalWidth: Int
            val finalHeight: Int

            if (videoRatio > screenRatio) {
                // Scale height to fill targetHeight completely, scale width larger (Center Crop)
                finalHeight = targetHeight
                finalWidth = ceil((targetHeight.toFloat() * videoRatio).toDouble()).toInt()
            } else {
                // Scale width to fill targetWidth completely, scale height larger (Center Crop)
                finalWidth = targetWidth
                finalHeight = ceil((targetWidth.toFloat() / videoRatio).toDouble()).toInt()
            }

            // Guarantee height & width are at least full target display size + overbleed buffer
            val safeWidth = max(finalWidth, targetWidth) + 2
            val safeHeight = max(finalHeight, targetHeight) + 2

            setMeasuredDimension(safeWidth, safeHeight)
        } else {
            setMeasuredDimension(targetWidth + 2, targetHeight + 2)
        }
    }
}
