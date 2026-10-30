package com.morgan.minimallauncher

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

/**
 * Claude's little room: a cosy animated bedroom where Claude hangs out.
 * Press OK (or tap him) to start chatting. Back leaves the room.
 */
class ClaudeRoomActivity : AppCompatActivity() {

    private lateinit var room: ClaudeRoomView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF0B0A12.toInt()); isFocusable = true; isFocusableInTouchMode = true }
        room = ClaudeRoomView(this).apply {
            onSelect = { openChat() }
            setOnClickListener { openChat() }
        }
        root.addView(room, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        UiSounds.attach(this)
        room.setSelected(true)   // he notices you've come in, and waves
    }

    private fun openChat() {
        UiSounds.select(this)
        startActivity(Intent(this, ClaudeActivity::class.java))
        @Suppress("DEPRECATION") overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            room.poke(); return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
