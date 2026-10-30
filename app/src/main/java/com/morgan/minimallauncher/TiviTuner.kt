package com.morgan.minimallauncher

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo

/**
 * TiviMate has no way for other apps to open a particular channel. So,
 * when the accessibility service is on, this opens TiviMate's channel
 * list (like pressing OK), looks for the channel by name - scrolling down
 * the list if needed - and selects it, the way you would with the remote.
 *
 * Best effort: channel names in your TiviMate playlist may differ from
 * the Freeview guide's ("UK: BBC ONE FHD" vs "BBC One"), so names are
 * compared loosely. If it can't find the channel it stops and says so.
 */
object TiviTuner {
    private val main = Handler(Looper.getMainLooper())
    private const val PKG = LiveGuide.TIVIMATE

    fun canTune() = HomeRedirectService.instance != null && Build.VERSION.SDK_INT >= 33

    /** Call right after launching TiviMate. [done] gets true if the channel was selected. */
    fun tune(channel: String, done: (Boolean) -> Unit) {
        val service = HomeRedirectService.instance ?: return done(false)
        val wanted = normalise(channel)
        var attempts = 0
        var openedList = false
        var lastSeen = ""

        fun step() {
            attempts++
            if (attempts > 40) { done(false); return }
            val root = service.rootInActiveWindow
            if (root == null || root.packageName?.toString() != PKG) {
                // TiviMate still starting up.
                main.postDelayed({ step() }, 500); return
            }
            val match = find(root) { n -> matches(normalise(n), wanted) }
            if (match != null) {
                done(select(service, match)); return
            }
            if (!openedList) {
                // Bring up the channel list over the playing channel.
                openedList = true
                if (Build.VERSION.SDK_INT >= 33) service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_CENTER)
                main.postDelayed({ step() }, 900); return
            }
            // Not on screen yet: scroll the list on and look again.
            val seen = visibleText(root)
            if (seen == lastSeen) { done(false); return } // reached the end
            lastSeen = seen
            val list = findScrollable(root)
            if (list != null) list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            else if (Build.VERSION.SDK_INT >= 33) repeat(6) { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_DOWN) }
            main.postDelayed({ step() }, 450)
        }
        main.postDelayed({ step() }, 1500)
    }

    // ---------- Matching ----------

    private val noise = Regex("\\b(uk|gb|hd|fhd|uhd|sd|4k|hevc|h265|raw|vip|backup|\\+1 ?hd)\\b|[|:\\[\\]()_.\\-]")

    fun normalise(s: String): String = s.lowercase()
        .replace(noise, " ")
        .replace("bbc 1", "bbc one").replace("bbc1", "bbc one").replace("bbc 2", "bbc two").replace("bbc2", "bbc two")
        .replace("itv 1", "itv1").replace("channel 5", "5").replace("channel5", "5").replace("ch4", "channel 4")
        .replace(Regex("\\s+"), " ").trim()

    private fun matches(candidate: String, wanted: String): Boolean {
        if (candidate.isEmpty() || wanted.isEmpty()) return false
        if (candidate == wanted) return true
        // "bbc one london" contains "bbc one"; keep +1 channels apart.
        val plusOne = { x: String -> x.contains("+1") }
        if (plusOne(candidate) != plusOne(wanted)) return false
        return Regex("(^| )" + Regex.escape(wanted) + "( |$)").containsMatchIn(candidate) && candidate.length <= wanted.length + 14
    }

    private fun find(node: AccessibilityNodeInfo, test: (String) -> Boolean): AccessibilityNodeInfo? {
        val text = (node.text ?: node.contentDescription)?.toString()
        if (text != null && test(text)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            find(child, test)?.let { return it }
        }
        return null
    }

    private fun visibleText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo) {
            n.text?.let { sb.append(it).append('|') }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(node)
        return sb.toString()
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findScrollable(child)?.let { return it }
        }
        return null
    }

    /** Click the channel row (or the nearest clickable parent); otherwise focus it and press OK. */
    private fun select(service: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
        }
        n = node
        while (n != null) {
            if (n.isFocusable && n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
                if (Build.VERSION.SDK_INT >= 33) service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_CENTER)
                return true
            }
            n = n.parent
        }
        return false
    }
}
