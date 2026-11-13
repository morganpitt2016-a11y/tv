package com.morgan.minimallauncher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Voice control: press the remote's mic button (or Voice in Control Centre) and say
 *   "Put BBC One on" · "Play Gladiator" · "Watch The Traitors" · "Open Netflix" ·
 *   "TV guide" · "Sport" · "Settings" · anything else searches.
 */
class VoiceActivity : AppCompatActivity() {
    private val listen = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (said.isNullOrBlank()) { finish(); return@registerForActivityResult }
        act(said)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            listen.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Try “Put BBC One on” or “Play Gladiator”"))
        } catch (e: Exception) {
            GlassUi.toast(this, "Voice isn’t available on this TV"); finish()
        }
    }

    private fun act(raw: String) {
        val s = raw.lowercase().trim().trimEnd('.', '!', '?')
        val app = applicationContext
        fun open(cls: Class<*>, tab: String? = null) {
            startActivity(Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { tab?.let { putExtra(WatchActivity.EXTRA_TAB, it) } }); finish()
        }
        when {
            s in listOf("home", "go home") -> { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finish(); return }
            s.contains("guide") -> { open(LiveGuideActivity::class.java); return }
            s == "sport" || s == "sports" || s.contains("football") && s.length < 20 -> { open(WatchActivity::class.java, "sport"); return }
            s.contains("settings") -> { open(SettingsActivity::class.java); return }
            s.startsWith("open ") || s.startsWith("launch ") -> { openApp(s.substringAfter(' ')); return }
        }
        // "put X on", "switch to X", "change to X", "turn on X": a channel first, then a film or series.
        val chanWords = listOf("put on ", "put ", "switch to ", "change to ", "turn on ", "go to ", "channel ")
        val playWords = listOf("play ", "watch ", "start ", "show me ", "find ")
        val cw = chanWords.firstOrNull { s.startsWith(it) }
        val pw = playWords.firstOrNull { s.startsWith(it) }
        val what = when {
            cw != null -> s.removePrefix(cw).removeSuffix(" on").trim()
            pw != null -> s.removePrefix(pw).trim()
            else -> s
        }
        if (!Xtream.isSetUp(app)) { search(raw); return }
        GlassUi.toast(this, "Looking for “$what”…")
        Bg.run {
            val channel = if (cw != null || pw != null || what.split(' ').size <= 3) findChannel(app, what) else null
            val lib = if (channel == null) try { Xtream.searchLibrary(app, what, 3) } catch (e: Exception) { null } else null
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val film = lib?.first?.firstOrNull()
                val show = lib?.second?.firstOrNull()
                val exactFilm = film != null && TextMatch.vodKey(Xtream.clean(film.name)) == TextMatch.vodKey(what)
                when {
                    channel != null -> {
                        PlayerActivity.play(this, Xtream.liveUrl(app, channel.id), channel.name, live = true, subtitle = LiveGuide.nowNext(app, channel.name),
                            eyebrow = "LIVE", alt = Xtream.liveUrl(app, channel.id, hls = true), liveId = channel.id, liveCat = channel.category)
                        finish()
                    }
                    show != null && (!exactFilm) -> {
                        startActivity(Intent(this, EpisodesActivity::class.java).putExtra(EpisodesActivity.EXTRA_NAME, Xtream.clean(show.name))
                            .putExtra(EpisodesActivity.EXTRA_IMAGE, show.cover).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    }
                    film != null -> {
                        PlayerActivity.play(this, Xtream.vodUrl(app, film), Xtream.clean(film.name), live = false, eyebrow = "FILM", image = film.icon)
                        finish()
                    }
                    else -> search(raw)
                }
            }
        }
    }

    /** A channel whose name matches what was said ("bbc one", "sky sports main event"). */
    private fun findChannel(c: Context, said: String): Xtream.Live? {
        val want = TextMatch.normalise(said.replace(" plus one", " +1").replace("one", "one"))
        if (want.isBlank()) return null
        val all = try { Xtream.best(Xtream.live(c)) } catch (e: Exception) { return null }
        return all.firstOrNull { TextMatch.normalise(it.name) == want }
            ?: all.firstOrNull { TextMatch.matches(TextMatch.normalise(it.name), want) }
    }

    private fun openApp(name: String) {
        val apps = AppListHelper.loadLaunchableTvApps(this)
        val a = apps.firstOrNull { it.label.equals(name, true) } ?: apps.firstOrNull { it.label.lowercase().startsWith(name) }
            ?: apps.firstOrNull { it.label.lowercase().contains(name) }
        if (a?.launchIntent == null) { GlassUi.toast(this, "Couldn’t find an app called “$name”"); finish(); return }
        startActivity(a.launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finish()
    }

    private fun search(q: String) {
        startActivity(Intent(this, WatchActivity::class.java).putExtra(WatchActivity.EXTRA_TAB, "search").putExtra(WatchActivity.EXTRA_QUERY, q)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
