package com.morgan.minimallauncher

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Full grid of every launchable TV app, for anything not pinned to the
 * home screen dock.
 */
class AllAppsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_all_apps)

        val grid = findViewById<RecyclerView>(R.id.allAppsGrid)
        grid.layoutManager = GridLayoutManager(this, 6)
        grid.adapter = AppAdapter(AppListHelper.loadLaunchableTvApps(this)) { intent ->
            intent.component?.packageName?.let { RecentAppsTracker.recordOpened(this, it) }
            startActivity(intent)
        }
    }
}
