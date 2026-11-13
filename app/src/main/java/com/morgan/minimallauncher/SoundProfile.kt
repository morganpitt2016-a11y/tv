package com.morgan.minimallauncher

import android.app.Activity
import android.content.Context
import android.media.audiofx.DynamicsProcessing

/**
 * Your sound profile, applied to everything the built-in player plays:
 *  - Sound Check (like Apple TV's calibration): a few "A or B?" listening tests on what's playing
 *    set the bass, clarity and treble for your room and your ears.
 *  - Dialogue Boost: lifts voices (1-4 kHz) and trims the boom underneath them.
 *  - Even Volume: evens out loud and quiet bits (adverts, explosions, whispering) - good at night.
 * Works on sound the TV decodes itself; Dolby/DTS sent straight to a soundbar can't be changed.
 */
object SoundProfile {
    data class Settings(val bass: Int, val clarity: Int, val treble: Int, val dialogue: Boolean, val level: Boolean) {
        val flat get() = bass == 0 && clarity == 0 && treble == 0 && !dialogue && !level
    }

    private fun k(c: Context, name: String) = ProfileManager.scope(c, "snd_$name")

    fun get(c: Context) = Settings(
        Prefs.int(c, k(c, "bass"), 0), Prefs.int(c, k(c, "clarity"), 0), Prefs.int(c, k(c, "treble"), 0),
        Prefs.bool(c, k(c, "dialogue"), false), Prefs.bool(c, k(c, "level"), false))

    fun save(c: Context, s: Settings) {
        Prefs.setInt(c, k(c, "bass"), s.bass); Prefs.setInt(c, k(c, "clarity"), s.clarity); Prefs.setInt(c, k(c, "treble"), s.treble)
        Prefs.setBool(c, k(c, "dialogue"), s.dialogue); Prefs.setBool(c, k(c, "level"), s.level)
        Prefs.setBool(c, k(c, "calibrated"), true)
    }

    fun calibrated(c: Context) = Prefs.bool(c, k(c, "calibrated"), false)

    /** The effect on one player's sound. Call [apply] whenever the settings change; [release] when done. */
    class Effect(private val session: Int) {
        private var dp: DynamicsProcessing? = null

        fun apply(s: Settings) {
            if (session == 0) return
            try {
                if (s.flat) { dp?.setEnabled(false); return }
                val e = dp ?: create().also { dp = it }
                // Five bands: bass / low-mid / voice / presence / air.
                val dialogueDb = if (s.dialogue) 5f else 0f
                val gains = floatArrayOf(
                    s.bass.toFloat() - (if (s.dialogue) 1.5f else 0f),
                    -(dialogueDb * 0.4f) - s.clarity * 0.3f,
                    dialogueDb + s.clarity.toFloat(),
                    dialogueDb * 0.4f + s.clarity * 0.5f + s.treble * 0.4f,
                    s.treble.toFloat())
                for (ch in 0 until CHANNELS) {
                    val eq = e.getPreEqByChannelIndex(ch)
                    for (i in CUTS.indices) eq.getBand(i).let { b -> b.isEnabled = true; b.gain = gains[i]; eq.setBand(i, b) }
                    eq.isEnabled = true
                    e.setPreEqByChannelIndex(ch, eq)
                    // Even Volume: gentle compression with make-up gain.
                    val mbc = e.getMbcByChannelIndex(ch)
                    mbc.isEnabled = s.level
                    mbc.getBand(0).let { b ->
                        b.isEnabled = s.level; b.cutoffFrequency = 20_000f
                        b.attackTime = 8f; b.releaseTime = 250f; b.ratio = 3.5f; b.threshold = -28f; b.kneeWidth = 6f
                        b.noiseGateThreshold = -90f; b.expanderRatio = 1f; b.preGain = 0f; b.postGain = if (s.level) 7f else 0f
                        mbc.setBand(0, b)
                    }
                    e.setMbcByChannelIndex(ch, mbc)
                    // Always: a limiter so boosts never clip.
                    e.setLimiterByChannelIndex(ch, DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1.5f, 0f))
                }
                e.setEnabled(true)
            } catch (ex: Throwable) { release() }
        }

