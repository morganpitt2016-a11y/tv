package com.morgan.minimallauncher

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TiviMate has no way for other apps to open a particular channel. So,
 * when the accessibility service is on, this brings up TiviMate's channel
 * list (like pressing OK), looks for the channel by name across every
 * window TiviMate has on screen - scrolling the list if needed - and
 * selects it the way the remote would (move focus to it, press OK).
 *
 * Every step is written to a log, and the iPhone remote can capture what
 * TiviMate shows, so the matching can be tuned to the real channel names.
 */
object TiviTuner {
    private val main = Handler(Looper.getMainLooper())
    private const val PKG = LiveGuide.TIVIMATE

    fun canTune() = HomeRedirectService.instance != null && Build.VERSION.SDK_INT >= 33

    // ---------- Remembered channel positions ----------
    // Each time the picker sees TiviMate's list it notes which row every channel is on,
    // so next time it can jump straight there instead of scrolling.

    private val rows = HashMap<String, Int>()
    private var rowsLoaded = false

    private fun loadRows(c: android.content.Context) {
        if (rowsLoaded) return
        rowsLoaded = true
        try {
            val o = org.json.JSONObject(Prefs.string(c, "tivi_rows2") ?: "{}")
            o.keys().forEach { k -> rows[k] = o.getInt(k) }
        } catch (e: Exception) {}
    }

    private fun saveRows(c: android.content.Context) {
        val o = org.json.JSONObject()
        rows.entries.take(3000).forEach { (k, v) -> o.put(k, v) }
        Prefs.setString(c, "tivi_rows2", o.toString())
    }

    fun hasLearned(c: android.content.Context): Boolean { loadRows(c); return rows.isNotEmpty() }

