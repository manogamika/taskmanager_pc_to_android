package moqbit.project.pcstat

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class RunningAppsActivity : AppCompatActivity() {

    data class AppInfo(val name: String, val packageName: String, val icon: android.graphics.drawable.Drawable)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply saved orientation
        val prefs = getSharedPreferences("pcstat_prefs", MODE_PRIVATE)
        val orientation = prefs.getInt("orientation", android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        requestedOrientation = orientation

        // Enable edge-to-edge and transparent immersive fullscreen
        BackgroundManager.applyImmersiveFullscreen(window)

        setContentView(R.layout.activity_running_apps)

        val btnBack = findViewById<ImageView>(R.id.btnBack)
        btnBack.setOnClickListener {
            finish()
        }

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerViewApps)
        recyclerView.layoutManager = LinearLayoutManager(this)

        // Load installed apps with launch intent
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        val appList = resolveInfos.map { resolveInfo ->
            val appName = resolveInfo.loadLabel(pm).toString()
            val packageName = resolveInfo.activityInfo.packageName
            val icon = resolveInfo.loadIcon(pm)
            AppInfo(appName, packageName, icon)
        }.sortedBy { it.name }

        recyclerView.adapter = AppAdapter(appList) { appInfo ->
            val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName)
            if (launchIntent != null) {
                startActivity(launchIntent)
            }
        }
    }

    class AppAdapter(private val apps: List<AppInfo>, private val onClick: (AppInfo) -> Unit) :
        RecyclerView.Adapter<AppAdapter.AppViewHolder>() {

        class AppViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
            val ivIcon: ImageView = view.findViewById(R.id.ivAppIcon)
            val tvName: TextView = view.findViewById(R.id.tvAppName)
            val tvPackage: TextView = view.findViewById(R.id.tvPackageName)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): AppViewHolder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_row, parent, false)
            return AppViewHolder(view)
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            val app = apps[position]
            holder.ivIcon.setImageDrawable(app.icon)
            holder.tvName.text = app.name
            holder.tvPackage.text = app.packageName
            holder.itemView.setOnClickListener { onClick(app) }
        }

        override fun getItemCount() = apps.size
    }
}
