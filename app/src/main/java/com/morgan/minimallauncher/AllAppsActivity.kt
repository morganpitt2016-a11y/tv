package com.morgan.minimallauncher

import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Every installed TV app, tvOS-style, over the blurred wallpaper. */
class AllAppsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_all_apps)
        GlassUi.installBackdrop(findViewById<FrameLayout>(R.id.appsRoot))

        val apps = AppListHelper.loadLaunchableTvApps(this)
        findViewById<TextView>(R.id.appsSubtitle).text = "${apps.size} apps"

        val grid = findViewById<RecyclerView>(R.id.allAppsGrid)
        grid.layoutManager = GridLayoutManager(this, 5)
        grid.adapter = AppAdapter(apps,
            onLaunch = { app, v -> AppActions.launch(this, app, v) },
            onMenu = { app, v ->
                val dock = ProfileManager.getDockOrder(this).ifEmpty { apps.take(10).map { it.packageName } }
                AppActions.menu(this, app, v, dock) {}
            })
        grid.post { grid.getChildAt(0)?.requestFocus() }
        UiSounds.attach(this)
    }
}
