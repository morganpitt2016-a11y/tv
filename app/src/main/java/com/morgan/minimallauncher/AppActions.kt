package com.morgan.minimallauncher

import android.app.Activity
import android.app.ActivityOptions
import android.view.View

/** Opening apps and the hold-OK menu, shared by the home screen, rails and the Apps grid. */
object AppActions {

    /** Opens an app; it grows out of the tile that was selected. */
    fun launch(activity: Activity, app: AppEntry, from: View?) {
        val intent = app.launchIntent ?: return
        RecentAppsTracker.recordOpened(activity, app.packageName)
        try {
            val options = from?.takeIf { it.width > 0 }?.let {
                ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle()
            }
            activity.startActivity(intent, options)
        } catch (e: Exception) {
            GlassUi.toast(activity, "${app.label} couldn’t be opened")
        }
    }

    /**
     * Hold OK on an app: open, favourite, and arrange the dock.
     * [dock] is the dock as currently shown (so moving works before anything was pinned).
     */
    fun menu(activity: Activity, app: AppEntry, from: View?, dock: List<String>, changed: () -> Unit) {
        val inDock = app.packageName in dock
        val fav = ProfileManager.isFavourite(activity, app.packageName)
        val d = GlassDialog(activity).title(app.label)
        d.button("Open", GlassDialog.Style.BOLD) { launch(activity, app, from) }
        d.button(if (fav) "Remove from Favourites" else "Add to Favourites") {
            ProfileManager.toggleFavourite(activity, app.packageName); changed()
        }
        if (inDock) {
            val i = dock.indexOf(app.packageName)
            if (i > 0) d.button("Move Left") { ProfileManager.moveInDock(activity, app.packageName, -1, dock); changed() }
            if (i < dock.size - 1) d.button("Move Right") { ProfileManager.moveInDock(activity, app.packageName, 1, dock); changed() }
            d.button("Remove from Dock", GlassDialog.Style.DESTRUCTIVE) { ProfileManager.removeFromDock(activity, app.packageName, dock); changed() }
        } else {
            d.button("Add to Dock") { ProfileManager.addToDock(activity, app.packageName, dock); changed() }
        }
        d.button("Cancel")
        d.show()
    }
}