    /** The row a node sits on in its list, if TiviMate's list reports it. */
    private fun rowOf(node: AccessibilityNodeInfo): Int? {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth++ < 6) {
            n.collectionItemInfo?.let { return it.rowIndex }
            n = n.parent
        }
        return null
    }

    /** Note the row of every channel name currently visible. Returns how many were new. */
    private fun learn(lists: List<AccessibilityNodeInfo>): Int {
        var added = 0
        fun walk(n: AccessibilityNodeInfo) {
            val t = (n.text ?: n.contentDescription)?.toString()
            if (t != null && t.length in 2..60) rowOf(n)?.let { r ->
                val k = normalise(t)
                if (k.isNotEmpty() && rows[k] != r) { rows[k] = r; added++ }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        lists.forEach { walk(it) }
        return added
    }

    /** Only an exact remembered match: a loose one could jump to the wrong channel. */
    private fun learnedRow(wanted: String): Int? = rows[wanted]

    /** Jump a list straight to a row (RecyclerView-style lists support this). */
    private fun scrollTo(list: AccessibilityNodeInfo, row: Int): Boolean {
        val action = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION
        if (list.actionList.none { it.id == action.id }) return false
        val args = android.os.Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, row)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_COLUMN_INT, 0)
        }
        return list.performAction(action.id, args)
    }

    /** Set when Live TV sent you to TiviMate: Back while watching then returns to the guide. */
    @Volatile var returnToGuide = false

    /** True while TiviMate is in front showing only the picture (no channel list, guide or menu). */
    fun isWatchingFullScreen(service: AccessibilityService): Boolean {
        val roots = tiviRoots(service)
        return roots.isNotEmpty() && roots.none { scrollables(it).isNotEmpty() }
    }

    // ---------- Log ----------

    private val log = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.UK)

    private fun note(s: String) = synchronized(log) {
        log.addLast("${clock.format(Date())}  $s")
        while (log.size > 120) log.removeFirst()
    }

    fun logText(): String = synchronized(log) { if (log.isEmpty()) "No tuning attempts yet." else log.joinToString("\n") }

    // ---------- Tuning ----------

    /** Call right after launching TiviMate. [done] gets true once the channel was selected. */
    fun tune(channel: String, relaunch: (() -> Unit)? = null, doneOuter: (Boolean) -> Unit) {
        // On failure, keep a picture of what TiviMate was showing, for the log.
        val done: (Boolean) -> Unit = { ok ->
            if (!ok) note("What TiviMate showed:\n" + compactScreen())
            doneOuter(ok)
        }
        val service = HomeRedirectService.instance ?: run { note("Accessibility service not running"); return done(false) }
        val wanted = normalise(channel)
        loadRows(service)
        note("---- Tune to \"$channel\" (looking for \"$wanted\"${learnedRow(wanted)?.let { ", remembered row $it" } ?: ""})")
        var jumped = false
        var relaunched = false
        var attempts = 0
        var okPresses = 0
        var settle = 0
        var lastSeen = ""
        var sameCount = 0

        fun step() {
            attempts++
            if (attempts > 90) { note("Gave up after $attempts checks"); done(false); return }
            val roots = tiviRoots(service)
            if (roots.isEmpty()) {
                if (attempts % 4 == 1) note("Waiting for TiviMate (front: ${frontPackages(service)})")
                main.postDelayed({ step() }, 200); return
            }
            val lists = roots.flatMap { scrollables(it) }
            val match = roots.firstNotNullOfOrNull { findInList(it, wanted) }
                ?: roots.firstNotNullOfOrNull { r -> find(r) { n -> normalise(n) == wanted }?.takeIf { lists.isNotEmpty() } }
            if (match != null) {
                note("Found \"${match.text ?: match.contentDescription}\" (${match.className}) after $attempts checks")
                rowOf(match)?.let { row -> if (rows[wanted] != row) { rows[wanted] = row; saveRows(service) } }
                select(service, match) { ok -> finish(service, wanted, ok, done) }
                return
            }
            val texts = roots.flatMap { texts(it) }
            if (lists.isEmpty()) {
                // Playing full screen: let playback settle, then press OK to bring up the channel list.
                if (settle++ < 1) { main.postDelayed({ step() }, 300); return }
                if (okPresses >= 2) { note("No channel list appeared. On screen: ${texts.take(15)}"); done(false); return }
                okPresses++
                note("No list showing - pressing OK (#$okPresses). On screen: ${texts.take(10)}")
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_CENTER)
                settle = 0
                main.postDelayed({ step() }, 550); return
            }
            // First look at the list: jump straight to the remembered row, or to the top.
            if (!jumped) {
                jumped = true
                // TiviMate came back on some other screen (not the channel list): restart it once.
                val known = rows.size >= 3 && texts.none { t -> rows.containsKey(normalise(t)) }
                if (known && !relaunched && relaunch != null) {
                    relaunched = true; jumped = false
                    note("Not the channel list - restarting TiviMate on live TV")
                    relaunch(); main.postDelayed({ step() }, 900); return
                }
                val row = learnedRow(wanted) ?: 0
                if (lists.any { scrollTo(it, row) }) {
                    note("Jumped to row $row")
                    main.postDelayed({ step() }, 220); return
                }
            }
            val seen = texts.joinToString("|")
            if (seen == lastSeen) {
                if (++sameCount >= 2) { note("End of list, not found. Last names seen: ${texts.take(20)}"); done(false); return }
            } else sameCount = 0
            lastSeen = seen
            if (attempts % 3 == 1) note("${lists.size} list(s), names: ${texts.take(12)} - scrolling")
            val scrolled = lists.any { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
            if (!scrolled) repeat(6) { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_DOWN) }
            main.postDelayed({ step() }, 170)
        }
        main.postDelayed({ step() }, 400)
    }

    /** After selecting: if the list is still open over the picture, close it so it's full screen. */
    private fun finish(service: AccessibilityService, wanted: String, ok: Boolean, done: (Boolean) -> Unit) {
        if (!ok) { note("Couldn't select it"); done(false); return }
        main.postDelayed({
            val roots = tiviRoots(service)
            if (roots.any { scrollables(it).isNotEmpty() }) {
                note("List still open - pressing Back for full screen")
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
            note("Done")
            done(true)
        }, 900)
    }

    /**
     * Select the channel: put TiviMate's highlight on it and press OK - but only
     * once TiviMate confirms the highlight has moved there (pressing OK too soon
     * picks whatever was highlighted before). Otherwise click the row instead.
     */
    private fun select(service: AccessibilityService, node: AccessibilityNodeInfo, done: (Boolean) -> Unit) {
        var n: AccessibilityNodeInfo? = node
        var focusable: AccessibilityNodeInfo? = null
        var clickable: AccessibilityNodeInfo? = null
        while (n != null && (focusable == null || clickable == null)) {
            if (focusable == null && n.isFocusable) focusable = n
            if (clickable == null && n.isClickable) clickable = n
            n = n.parent
        }
        val target = clickable
        fun click(): Boolean = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        val f = focusable
        if (f == null || !f.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            if (click()) { note("Clicked it"); done(true) } else { note("Not focusable or clickable"); done(false) }
            return
        }
        var checks = 0
        fun confirm() {
            f.refresh()
            if (f.isFocused) {
                note("Highlight is on it - pressing OK")
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_CENTER)
                done(true)
            } else if (++checks < 8) {
                if (checks == 3) f.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                main.postDelayed({ confirm() }, 80)
            } else if (click()) {
                note("Highlight didn't move - clicked it instead"); done(true)
            } else {
                note("Couldn't move the highlight to it"); done(false)
            }
        }
        main.postDelayed({ confirm() }, 80)
    }

    // ---------- Windows and nodes ----------

    /** Roots of every TiviMate window on screen (its channel list can be a separate pop-up window). */
    private fun tiviRoots(service: AccessibilityService): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        try {
            service.windows?.forEach { w -> w.root?.takeIf { it.packageName?.toString() == PKG }?.let { out += it } }
        } catch (e: Exception) {}
        if (out.isEmpty()) service.rootInActiveWindow?.takeIf { it.packageName?.toString() == PKG }?.let { out += it }
        return out
    }

    private fun frontPackages(service: AccessibilityService): String = try {
        service.windows?.mapNotNull { it.root?.packageName?.toString() }?.distinct()?.joinToString().orEmpty()
    } catch (e: Exception) { "?" }

    private fun scrollables(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo) {
            if (n.isScrollable && n.isVisibleToUser) out += n
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(node)
        return out
    }

    private fun texts(node: AccessibilityNodeInfo): List<String> {
        val out = mutableListOf<String>()
        fun walk(n: AccessibilityNodeInfo) {
            (n.text ?: n.contentDescription)?.toString()?.trim()?.takeIf { it.isNotEmpty() && it.length < 60 }?.let { out += it }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(node)
        return out
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

    /** A matching channel name inside a scrolling list or guide (not the now-playing banner). */
    private fun findInList(node: AccessibilityNodeInfo, wanted: String, inList: Boolean = false): AccessibilityNodeInfo? {
        val here = inList || node.isScrollable
        val text = (node.text ?: node.contentDescription)?.toString()
        if (here && text != null && matches(normalise(text), wanted)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findInList(child, wanted, here)?.let { return it }
        }
        return null
    }

    /** Short version of the screen: one line per piece of text, with list/clickable/focusable flags. */
    private fun compactScreen(): String {
        val service = HomeRedirectService.instance ?: return "(service not running)"
        val lines = mutableListOf<String>()
        val windows = try { service.windows.orEmpty() } catch (e: Exception) { emptyList() }
        val roots = windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(service.rootInActiveWindow) }
        for (root in roots) {
            lines += "[${root.packageName}]"
            fun walk(n: AccessibilityNodeInfo, inList: Boolean) {
                if (lines.size > 70) return
                val list = inList || n.isScrollable
                if (n.isScrollable) lines += "  <list ${n.className?.toString()?.substringAfterLast('.')}>"
                val t = (n.text ?: n.contentDescription)?.toString()?.replace('\n', ' ')?.trim()
                if (!t.isNullOrBlank()) {
                    val f = buildString { if (list) append("L"); if (n.isClickable) append("C"); if (n.isFocusable) append("F"); if (n.isFocused) append("*") }
                    lines += "  ${t.take(40)}${if (f.isNotEmpty()) "  ($f)" else ""}"
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, list) }
            }
            walk(root, false)
        }
        return lines.joinToString("\n").ifBlank { "(nothing readable)" }
    }

    /** Everything on screen, for the iPhone remote's "Capture TV screen". */
    fun dumpScreen(): String {
        val service = HomeRedirectService.instance ?: return "Accessibility service isn't running."
        val sb = StringBuilder()
        val windows = try { service.windows.orEmpty() } catch (e: Exception) { emptyList() }
        val roots = windows.mapNotNull { w -> w.root?.let { "window type=${w.type} layer=${w.layer}" to it } }
            .ifEmpty { listOfNotNull(service.rootInActiveWindow?.let { "active window" to it }) }
        var lines = 0
        for ((label, root) in roots) {
            sb.append("== ").append(label).append(" pkg=").append(root.packageName).append('\n')
            fun walk(n: AccessibilityNodeInfo, depth: Int) {
                if (lines++ > 400) return
                val t = (n.text ?: n.contentDescription)?.toString()?.replace('\n', ' ')
                val flags = buildString {
                    if (n.isScrollable) append('S'); if (n.isClickable) append('C'); if (n.isFocusable) append('F'); if (n.isFocused) append('*')
                }
                sb.append("  ".repeat(depth.coerceAtMost(12))).append(n.className?.toString()?.substringAfterLast('.'))
                if (flags.isNotEmpty()) sb.append(" [").append(flags).append(']')
                n.viewIdResourceName?.let { sb.append(" #").append(it.substringAfter(":id/")) }
                if (!t.isNullOrBlank()) sb.append("  \"").append(t.take(60)).append('"')
                sb.append('\n')
                for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
            }
            walk(root, 0)
        }
        return sb.toString().ifBlank { "Nothing readable on screen." }
    }

    // ---------- Matching ----------

    private val noise = Regex("\\b(uk|gb|hd|fhd|uhd|sd|4k|hevc|h265|raw|vip|backup)\\b|[|:\\[\\]()_.\\-]")

    fun normalise(s: String): String = s.lowercase()
        .replace(Regex("^\\s*\\d{1,4}[.)]?\\s+"), "")
        .replace(noise, " ")
        .replace("bbc 1", "bbc one").replace("bbc1", "bbc one").replace("bbc 2", "bbc two").replace("bbc2", "bbc two")
        .replace("itv 1", "itv1").replace("channel 5", "5").replace("channel5", "5").replace("ch4", "channel 4")
        .replace(Regex("\\s+"), " ").trim()

    private fun matches(candidate: String, wanted: String): Boolean {
        if (candidate.isEmpty() || wanted.isEmpty()) return false
        if (candidate == wanted) return true
        val plusOne = { x: String -> x.contains("+1") }
        if (plusOne(candidate) != plusOne(wanted)) return false
        // "bbc one london" contains "bbc one" as whole words.
        return Regex("(^| )" + Regex.escape(wanted) + "( |$)").containsMatchIn(candidate) && candidate.length <= wanted.length + 14
    }
}
