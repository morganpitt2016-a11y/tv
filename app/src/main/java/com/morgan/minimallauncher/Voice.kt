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
        // "Play the bit where Jim proposes to Pam": Claude works out the show, episode and moment, and it plays from there.
        if (Regex("\\b(bit|scene|part|moment|episode) where\\b|\\bwhen .+ (proposes|dies|says|finds|meets|kisses|leaves)").containsMatchIn(s)) { playTheBit(raw); return }
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

    /** "Play the bit where…": Claude names the episode and the minute; Dodgy Box plays it from just before then. */
    private fun playTheBit(said: String) {
        val app = applicationContext
        if (ClaudeChat.getApiKey(app) == null) { GlassUi.toast(this, "Add your Anthropic API key in Settings › Claude first"); search(said); return }
        GlassUi.toast(this, "Finding that moment…")
        Bg.run {
            val chat = ClaudeChat(app, "You know films and TV series in detail. You identify scenes people describe. Reply with JSON only.", quick = true)
            val ask = "Someone asked their TV: \"$said\". Which scene is it? Reply with JSON only: " +
                "{\"show\":\"series or film title\",\"film\":false,\"season\":1,\"episode\":1,\"minute\":12,\"second\":0,\"scene\":\"a few words describing the moment\"}. " +
                "For a film set film true and season/episode 0. Give your best estimate of when in the episode it happens."
            val text = (chat.send(ask) as? ClaudeChat.Reply.Text)?.text.orEmpty()
            val o = try { org.json.JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1)) } catch (e: Exception) { null }
            if (o == null) { runOnUiThread { GlassUi.toast(this, "Couldn't place that scene - try describing it another way") }; return@run }
            val show = o.optString("show"); val film = o.optBoolean("film"); val se = o.optInt("season"); val ep = o.optInt("episode")
            val at = (o.optInt("minute") * 60 + o.optInt("second") - 15).coerceAtLeast(0)
            val scene = o.optString("scene").ifBlank { "that moment" }
            if (film) {
                val v = try { Xtream.searchLibrary(app, show, 3).first.firstOrNull() } catch (e: Exception) { null }
                runOnUiThread {
                    if (v == null) { GlassUi.toast(this, "$show isn't on Dodgy Box"); return@runOnUiThread }
                    val url = Xtream.vodUrl(app, v)
                    Prefs.setInt(app, WatchHistory.posKey(app, url), at)
                    PlayerActivity.play(this, url, Xtream.clean(v.name), live = false, subtitle = scene.replaceFirstChar { it.uppercase() }, eyebrow = "THE BIT WHERE…", image = v.icon)
                    finish()
                }
                return@run
            }
            val found = try { Xtream.seriesEpisodes(app, show, 0) } catch (e: Exception) { null }
            val list = found?.second.orEmpty()
            val e = list.firstOrNull { it.season == se && it.number == ep }
            runOnUiThread {
                if (e == null) { GlassUi.toast(this, if (found == null) "$show isn't on Dodgy Box" else "$show S$se E$ep isn't on Dodgy Box"); return@runOnUiThread }
                val url = Xtream.episodeUrl(app, e)
                Prefs.setInt(app, WatchHistory.posKey(app, url), at)
                val rest = list.dropWhile { it.id != e.id }.drop(1).map { Triple(Xtream.episodeUrl(app, it), it.season, it.number) }
                PlayerActivity.play(this, url, show, live = false, subtitle = "S$se E$ep · ${scene.replaceFirstChar { it.uppercase() }}", eyebrow = "THE BIT WHERE…",
                    series = show, queue = rest, image = e.image ?: found?.first?.cover, season = se, episode = ep)
                finish()
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
