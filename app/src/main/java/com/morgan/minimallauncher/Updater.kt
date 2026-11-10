package com.morgan.minimallauncher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * Keeps the launcher up to date by itself: every so often (while you're on the home screen) it checks GitHub
 * for a newer build, downloads it and hands it to Android's installer. Needs "Install unknown apps" allowed
 * for the launcher once; after that new builds arrive without any commands.
 */
object Updater {
    private const val BASE = "https://raw.githubusercontent.com/morganpitt2016-a11y/tv/main/dist"
    @Volatile private var running = false
    @Volatile var guideOpen = false
    /** True while an update the person asked for (Check for Updates) is going through: Android's box opens straight away. */
    @Volatile var askedNow = false
    /** Android's "Install" confirmation, held until the home screen can ask first (so it never pops up over a film). */
    @Volatile var pendingConfirm: Intent? = null
    @Volatile var pendingBuild = 0

    /** Someone is watching: the player or the guide is up, or sound is playing (from any app). */
    fun busy(c: Context): Boolean {
        if (PlayerActivity.watching || guideOpen) return true
        val am = c.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        return am.isMusicActive
    }

    fun installedBuild(c: Context): Int =
        c.packageManager.getPackageInfo(c.packageName, 0).let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else @Suppress("DEPRECATION") it.versionCode }

    /** Check now (or at most every 20 minutes). Returns immediately; [report] gets the outcome. */
    fun maybeUpdate(c: Context, force: Boolean = false, asked: Boolean = false, report: ((String) -> Unit)? = null) {
        val app = c.applicationContext
        val now = System.currentTimeMillis()
        if (!force && now - (Prefs.string(app, "upd_last")?.toLongOrNull() ?: 0L) < 20 * 60_000L) return
        if (running) { report?.invoke("Already checking for an update - try again in a minute"); return }
        running = true
        Prefs.setString(app, "upd_last", now.toString())
        thread(isDaemon = true) {
            val msg = try { run(app, asked) } catch (e: Exception) { "Update check failed: ${e.javaClass.simpleName} ${e.message}" }
            running = false
            report?.invoke(msg)
        }
    }

    private fun run(c: Context, asked: Boolean = false): String {
        val stamp = System.currentTimeMillis()
        // GitHub's file servers can hand out an old copy for a few minutes; its API is always current.
        val latest = try {
            JSONObject(Net.get("https://api.github.com/repos/morganpitt2016-a11y/tv/contents/dist/version.json?ref=main",
                mapOf("Accept" to "application/vnd.github.raw"), timeoutMs = 15_000)).getInt("build")
        } catch (e: Exception) {
            JSONObject(Net.get("$BASE/version.json?t=$stamp", timeoutMs = 15_000)).getInt("build")
        }
        val have = installedBuild(c)
        if (latest <= have) return "Up to date (build $have)"
        val file = File(c.cacheDir, "update.apk")
        val viaApi = Net.download("https://api.github.com/repos/morganpitt2016-a11y/tv/contents/dist/app-debug.apk?ref=main", file,
            mapOf("Accept" to "application/vnd.github.raw"))
        if (!viaApi && !Net.download("$BASE/app-debug.apk?t=$stamp", file)) return "Build $latest found but the download failed"
        val info = c.packageManager.getPackageArchiveInfo(file.path, 0)
        val got = info?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else @Suppress("DEPRECATION") it.versionCode } ?: -1
        if (got <= have) { file.delete(); return "Downloaded file is build $got, not newer than $have - waiting for the next one" }
        val pending = Prefs.string(c, "upd_pending")?.split(":")
        if (!asked && pending != null && pending.size == 2 && pending[0].toIntOrNull() == latest && System.currentTimeMillis() - (pending[1].toLongOrNull() ?: 0L) < 3 * 60_000L)
            return "Build $latest was already sent to the installer - waiting for it to finish"
        // Never cut into what someone is watching: it installs when the screen goes off or the TV is idle.
        if (!asked && busy(c)) return "Build $latest is downloaded - it will install when nobody is watching"
        if (Build.VERSION.SDK_INT >= 26 && !c.packageManager.canRequestPackageInstalls())
            return "Build $latest is ready but this TV hasn't allowed the launcher to install updates yet"
        Prefs.setString(c, "upd_pending", "$latest:${System.currentTimeMillis()}")
        askedNow = asked; pendingBuild = latest
        install(c, file)
        return "Installing build $latest (was $have)"
    }

    private fun install(c: Context, file: File) {
        val pi = c.packageManager.packageInstaller
        // Clear out any earlier attempt that never finished.
        try { pi.mySessions.forEach { try { pi.abandonSession(it.sessionId) } catch (e: Exception) {} } } catch (e: Exception) {}
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { session ->
            file.inputStream().use { input -> session.openWrite("launcher.apk", 0, file.length()).use { out -> input.copyTo(out); session.fsync(out) } }
            val intent = PendingIntent.getBroadcast(c, id, Intent(c, UpdateReceiver::class.java).setPackage(c.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(intent.intentSender)
        }
    }
}

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android wants a confirmation the first time: show its Install button.
                val confirm = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                              else @Suppress("DEPRECATION") i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm ?: return
                if (Updater.askedNow) { try { c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {} }
                else Updater.pendingConfirm = confirm          // the home screen asks at a quiet moment
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> Diag.send(c, "update", "Install didn't finish: ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}

/** Fires when the launcher has just been updated: starting its process is all that is needed to bring the remote link back. */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Diag.send(c, "update", "Launcher restarted after an update (build ${Updater.installedBuild(c)})")
    }
}
