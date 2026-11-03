package com.morgan.minimallauncher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
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

    // ---------- Channel numbers ----------
    // TiviMate shows each channel's number beside its name. The picker notes them as it
    // passes, so next time it knows which way (and how far) to go straight away.

    private val numbers = HashMap<String, Int>()
    private var numbersLoaded = false

    /** Rough positions of the main UK channels, used until the real numbers are learned. */
    private val HINTS = mapOf(
        "bbc one" to 1, "bbc two" to 2, "itv1" to 3, "itv" to 3, "stv" to 3, "channel 4" to 4, "5" to 5,
        "itv2" to 6, "bbc three" to 7, "bbc four" to 9, "itv3" to 10, "quest" to 12, "e4" to 13,
        "film4" to 14, "really" to 17, "more4" to 18, "dave" to 19, "drama" to 20, "5usa" to 21,
        "itv4" to 26, "yesterday" to 27, "itvbe" to 28, "5star" to 31, "bbc news" to 40,
        "sky news" to 41, "cbbc" to 70, "cbeebies" to 71,
    )

    private fun loadNumbers(c: android.content.Context) {
        if (numbersLoaded) return
        numbersLoaded = true
        try {
            val o = org.json.JSONObject(Prefs.string(c, "tivi_numbers2") ?: "{}")
            o.keys().forEach { k -> numbers[k] = o.getInt(k) }
        } catch (e: Exception) {}
    }

    private fun saveNumbers(c: android.content.Context) {
        val o = org.json.JSONObject()
        numbers.entries.take(3000).forEach { (k, v) -> o.put(k, v) }
        Prefs.setString(c, "tivi_numbers2", o.toString())
    }

    private val numberOnly = Regex("^\\d{1,4}$")
    private val numberThenName = Regex("^(\\d{1,4})[.)]?\\s+(.+)$")

    /** Number and name of each row on screen, e.g. 8 → "channel 4". */
    private fun rowNumbers(list: AccessibilityNodeInfo): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        for (i in 0 until list.childCount) {
            val row = list.getChild(i) ?: continue
            val t = texts(row).take(4)
            var num: Int? = null
            var name: String? = null
            for (x in t) {
                if (num == null && numberOnly.matches(x)) { num = x.toInt(); continue }
                if (num == null) numberThenName.find(x)?.let { m -> num = m.groupValues[1].toInt(); name = m.groupValues[2]; }
                if (num != null && name == null && !numberOnly.matches(x)) name = x
                if (num != null && name != null) break
            }
            val n = num; val nm = name
            if (n != null && nm != null) out += n to normalise(nm)
        }
        return out
    }

    /** Remember the numbers of the channels on screen. Returns the visible number range. */
    private fun harvest(c: android.content.Context, list: AccessibilityNodeInfo): IntRange? {
        val seen = rowNumbers(list)
        if (seen.isEmpty()) return null
        var changed = false
        for ((n, name) in seen) if (name.isNotEmpty() && numbers[gk(name)] != n) { numbers[gk(name)] = n; changed = true }
        if (changed) saveNumbers(c)
        return seen.minOf { it.first }..seen.maxOf { it.first }
    }

    /** The channel's number if learned. Numbers belong to a group, so a guess from another group is never used. */
    private fun numberFor(wanted: String): Pair<Int, Boolean>? =
        numbers[gk(wanted)]?.let { it to true }

    // ---------- Channel groups ----------
    // TiviMate lists channels by group ("UK ~ Entertainment", "UK ~ Regionals"...) and a channel
    // is only in some of them. The picker remembers which group each channel was found in.

    @Volatile private var curGroup = ""
    private fun gk(name: String) = "$curGroup|$name"

    private val groupOf = HashMap<String, String>()
    private var groupsLoaded = false

    private fun loadGroups(c: android.content.Context) {
        if (groupsLoaded) return
        groupsLoaded = true
        try {
            val o = org.json.JSONObject(Prefs.string(c, "tivi_groups") ?: "{}")
            o.keys().forEach { k -> groupOf[k] = o.getString(k) }
        } catch (e: Exception) {}
    }

    private fun saveGroups(c: android.content.Context) {
        val o = org.json.JSONObject()
        groupOf.entries.take(3000).forEach { (k, v) -> o.put(k, v) }
        Prefs.setString(c, "tivi_groups", o.toString())
    }

    private val clockText = Regex("(?i)\\b(am|pm)\\b|^\\d+ min$|^\\d{1,2}:\\d{2}")

    /** TiviMate's list of groups and the one currently open, if the screen shows them. */
    private fun groupInfo(roots: List<AccessibilityNodeInfo>): Triple<AccessibilityNodeInfo, List<String>, String>? {
        val all = roots.flatMap { texts(it) }
        val grids = mutableListOf<AccessibilityNodeInfo>()
        fun collect(n: AccessibilityNodeInfo, d: Int) {
            if (n.className?.toString()?.contains("GridView") == true) grids += n
            if (d < 14) for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, d + 1) }
        }
        roots.forEach { collect(it, 0) }
        for (g in grids) {
            val names = texts(g)
            if (names.size < 3 || names.any { numberOnly.matches(it) || clockText.containsMatchIn(it) }) continue
            val cur = names.firstOrNull { n -> all.count { it == n } >= 2 } ?: continue
            return Triple(g, names, cur)
        }
        return null
    }

    private fun isInside(ancestor: AccessibilityNodeInfo, node: AccessibilityNodeInfo?): Boolean {
        var n = node
        var d = 0
        while (n != null && d++ < 12) { if (n == ancestor) return true; n = n.parent }
        return false
    }

    /** Move TiviMate's highlight onto [target] in the group list and open it. */
    private fun goToGroup(service: AccessibilityService, target: String, done: (Boolean) -> Unit) {
        var rounds = 0
        fun check() {
            if (cancelled || ++rounds > 40) { done(false); return }
            val info = groupInfo(tiviRoots(service)) ?: run { note("Group list not on screen"); done(false); return }
            val (grid, names, cur) = info
            if (cur == target) { done(true); return }
            val focus = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (!isInside(grid, focus)) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_LEFT)
                later(180, { done(false) }) { check() }; return
            }
            val ftxt = focus?.let { texts(it).firstOrNull() ?: it.text?.toString() }
            val fi = names.indexOf(ftxt)
            val ti = names.indexOf(target)
            when {
                ti < 0 -> { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_DOWN) }
                fi == ti -> { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_CENTER); later(500, { done(false) }) { check() }; return }
                fi < 0 -> { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_DOWN) }
                else -> service.performGlobalAction(if (ti > fi) AccessibilityService.GLOBAL_ACTION_DPAD_DOWN else AccessibilityService.GLOBAL_ACTION_DPAD_UP)
            }
            later(130, { done(false) }) { check() }
        }
        note("Switching to group \"$target\"")
        check()
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
    /** Set when a film or show page sent you to TiviMate: Back returns to that page. */
    @Volatile var returnToWatch = false

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
        var finished = false
        val done: (Boolean) -> Unit = done@{ ok ->
            if (finished) return@done
            finished = true
            setBusy(false)
            if (cancelled) {
                cancelled = false
                note("Cancelled - stopped searching")
                hideCover()
                return@done  // no "couldn't find it" message: you chose to stop
            }
            if (!ok) note("What TiviMate showed:\n" + compactScreen())
            // Lift the cover once the picture is up (or straight away if it failed).
            main.postDelayed({ hideCover() }, if (ok) 300 else 0)
            doneOuter(ok)
            HomeRedirectService.instance?.let { Diag.send(it, "tune", "Result: ${if (ok) "selected" else "FAILED"} for \"$channel\"\n" + logText().takeLast(60000)) }
        }
        cancelled = false
        setBusy(true)
        showCover(channel)
        val service = HomeRedirectService.instance ?: run { note("Accessibility service not running"); return done(false) }
        val wanted = normalise(channel)
        loadRows(service)
        loadNumbers(service)
        loadGroups(service)
        note("---- Tune to \"$channel\" (looking for \"$wanted\"${learnedRow(wanted)?.let { ", remembered row $it" } ?: ""})")
        var nudged = false
        var snapped = false
        var goingDown = true
        var directedFailed = false
        var lastRange = ""
        var rangeSame = 0
        var attempts = 0
        var okPresses = 0
        var emptyWaits = 0
        var lastOkAt = 0L
        var menuPressed = false
        val tried = HashSet<String>()
        val visited = HashSet<String>()
        var switches = 0
        var settle = 0
        var lastSeen = ""
        var sameCount = 0

        fun step() {
            if (cancelled) { done(false); return }
            attempts++
            if (attempts > 120) { note("Gave up after $attempts checks"); done(false); return }
            val roots = tiviRoots(service)
            if (roots.isEmpty()) {
                if (attempts % 4 == 1) note("Waiting for TiviMate (front: ${frontPackages(service)})")
                later(60, { done(false) }) { step() }; return
            }
            val lists = roots.flatMap { scrollables(it) }
            if (!snapped && lists.isNotEmpty()) { snapped = true; note("TiviMate's screen when the search started:\n" + compactScreen(onlyTivi = true)) }
            val texts = roots.flatMap { texts(it) }
            // Silence the old channel while the guide is open (not before: OK on a paused
            // picture would un-pause it instead of opening the channel list).
            if (lists.isNotEmpty()) hushNow(service)
            if (lists.isEmpty()) {
                // Playing full screen: let playback settle, then press OK to bring up the channel list.
                if (settle++ < 1) { later(100, { done(false) }) { step() }; return }
                if (okPresses >= 2) { note("No channel list appeared. On screen: ${texts.take(15)}"); done(false); return }
                if (okPresses == 1 && android.os.SystemClock.uptimeMillis() - lastOkAt < 1600) { later(150, { done(false) }) { step() }; return }
                okPresses++
                lastOkAt = android.os.SystemClock.uptimeMillis()
                note("No list showing - pressing OK (#$okPresses). On screen: ${texts.take(10)}")
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_CENTER)
                settle = 0
                later(220, { done(false) }) { step() }; return
            }
            // The channel list (or TV guide grid) is the biggest list on screen - not the group list beside it.
            val list = lists.maxByOrNull { descendants(it) }!!
            val gInfo = groupInfo(roots)
            curGroup = gInfo?.third ?: ""
            if (attempts == 2) note(if (gInfo == null) "Group list not found" else "Group \"$curGroup\" of ${gInfo.second.size}: ${gInfo.second}")
            val match = bestMatch(list, wanted)
            if (match != null) {
                note("Found \"${match.text ?: match.contentDescription}\" after $attempts checks")
                if (curGroup.isNotEmpty() && groupOf[wanted] != curGroup) { groupOf[wanted] = curGroup; saveGroups(service) }
                // Remember the number this name really sits on, so next time we head straight there.
                try {
                    harvest(service, list)
                    val mk = normalise((match.text ?: match.contentDescription).toString())
                    numbers[gk(mk)]?.let { n -> if (numbers[gk(wanted)] != n) { numbers[gk(wanted)] = n; saveNumbers(service) } }
                } catch (e: Exception) {}
                rowOf(match)?.let { row -> if (rows[wanted] != row) { rows[wanted] = row; saveRows(service) } }
                if (rows.isEmpty()) { rows[wanted] = 0; saveRows(service) }
                select(service, list, wanted) { ok -> finish(service, wanted, ok, done) }
                return
            }
            // Not on screen yet: move TiviMate's highlight down the list (then back up), like the remote.
            // OK on the full-screen picture brings up a small menu (TV guide, History...): open the guide from it.
            if (!menuPressed && rowNumbers(list).isEmpty() && texts(list).any { it.equals("TV guide", true) }) {
                val item = findIn(service) { it.equals("TV guide", true) }
                if (item != null) {
                    menuPressed = true
                    note("TiviMate's menu is up - opening the TV guide")
                    pressNode(service, item)
                    emptyWaits = 0
                    later(350, { done(false) }) { step() }; return
                }
            }
            // TiviMate draws the group list first and fills the channel rows a moment later:
            // don't press anything until rows exist, or a stray Right/OK closes the guide.
            if (rowNumbers(list).isEmpty() && emptyWaits < 25) {
                emptyWaits++
                if (emptyWaits == 1) note("Channel rows not drawn yet - waiting")
                later(200, { done(false) }) { step() }; return
            }
            // Wrong group? Switch to the one this channel was found in last time (or the main one).
            fun nextGroup(): String? {
                val names = gInfo?.second ?: return null
                val remembered = groupOf[wanted]?.takeIf { it in names && it !in tried && it != curGroup }
                if (remembered != null) return remembered
                return names.filter { it !in tried && it != curGroup }
                    .sortedBy { if (it.contains("entertainment", true)) 0 else 1 }.firstOrNull()
            }
            fun switchTo(g: String) {
                if (++switches > 10) { note("Tried too many groups"); done(false); return }
                nudged = false; lastSeen = ""; sameCount = 0; goingDown = true; visited.clear()
                directedFailed = false; lastRange = ""; rangeSame = 0; emptyWaits = 0
                goToGroup(service, g) { ok -> if (!ok) { note("Couldn't switch group"); done(false) } else later(500, { done(false) }) { step() } }
            }
            val remembered = groupOf[wanted]
            if (remembered != null && remembered != curGroup && gInfo != null && remembered in gInfo.second && remembered !in tried) {
                switchTo(remembered); return
            }
            if (remembered == null && switches == 0 && gInfo != null && !curGroup.contains("entertainment", true)) {
                val ent = gInfo.second.firstOrNull { it.contains("entertainment", true) }
                if (ent != null) { switchTo(ent); return }
            }
            val inList = focusRowIn(service, list) != null
            if (!inList && !nudged) {
                nudged = true
                note("Highlight isn't in the channel list - pressing Right")
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT)
                later(150, { done(false) }) { step() }; return
            }
            // Head straight for its number when we know (or can guess) it.
            val range = harvest(service, list)
            val target = numberFor(wanted)
            if (range != null && target != null && !directedFailed) {
                val (num, learned) = target
                if (num < range.first || num > range.last) {
                    val sig = range.toString()
                    if (sig == lastRange) { if (++rangeSame >= 3) { directedFailed = true; note("The guide won't move - searching instead") } } else rangeSame = 0
                    lastRange = sig
                    val up = num < range.first
                    val dist = if (up) range.first - num else num - range.last
                    val presses = dist.coerceIn(1, 40)
                    note("Channel ${if (learned) "is" else "is probably around"} $num, guide shows ${range.first}-${range.last} - ${if (up) "up" else "down"} $presses")
                    val key = if (up) AccessibilityService.GLOBAL_ACTION_DPAD_UP else AccessibilityService.GLOBAL_ACTION_DPAD_DOWN
                    for (i in 0 until presses) main.postDelayed({ service.performGlobalAction(key) }, i * 18L)
                    later(presses * 18L + 90, { done(false) }) { step() }
                    return
                }
                // It should be on screen but isn't (a guess was off): search outwards from here.
                if (!learned && !directedFailed) { directedFailed = true; goingDown = num >= (range.first + range.last) / 2 }
            }
            val seen = texts(list).joinToString("|")
            fun groupExhausted(): Boolean {
                if (curGroup.isNotEmpty()) tried.add(curGroup)
                val g = nextGroup() ?: return false
                note("Not in \"$curGroup\" - trying \"$g\"")
                switchTo(g)
                return true
            }
            if (goingDown && seen != lastSeen && !visited.add(seen)) {
                // Came back round to a screen already seen: this group has no such channel.
                if (groupExhausted()) return
            } else visited.add(seen)
            if (seen == lastSeen) {
                if (++sameCount >= 3) {
                    if (goingDown) { goingDown = false; sameCount = 0; note("Reached the bottom - searching upwards") }
                    else { if (groupExhausted()) return; note("Not in this channel group. Last names seen: ${texts(list).take(20)}"); done(false); return }
                }
            } else sameCount = 0
            lastSeen = seen
            if (attempts % 3 == 1) note("Searching ${if (goingDown) "down" else "up"} (highlight on ${rowNames(focusRowIn(service, list))}): ${texts(list).take(6)}")
            val key = if (goingDown) AccessibilityService.GLOBAL_ACTION_DPAD_DOWN else AccessibilityService.GLOBAL_ACTION_DPAD_UP
            for (i in 0 until 8) main.postDelayed({ service.performGlobalAction(key) }, i * 35L)
            later(8 * 35L + 130, { done(false) }) { step() }
        }
        later(80, { done(false) }) { step() }
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
        }, 120)
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
    private fun select(service: AccessibilityService, firstList: AccessibilityNodeInfo, wanted: String, done: (Boolean) -> Unit) {
        var rounds = 0
        var lastTop = Int.MIN_VALUE
        var stuck = 0
        var lastKey = 0
        var lost = 0
        fun check() {
            if (cancelled) { done(false); return }
            // Read the screen afresh each time: TiviMate rebuilds its rows as the guide scrolls.
            val list = tiviRoots(service).flatMap { scrollables(it) }.maxByOrNull { descendants(it) } ?: firstList
            val target = bestMatch(list, wanted)?.let { rowIn(list, it) }
            val focused = focusRowIn(service, list)
            if (target == null) {
                // Went past it: step back the other way, one row at a time.
                if (lastKey == 0 || ++lost > 8) { note("Lost sight of the channel"); done(false); return }
                val back = if (lastKey == AccessibilityService.GLOBAL_ACTION_DPAD_UP) AccessibilityService.GLOBAL_ACTION_DPAD_DOWN else AccessibilityService.GLOBAL_ACTION_DPAD_UP
                if (lost == 1) note("Went past it - stepping back")
                service.performGlobalAction(back)
                later(120, { done(false) }) { check() }; return
            }
            lost = 0
            if (focused == null) {
                if (++rounds > 3) { note("TiviMate's highlight isn't in the list"); done(false); return }
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT)
                later(120, { done(false) }) { check() }; return
            }
            if (focused == target) {
                main.postDelayed({
                    val again = tiviRoots(service).flatMap { scrollables(it) }.maxByOrNull { descendants(it) } ?: list
                    if (cancelled) { done(false); return@postDelayed }
                    if (focusRowIn(service, again) == bestMatch(again, wanted)?.let { rowIn(again, it) }) {
                        note("Highlight is on ${rowNames(target)} - pressing OK")
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_CENTER)
                        done(true)
                    } else check()
                }, 40)
                return
            }
            if (++rounds > 40) { note("Couldn't get the highlight onto it (on ${rowNames(focused)})"); done(false); return }
            val f = android.graphics.Rect().also { focused.getBoundsInScreen(it) }
            val t = android.graphics.Rect().also { target.getBoundsInScreen(it) }
            // Stuck only if neither the highlight nor the channel's row moved.
            val where = f.top * 10007 + t.top
            if (where == lastTop) stuck++ else stuck = 0
            lastTop = where
            if (stuck >= 4) { note("Highlight won't move (on ${rowNames(focused)})"); done(false); return }
            val dy = t.centerY() - f.centerY()
            // Small moves, re-checking after each, so it never overshoots.
            val steps = (kotlin.math.abs(dy) / f.height().coerceAtLeast(1)).coerceIn(1, 7)
            val key = if (dy > 0) AccessibilityService.GLOBAL_ACTION_DPAD_DOWN else AccessibilityService.GLOBAL_ACTION_DPAD_UP
            lastKey = key
            if (rounds == 1) note("Highlight is on ${rowNames(focused)} - moving ${if (dy > 0) "down" else "up"} to it")
            for (i in 0 until steps) main.postDelayed({ service.performGlobalAction(key) }, i * 20L)
            later(steps * 20L + 70, { done(false) }) { check() }
        }
        later(40, { done(false) }) { check() }
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
            if (cancelled) { cancelled = false; note("Cancelled"); return done(false) }
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

    // ---------- Cover ----------
    // A full-screen card over TiviMate while the picker works, so you never see it
    // scrolling through the guide - just "ITV1" and then the picture.

    private var cover: android.view.View? = null
    @Volatile var cancelled = false
    val covering get() = cover != null
    /** True from the start of a search until it finishes (found, failed or cancelled). */
    @Volatile private var busyFlag = false
    @Volatile private var busySince = 0L
    /** True while a search runs - but never for more than 30s, so a hang can't lock the remote. */
    val busy: Boolean get() = busyFlag && android.os.SystemClock.uptimeMillis() - busySince < 30_000L
    private fun setBusy(on: Boolean) { busyFlag = on; if (on) busySince = android.os.SystemClock.uptimeMillis() }

    /** Runs [block] later; an exception ends the search cleanly instead of crashing the app. */
    private fun later(ms: Long, onError: () -> Unit, block: () -> Unit) {
        main.postDelayed({
            try { block() } catch (e: Exception) { note("Error: ${e.javaClass.simpleName} ${e.message ?: ""}"); try { onError() } catch (e2: Exception) {} }
        }, ms)
    }

    /** Back pressed mid-search: stop everything now. */
    fun cancel() {
        if (!busy) return
        cancelled = true
        hideCover()
    }

    fun showCover(channel: String, subtitle: String = "Tuning…") {
        val service = HomeRedirectService.instance ?: run { note("Cover: the accessibility service isn't running, so nothing can hide TiviMate"); return }
        main.post {
            cover?.let { (it.findViewWithTag<android.widget.TextView>("name"))?.text = channel; return@post }
            val c = service
            val wm = c.getSystemService(android.view.WindowManager::class.java)
            val box = android.widget.LinearLayout(c).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setBackgroundColor(0xFF000000.toInt())
                alpha = 0f
            }
            box.addView(GlassUi.text(c, channel, 44f, R.font.inter_semibold).apply { tag = "name" })
            box.addView(GlassUi.text(c, subtitle, 15f, R.font.inter_medium, 0x99FFFFFF.toInt()).apply {
                setPadding(0, GlassUi.dp(c, 10f), 0, 0)
            })
            box.addView(android.widget.ProgressBar(c).apply {
                isIndeterminate = true
                indeterminateTintList = android.content.res.ColorStateList.valueOf(0x99FFFFFF.toInt())
            }, android.widget.LinearLayout.LayoutParams(GlassUi.dp(c, 28f), GlassUi.dp(c, 28f)).apply { topMargin = GlassUi.dp(c, 22f) })
            val lp = android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // Never takes focus or touches: the remote presses still go to TiviMate underneath.
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT)
            try {
                wm.addView(box, lp)
                cover = box
                note("Cover is up")
                mute(c, true)
                hush(c)
                box.animate().alpha(1f).setDuration(150).start()
                // Never leave it up by mistake.
                main.postDelayed({ hideCover() }, 25_000)
            } catch (e: Exception) { note("Couldn't show the cover: ${e.message}") }
        }
    }

    /** Is [t] the row for season [sn] episode [en] in TiviMate's episode list ("E4. Show - S01E04 - Title")? */
    private fun episodeRow(t: String, sn: Int, en: Int): Boolean {
        Regex("S0*(\\d+)E0*(\\d+)").find(t)?.let { m -> return m.groupValues[1].toInt() == sn && m.groupValues[2].toInt() == en }
        return sn == 1 && Regex("^E0*$en\\.").containsMatchIn(t)
    }

    // Hold audio focus while the cover is up: players that respect it (TiviMate's live channel does) pause
    // instead of being heard underneath. Muting the stream alone doesn't work on every TV.
    private var focusRequest: android.media.AudioFocusRequest? = null

    private fun holdAudio(c: android.content.Context, on: Boolean) {
        val am = c.getSystemService(android.media.AudioManager::class.java) ?: return
        try {
            if (on && focusRequest == null) {
                val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
                    .setOnAudioFocusChangeListener { }.build()
                if (am.requestAudioFocus(req) == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focusRequest = req
            } else if (!on) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
                focusRequest = null
            }
        } catch (e: Exception) {}
    }

    private val quietStreams = intArrayOf(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.STREAM_NOTIFICATION,
        android.media.AudioManager.STREAM_SYSTEM)
    private val mutedStreams = HashSet<Int>()
    private var mutedByUs = false

    /**
     * Pause whatever TiviMate is playing (the old channel), so nothing is heard while
     * the cover is up. The Chromecast's own volume is fixed (the TV controls it), so
     * muting alone doesn't work there.
     */
    private var lastHush = 0L

    /** [hush], at most every 700ms. */
    private fun hushNow(c: android.content.Context) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastHush < 700) return
        lastHush = now
        hush(c)
    }

    fun hush(c: android.content.Context) {
        try {
            val msm = c.getSystemService(android.media.session.MediaSessionManager::class.java)
            val sessions = msm.getActiveSessions(android.content.ComponentName(c, MediaListener::class.java))
            val tivi = sessions.filter { it.packageName == PKG }
            if (tivi.isNotEmpty()) { tivi.forEach { it.transportControls.pause() }; note("Audio: paused TiviMate through its media session"); return }
            note("Audio: no TiviMate media session (sessions: ${sessions.map { it.packageName }})")
        } catch (e: Exception) { note("Audio: can't read media sessions (${e.javaClass.simpleName}) - is notification access on for the launcher?") }
        // No media session to talk to: the remote's Pause key instead.
        try {
            val am = c.getSystemService(android.media.AudioManager::class.java)
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE))
            note("Audio: sent the Pause media key")
        } catch (e: Exception) {}
    }

    /** What the audio system looks like right now, for the remote diagnostics. */
    fun audioReport(c: android.content.Context): String {
        val am = c.getSystemService(android.media.AudioManager::class.java)
        val sb = StringBuilder()
        sb.append("Volume fixed: ${am.isVolumeFixed}\n")
        for ((n, st) in listOf("music" to android.media.AudioManager.STREAM_MUSIC, "notification" to android.media.AudioManager.STREAM_NOTIFICATION, "system" to android.media.AudioManager.STREAM_SYSTEM))
            sb.append("$n: volume ${am.getStreamVolume(st)}/${am.getStreamMaxVolume(st)}, muted ${am.isStreamMute(st)}\n")
        try {
            val msm = c.getSystemService(android.media.session.MediaSessionManager::class.java)
            val ss = msm.getActiveSessions(android.content.ComponentName(c, MediaListener::class.java))
            sb.append("Media sessions: ").append(if (ss.isEmpty()) "none" else ss.joinToString { "${it.packageName}=${it.playbackState?.state}" }).append('\n')
        } catch (e: Exception) { sb.append("Media sessions: not readable (${e.javaClass.simpleName})\n") }
        sb.append("Audio playing: ").append(try { am.activePlaybackConfigurations.joinToString { "${it.audioAttributes.usage}" }.ifEmpty { "nothing" } } catch (e: Exception) { "?" })
        return sb.toString()
    }

    /** Silence the TV while TiviMate is being driven, so the old channel and search chimes aren't heard. */
    private fun mute(c: android.content.Context, on: Boolean) {
        val am = c.getSystemService(android.media.AudioManager::class.java) ?: return
        holdAudio(c, on)
        if (on) {
            for (st in quietStreams) try {
                if (am.isStreamMute(st)) continue
                am.adjustStreamVolume(st, android.media.AudioManager.ADJUST_MUTE, 0)
                mutedStreams += st
            } catch (e: Exception) {}
            mutedByUs = mutedStreams.isNotEmpty()
        } else {
            for (st in mutedStreams.toList()) try { am.adjustStreamVolume(st, android.media.AudioManager.ADJUST_UNMUTE, 0) } catch (e: Exception) {}
            mutedStreams.clear()
            mutedByUs = false
        }
    }

    fun hideCover() {
        main.post {
            val v = cover ?: return@post
            cover = null
            // Sound back once the new channel or film is on.
            main.postDelayed({ if (cover == null) mute(v.context, false) }, 250)
            v.animate().alpha(0f).setDuration(250).withEndAction {
                try { (v.context.getSystemService(android.view.WindowManager::class.java)).removeView(v) } catch (e: Exception) {}
            }.start()
        }
    }

    // ---------- Films and shows in TiviMate ----------
    // TiviMate (with a provider that has films and series) has its own search. This opens
    // it, types the title, picks the matching film or show and presses Play - behind the
    // cover, like tuning a channel.

    /** "EN - The Batman (2022) 4K" -> "the batman". */
    fun vodKey(s: String): String = TextMatch.vodKey(s)

    private val playLabels = listOf("play", "watch", "resume", "continue", "watch now", "play now", "start")

    private fun isPlayLabel(t: String): Boolean {
        val s = t.lowercase().trim()
        if (s.length > 30 || "trailer" in s) return false
        return playLabels.any { s == it || s.startsWith("$it ") }
    }

    private fun findNode(service: AccessibilityService, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun walk(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (test(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { c -> walk(c)?.let { return it } }
            return null
        }
        return tiviRoots(service).firstNotNullOfOrNull { walk(it) }
    }

    fun playVod(c: android.content.Context, title: String, year: Int, show: Boolean, episode: Pair<Int, Int>? = null, fromStart: Boolean = false) {
        val app = c.applicationContext
        fun say(t: String) = android.widget.Toast.makeText(app, t, android.widget.Toast.LENGTH_LONG).show()
        val intent = LiveGuide.launchIntent(app, PKG) ?: return say("TiviMate isn’t installed")
        if (!canTune()) return say("Turn on the accessibility service in Settings to open films on Dodgy Box")
        val service = HomeRedirectService.instance ?: return
        val wanted = vodKey(title)
        cancelled = false
        setBusy(true)
        returnToWatch = true
        showCover(title, "Opening Dodgy Box…")
        hush(app)                                    // pause the live channel before TiviMate comes forward
        note("---- Find ${if (show) "show" else "film"} \"$title\" ($year) in TiviMate (looking for \"$wanted\")")
        try { app.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}

        var stage = 0          // 0 find search, 1 wait for results, 2 press Play
        var attempts = 0
        var stageTries = 0
        var lefts = 0
        var backs = 0
        var typed = false
        var episodeScrolls = 0
        var episodeGaveUp = false
        var finished = false
        fun finish(ok: Boolean, why: String) {
            if (finished) return
            finished = true
            setBusy(false)
            if (cancelled) { cancelled = false; note("Cancelled"); hideCover(); return }
            note(why)
            HomeRedirectService.instance?.let { Diag.send(it, "film", "Result: ${if (ok) "playing" else "FAILED"} for \"$title\"\n" + logText().takeLast(60000)) }
            if (ok) main.postDelayed({ hideCover() }, 500)
            else {
                note("What TiviMate showed:\n" + compactScreen())
                // Back to the film's page rather than leaving you in TiviMate's search.
                returnToWatch = false
                try {
                    app.startActivity(Intent(app, WatchActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                } catch (e: Exception) {}
                hideCover()
                say("Couldn’t find $title on Dodgy Box")
            }
        }
        fun step() {
            if (cancelled) return finish(false, "Cancelled")
            if (++attempts > 90) return finish(false, "Gave up")
            if (tiviRoots(service).isEmpty()) { later(100, { finish(false, "Error") }) { step() }; return }
            // Keep TiviMate's live channel quiet until the film itself is started.
            if (stage < 2) hushNow(service)
            when (stage) {
                0 -> {
                    val field = findNode(service) { it.isEditable || it.className?.toString()?.endsWith("EditText") == true }
                    if (field != null) {
                        val args = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, title) }
                        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        typed = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        if (Build.VERSION.SDK_INT >= 30) field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                        note("Typed \"$title\" into TiviMate's search ($typed)")
                        stage = 1; stageTries = 0
                        later(300, { finish(false, "Error") }) { step() }; return
                    }
                    val search = findNode(service) { n ->
                        val t = (n.text ?: n.contentDescription)?.toString()?.lowercase()?.trim()
                        t == "search" || t == "search…" || t == "search..."
                    }
                    when {
                        search != null -> { note("Opening Search"); pressNode(service, search); later(500, { finish(false, "Error") }) { step() } }
                        lefts < 2 -> { lefts++; service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_LEFT); later(250, { finish(false, "Error") }) { step() } }
                        backs < 2 -> { backs++; lefts = 0; note("Looking for TiviMate's menu (Back)"); service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); later(400, { finish(false, "Error") }) { step() } }
                        else -> finish(false, "Couldn't find TiviMate's Search")
                    }
                }
                1 -> {
                    // A result with the same title (the year decides between remakes), not the search box itself.
                    var best: AccessibilityNodeInfo? = null
                    var bestScore = 0
                    fun walk(n: AccessibilityNodeInfo) {
                        val t = (n.text ?: n.contentDescription)?.toString()
                        if (t != null && !n.isEditable && t.length < 90) {
                            val k = vodKey(t)
                            val otherYear = Regex("\\((19|20)\\d\\d\\)").find(t)?.value?.let { y -> !(year > 0 && y.contains(year.toString())) } == true
                            val score = when {
                                k == wanted && show && otherYear -> 0          // a film of the same name, not the series
                                k == wanted && otherYear && stageTries < 8 -> 0 // wrong year: keep looking a moment
                                k == wanted && year > 0 && t.contains(year.toString()) -> 3
                                k == wanted -> 2
                                k.startsWith("$wanted ") && k.length <= wanted.length + 12 -> 1
                                else -> 0
                            }
                            if (score > bestScore) { best = n; bestScore = score }
                        }
                        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
                    }
                    tiviRoots(service).forEach { walk(it) }
                    val hit = best
                    if (hit != null) {
                        note("Found \"${hit.text ?: hit.contentDescription}\" - opening it")
                        pressNode(service, hit)
                        stage = 2; stageTries = 0
                        later(450, { finish(false, "Error") }) { step() }; return
                    }
                    if (++stageTries > 20) return finish(false, "Not found in TiviMate's films and series")
                    later(150, { finish(false, "Error") }) { step() }
                }
                else -> {
                    val want = episode
                    if (want != null && !episodeGaveUp) {
                        val (sn, en) = want
                        val row = findNode(service) { n -> (n.text ?: n.contentDescription)?.toString()?.let { episodeRow(it, sn, en) } == true }
                        if (row != null) {
                            note("TiviMate's page for it:\n" + compactScreen(onlyTivi = true))
                            note("Pressing episode S$sn E$en: \"${row.text ?: row.contentDescription}\"")
                            WatchHistory.record(app, title, sn, en)
                            pressNode(service, row)
                            main.postDelayed({ finish(true, "Playing S$sn E$en") }, 450)
                            return
                        }
                        // Not in the few rows showing: scroll TiviMate's episode list.
                        val list = findNode(service) { n -> n.isScrollable && texts(n).any { Regex("^E\\d+\\.").containsMatchIn(it) } }
                        if (list != null && episodeScrolls++ < 12) {
                            list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                            later(220, { finish(false, "Error") }) { step() }; return
                        }
                        if (list != null || stageTries > 4) { episodeGaveUp = true; note("Episode S$sn E$en not found - playing from TiviMate's own Play button") }
                        else { stageTries++; later(200, { finish(false, "Error") }) { step() }; return }
                    }
                    // Start over when asked to (TiviMate offers "Play from beginning" beside "Resume"); otherwise resume.
                    val startOver = if (fromStart && !show) findNode(service) { n ->
                        (n.text ?: n.contentDescription)?.toString()?.lowercase()?.let { it.startsWith("play from") || it.startsWith("start over") || it.startsWith("restart") } == true
                    } else null
                    val play = startOver ?: findNode(service) { n -> (n.text ?: n.contentDescription)?.toString()?.let(::isPlayLabel) == true }
                    if (play != null) {
                        note("TiviMate's page for it:\n" + compactScreen(onlyTivi = true))
                        note("Pressing \"${play.text ?: play.contentDescription}\"")
                        Regex("S(\\d+)\\s*E(\\d+)").find((play.text ?: play.contentDescription).toString())?.let { m ->
                            WatchHistory.record(app, title, m.groupValues[1].toInt(), m.groupValues[2].toInt())
                        }
                        pressNode(service, play)
                        if (!show) WatchHistory.markFilm(app, title)
                        main.postDelayed({ finish(true, "Playing") }, 450)
                        return
                    }
                    // Some films start straight away: nothing left but the picture.
                    if (isWatchingFullScreen(service) && stageTries > 2) return finish(true, "Playing")
                    if (++stageTries > 14) return finish(false, "No Play button on TiviMate's page")
                    later(150, { finish(false, "Error") }) { step() }
                }
            }
        }
        later(250, { finish(false, "Error") }) { step() }
    }

    // ---------- Windows and nodes ----------

    /** Roots of every TiviMate window on screen (its channel list can be a separate pop-up window). */
    private var lastClear = 0L

    private fun tiviRoots(service: AccessibilityService): List<AccessibilityNodeInfo> {
        // TiviMate's guide doesn't announce when it scrolls, so Android would keep serving an
        // old copy of the screen. Throw that copy away and read what's really there.
        // (At most every 60ms - each refresh re-reads TiviMate's whole screen.)
        val now = android.os.SystemClock.uptimeMillis()
        if (Build.VERSION.SDK_INT >= 33 && now - lastClear > 60) {
            lastClear = now
            try { service.clearCache() } catch (e: Exception) {}
        }
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
    /** What TiviMate is showing (only TiviMate - used when Claude asks to see the screen). */
    fun tiviScreen(): String = compactScreen(onlyTivi = true)

    private fun compactScreen(onlyTivi: Boolean = false): String {
        val service = HomeRedirectService.instance ?: return "(service not running)"
        val lines = mutableListOf<String>()
        val windows = try { service.windows.orEmpty() } catch (e: Exception) { emptyList() }
        val roots = windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(service.rootInActiveWindow) }
            .filter { !onlyTivi || it.packageName?.toString() == PKG }
        for (root in roots) {
            lines += "[${root.packageName}]"
            fun walk(n: AccessibilityNodeInfo, inList: Boolean) {
                if (lines.size > 70) return
                val list = inList || n.isScrollable
                if (n.isScrollable) lines += "  <list ${n.className?.toString()?.substringAfterLast('.')}>"
                val t = (n.text ?: n.contentDescription)?.toString()?.replace('\n', ' ')?.trim()
                if (!t.isNullOrBlank()) {
                    val f = buildString { if (list) append("L"); if (n.isClickable) append("C"); if (n.isFocusable) append("F"); if (n.isFocused) append("*") }
                    val id = n.viewIdResourceName?.substringAfter('/')?.let { " #$it" } ?: ""
                    lines += "  ${t.take(40)}${if (f.isNotEmpty()) "  ($f)" else ""}$id"
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

    fun normalise(s: String): String = TextMatch.normalise(s)

    private fun matches(candidate: String, wanted: String) = TextMatch.matches(candidate, wanted)
}