        private fun create(): DynamicsProcessing {
            val cfg = DynamicsProcessing.Config.Builder(DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, CHANNELS,
                true, CUTS.size, true, 1, false, 0, true).setPreferredFrameDuration(10f).build()
            for (ch in 0 until CHANNELS) {
                val eq = cfg.getPreEqByChannelIndex(ch)
                for (i in CUTS.indices) eq.setBand(i, DynamicsProcessing.EqBand(true, CUTS[i], 0f))
                cfg.setPreEqByChannelIndex(ch, eq)
            }
            return DynamicsProcessing(0, session, cfg)
        }

        fun release() { try { dp?.release() } catch (e: Throwable) {}; dp = null }
    }

    private const val CHANNELS = 2
    private val CUTS = floatArrayOf(150f, 600f, 3_500f, 8_000f, 20_000f)

    // ---------- Sound Check: "A or B?" on whatever's playing ----------

    private class Step(val title: String, val hint: String, val a: (Settings) -> Settings, val b: (Settings) -> Settings)

    /**
     * Runs the listening tests. [preview] plays a setting live (without saving); [done] gets the result.
     * The person keeps watching while choosing, so it's their real speakers, room and ears being tuned.
     */
    fun soundCheck(a: Activity, preview: (Settings) -> Unit, done: (Settings) -> Unit) {
        var cur = Settings(0, 0, 0, false, false)
        val steps = listOf(
            Step("Bass", "Listen to the low sounds - music, footsteps, engines.", { it.copy(bass = 0) }, { it.copy(bass = 5) }),
            Step("Bass, closer", "Nearly there - which has the fuller sound without booming?", { it.copy(bass = (it.bass - 2).coerceAtLeast(-4)) }, { it.copy(bass = it.bass + 2) }),
            Step("Voices", "Listen to people talking. Which is easier to follow?", { it.copy(clarity = 0) }, { it.copy(clarity = 4) }),
            Step("Detail", "Listen to the crisp sounds - rustling, cymbals, the letter S.", { it.copy(treble = 0) }, { it.copy(treble = 4) }),
            Step("Loud and quiet", "B evens out loud and quiet moments. Which do you prefer?", { it.copy(level = false) }, { it.copy(level = true) }),
        )
        fun run(i: Int, showing: Char) {
            if (i >= steps.size) {
                preview(cur); done(cur)
                GlassDialog(a).title("Sound Check Done").message(describe(cur) + "\n\nIt’s saved for this profile and used for everything you play. Run it again any time from Playback → Sound.")
                    .button("Lovely", GlassDialog.Style.BOLD).show()
                return
            }
            val st = steps[i]
            val sa = st.a(cur); val sb = st.b(cur)
            preview(if (showing == 'B') sb else sa)
            GlassDialog(a).title("Sound Check ${i + 1} of ${steps.size} · ${st.title}")
                .message("${st.hint}\n\nNow playing: Sound $showing. Switch between them, then pick the one you like.")
                .button((if (showing == 'A') "▶ " else "") + "Listen to Sound A") { run(i, 'A') }
                .button((if (showing == 'B') "▶ " else "") + "Listen to Sound B") { run(i, 'B') }
                .button("A sounds better", GlassDialog.Style.BOLD) { cur = sa; run(i + 1, 'A') }
                .button("B sounds better", GlassDialog.Style.BOLD) { cur = sb; run(i + 1, 'A') }
                .button("Can’t tell") { cur = sa; run(i + 1, 'A') }
                .focusButton(if (showing == 'A') 0 else 1)
                .show()
        }
        GlassDialog(a).title("Sound Check").message("A few quick listening tests on what’s playing now - like an eye test for your ears and your room. " +
            "Keep the volume where you normally have it. Works best on something with talking and music.")
            .button("Start", GlassDialog.Style.BOLD) { run(0, 'A') }.button("Not Now").show()
    }

    fun describe(s: Settings): String {
        if (s.flat) return "Your sound is set to the original mix."
        val parts = mutableListOf<String>()
        if (s.bass != 0) parts += if (s.bass > 0) "a bit more bass" else "a bit less bass"
        if (s.clarity > 0) parts += "clearer voices"
        if (s.treble > 0) parts += "more detail"
        if (s.dialogue) parts += "Dialogue Boost"
        if (s.level) parts += "Even Volume"
        return "Your sound: " + parts.joinToString(", ") + "."
    }
}
