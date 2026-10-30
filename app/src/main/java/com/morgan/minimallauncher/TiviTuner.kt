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
        var nudged = false
        var goingDown = true
        var attempts = 0
        var okPresses = 0
        var settle = 0
        var lastSeen = ""
        var sameCount = 0

        fun step() {
            attempts++
            if (attempts > 120) { note("Gave up after $attempts checks"); done(false); return }
            val roots = tiviRoots(service)
            if (roots.isEmpty()) {
                if (attempts % 4 == 1) note("Waiting for TiviMate (front: ${frontPackages(service)})")
                main.postDelayed({ step() }, 200); return
            }
            val lists = roots.flatMap { scrollables(it) }
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
            // The channel list (or TV guide grid) is the biggest list on screen - not the group list beside it.
            val list = lists.maxByOrNull { descendants(it) }!!
            val match = bestMatch(list, wanted)
            if (match != null) {
                note("Found \"${match.text ?: match.contentDescription}\" after $attempts checks")
                rowOf(match)?.let { row -> if (rows[wanted] != row) { rows[wanted] = row; saveRows(service) } }
                if (rows.isEmpty()) { rows[wanted] = 0; saveRows(service) }
                select(service, list, wanted) { ok -> finish(service, wanted, ok, done) }
                return
            }
            // Not on screen yet: move TiviMate's highlight down the list (then back up), like the remote.
            val inList = focusRowIn(service, list) != null
            if (!inList && !nudged) {
                nudged = true
                note("Highlight isn't in the channel list - pressing Right")
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT)
                main.postDelayed({ step() }, 300); return
            }
            val seen = texts(list).joinToString("|")
            if (seen == lastSeen) {
                if (++sameCount >= 2) {
                    if (goingDown) { goingDown = false; sameCount = 0; note("Reached the bottom - searching upwards") }
                    else { note("Not in this channel group. Last names seen: ${texts(list).take(20)}"); done(false); return }
                }
            } else sameCount = 0
            lastSeen = seen
            if (attempts % 4 == 1) note("Searching ${if (goingDown) "down" else "up"}: ${texts(list).take(8)}")
            val key = if (goingDown) AccessibilityService.GLOBAL_ACTION_DPAD_DOWN else AccessibilityService.GLOBAL_ACTION_DPAD_UP
            for (i in 0 until 8) main.postDelayed({ service.performGlobalAction(key) }, i * 40L)
            main.postDelayed({ step() }, 8 * 40L + 200)
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

    private fun descendants(n: AccessibilityNodeInfo): Int {
        var c = 0
        fun walk(x: AccessibilityNodeInfo, d: Int) { c++; if (d < 6) for (i in 0 until x.childCount) x.getChild(i)?.let { walk(it, d + 1) } }
        walk(n, 0)
        return c
    }

    /** The channel's name in [list]: an exact name first, then a close one ("BBC One London" for "BBC One"). */
    private fun bestMatch(list: AccessibilityNodeInfo, wanted: String): AccessibilityNodeInfo? {
        var loose: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val t = (n.text ?: n.contentDescription)?.toString()
            if (t != null && t.length < 60) {
                val k = normalise(t)
                if (k == wanted) return n
                if (loose == null && matches(k, wanted)) loose = n
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { c -> walk(c)?.let { return it } }
            return null
        }
        return walk(list) ?: loose
    }

    /** The row of [list] that [node] is in (its ancestor directly inside the list). */
    private fun rowIn(list: AccessibilityNodeInfo, node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var n = node
        var depth = 0
        while (n != null && depth++ < 12) {
            val parent = n.parent ?: return null
            if (parent == list) return n
            n = parent
        }
        return null
    }

    private fun focusRowIn(service: AccessibilityService, list: AccessibilityNodeInfo) =
        rowIn(list, service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT))

    private fun rowNames(row: AccessibilityNodeInfo?) = row?.let { texts(it).take(3) } ?: emptyList()

    /**
     * Select the channel the way the remote does: move TiviMate's own highlight
     * (Up/Down) until it is on the channel's row, check it stays there, then
     * press OK. Works in the channel list and in TiviMate's TV guide, where the
     * highlight sits on a programme in the channel's row.
     */
    private fun select(service: AccessibilityService, list: AccessibilityNodeInfo, wanted: String, done: (Boolean) -> Unit) {
        var rounds = 0
        var lastTop = Int.MIN_VALUE
        var stuck = 0
        fun check() {
            list.refresh()
            val target = bestMatch(list, wanted)?.let { rowIn(list, it) }
            val focused = focusRowIn(service, list)
            if (target == null) { note("Lost sight of the channel"); done(false); return }
            if (focused == null) {
                if (++rounds > 3) { note("TiviMate's highlight isn't in the list"); done(false); return }
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT)
                main.postDelayed({ check() }, 250); return
            }
            if (focused == target) {
                main.postDelayed({
                    if (focusRowIn(service, list) == target) {
                        note("Highlight is on ${rowNames(target)} - pressing OK")
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_CENTER)
                        done(true)
                    } else check()
                }, 150)
                return
            }
            if (++rounds > 40) { note("Couldn't get the highlight onto it (on ${rowNames(focused)})"); done(false); return }
            val f = android.graphics.Rect().also { focused.getBoundsInScreen(it) }
            val t = android.graphics.Rect().also { target.getBoundsInScreen(it) }
            if (f.top == lastTop) stuck++ else stuck = 0
            lastTop = f.top
            if (stuck >= 4) { note("Highlight won't move (on ${rowNames(focused)})"); done(false); return }
            val dy = t.centerY() - f.centerY()
            val steps = (kotlin.math.abs(dy) / f.height().coerceAtLeast(1)).coerceIn(1, 10)
            val key = if (dy > 0) AccessibilityService.GLOBAL_ACTION_DPAD_DOWN else AccessibilityService.GLOBAL_ACTION_DPAD_UP
            if (rounds == 1) note("Highlight is on ${rowNames(focused)} - moving ${if (dy > 0) "down" else "up"} to it")
            for (i in 0 until steps) main.postDelayed({ service.performGlobalAction(key) }, i * 45L)
            main.postDelayed({ check() }, steps * 45L + 180)
        }
        main.postDelayed({ check() }, 100)
    }

    // ---------- Recording ----------

    private fun isRecordLabel(t: String): Boolean {
        val s = t.lowercase().trim()
        if (s.length > 40 || listOf("recordings", "stop", "cancel", "delete", "remove", "recorded").any { it in s }) return false
        return s == "record" || s == "rec" || s.startsWith("record ") || s.startsWith("start record") || s.endsWith(" record")
    }

    private fun isConfirmLabel(t: String): Boolean =
        t.lowercase().trim() in setOf("ok", "yes", "start", "confirm", "record", "record program", "record programme", "save")

    private fun findIn(service: AccessibilityService, test: (String) -> Boolean): AccessibilityNodeInfo? =
        tiviRoots(service).firstNotNullOfOrNull { root -> find(root) { test(it) } }

    /** Press a button the way the remote would: click it, or highlight it and press OK. */
    private fun pressNode(service: AccessibilityService, node: AccessibilityNodeInfo) {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable && !n.isFocusable) n = n.parent
        val target = n ?: node
        if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        main.postDelayed({ service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_CENTER) }, 150)
    }

    /**
     * With TiviMate showing the channel, find its Record button and press it:
     * first on screen as it is, then after OK (TiviMate's controls), then by
     * holding OK on the highlighted channel (its options menu).
     */
    fun record(done: (Boolean) -> Unit) {
        val service = HomeRedirectService.instance ?: run { note("Accessibility service not running"); return done(false) }
        note("---- Record")
        var stage = 0
        fun attempt() {
            val button = findIn(service, ::isRecordLabel)
            if (button != null) {
                note("Found \"${button.text ?: button.contentDescription}\" - pressing it")
                val pressed = android.graphics.Rect().also { button.getBoundsInScreen(it) }
                pressNode(service, button)
                main.postDelayed({ confirmRecording(service, 0, pressed, done) }, 900)
                return
            }
            when (stage++) {
                0 -> { note("No Record button showing - pressing OK for TiviMate's controls"); service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_CENTER) }
                1 -> {
                    val f = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    note("Holding OK on \"${f?.text ?: f?.contentDescription ?: "the highlighted item"}\" for its options")
                    f?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                }
                else -> {
                    note("Couldn't find TiviMate's Record button. What TiviMate showed:\n" + compactScreen())
                    backToPicture(service)
                    return done(false)
                }
            }
            main.postDelayed({ attempt() }, 900)
        }
        attempt()
    }

    /** TiviMate may ask how to record (this programme, or for a time): accept its first choice. */
    private fun confirmRecording(service: AccessibilityService, round: Int, pressed: android.graphics.Rect, done: (Boolean) -> Unit) {
        // Never the button already pressed: pressing Record twice would stop the recording.
        fun notPressed(n: AccessibilityNodeInfo?) = n?.takeIf { android.graphics.Rect().also { r -> it.getBoundsInScreen(r) } != pressed }
        val next = if (round < 2) notPressed(findIn(service, ::isConfirmLabel)) ?: notPressed(findIn(service, ::isRecordLabel)) else null
        if (next != null) {
            note("TiviMate asked - choosing \"${next.text ?: next.contentDescription}\"")
            pressNode(service, next)
            main.postDelayed({ confirmRecording(service, round + 1, pressed, done) }, 900)
            return
        }
        note("Recording started")
        backToPicture(service)
        done(true)
    }

    /** Close any list or menu left open, back to the full-screen picture. */
    private fun backToPicture(service: AccessibilityService) {
        if (tiviRoots(service).any { scrollables(it).isNotEmpty() }) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
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
