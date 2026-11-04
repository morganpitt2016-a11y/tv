package com.morgan.minimallauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Remind me" from the TV guide: a minute before a programme starts, a box pops up over
 * whatever is on screen with "Watch now". Checked by the launcher's always-on service.
 */
object Reminders {
    data class Item(val liveId: Int, val channel: String, val category: String, val title: String, val start: Long)

    private const val KEY = "reminders_v1"

    @Synchronized fun all(c: Context): List<Item> = try {
        val a = JSONArray(Prefs.string(c, KEY) ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Item(it.getInt("id"), it.getString("ch"), it.optString("cat"), it.getString("t"), it.getLong("s"))
        }
    } catch (e: Exception) { emptyList() }

    @Synchronized private fun save(c: Context, list: List<Item>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.liveId).put("ch", it.channel).put("cat", it.category).put("t", it.title).put("s", it.start)) }
        Prefs.setString(c, KEY, a.toString())
    }

    fun has(c: Context, liveId: Int, start: Long) = all(c).any { it.liveId == liveId && it.start == start }

    fun add(c: Context, item: Item) = save(c, all(c).filterNot { it.liveId == item.liveId && it.start == item.start } + item)

    fun remove(c: Context, liveId: Int, start: Long) = save(c, all(c).filterNot { it.liveId == liveId && it.start == start })

    /** Called every half a minute: anything starting within the next minute pops up (once). */
    fun tick(c: Context) {
        val now = System.currentTimeMillis()
        val list = all(c)
        if (list.isEmpty()) return
        val due = list.filter { it.start - now < 60_000L }
        if (due.isEmpty()) return
        save(c, list - due.toSet())
        due.filter { now - it.start < 10 * 60_000L }.lastOrNull()?.let { r ->       // ignore ones long gone (TV was off)
            try {
                c.startActivity(Intent(c, ReminderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("id", r.liveId).putExtra("ch", r.channel).putExtra("cat", r.category).putExtra("t", r.title).putExtra("s", r.start))
            } catch (e: Exception) {}
        }
    }
}

/** The pop-up: "Starting now - Watch now / Not now". */
class ReminderActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra("id", -1)
        val ch = intent.getStringExtra("ch").orEmpty()
        val title = intent.getStringExtra("t").orEmpty()
        val start = intent.getLongExtra("s", 0L)
        val soon = start > System.currentTimeMillis() + 15_000L
        val app = applicationContext
        val d = GlassDialog(this).title(title).message(if (soon) "Starts in a minute on $ch." else "Starting now on $ch.")
        d.button("Watch Now", GlassDialog.Style.BOLD) {
            PlayerActivity.play(this, Xtream.liveUrl(app, id), ch, live = true, subtitle = title, eyebrow = "LIVE",
                alt = Xtream.liveUrl(app, id, hls = true), liveId = id, liveCat = intent.getStringExtra("cat"))
            finish()
        }
        d.button("Not Now") { finish() }
        val dialog = d.show()
        dialog.setOnDismissListener { if (!isFinishing) finish() }
    }
}
