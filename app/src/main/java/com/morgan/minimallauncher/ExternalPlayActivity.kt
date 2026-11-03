package com.morgan.minimallauncher

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * Lets TiviMate's "Open in external player" hand a film or episode to this launcher. The stream link carries
 * the provider's server, username and password, so the first time it arrives the launcher tests them and keeps
 * them on this TV (they are never sent anywhere else). From then on, films and series open in the launcher's
 * own player straight away, with Continue / Start over, instead of going through TiviMate.
 */
class ExternalPlayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = intent?.data?.toString()
        if (link == null) { finish(); return }
        val app = applicationContext
        val title = intent.getStringExtra("title") ?: intent.getStringExtra("android.intent.extra.TITLE") ?: "Dodgy Box"

        if (!Xtream.isSetUp(app)) parse(link)?.let { (server, user, pass) ->
            thread(isDaemon = true) {
                val msg = try {
                    Xtream.save(app, server, user, pass)
                    "Dodgy Box sign-in saved: " + Xtream.check(app)
                } catch (e: Exception) {
                    Xtream.signOut(app)
                    "Couldn’t use that link to sign in: ${e.message}"
                }
                runOnUiThread { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() }
                Diag.send(app, "xtream", msg)          // the result only - never the details
            }
        }
        PlayerActivity.play(this, link, title, live = false, eyebrow = "DODGY BOX")
        finish()
    }

    private fun parse(link: String): Triple<String, String, String>? {
        val a = Regex("^(https?://[^/]+)/(?:movie|series|live)/([^/]+)/([^/]+)/[^/?]+").find(link)
        val b = Regex("^(https?://[^/]+)/([^/]+)/([^/]+)/\\d+(?:\\.\\w+)?(?:\\?.*)?$").find(link)
        val m = a ?: b ?: return null
        return Triple(m.groupValues[1], Uri.decode(m.groupValues[2]), Uri.decode(m.groupValues[3]))
    }
}
