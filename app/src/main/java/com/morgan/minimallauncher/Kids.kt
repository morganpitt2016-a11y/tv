package com.morgan.minimallauncher

import android.app.Activity
import android.content.Context

/**
 * Kids profiles: only children's channels, films and series, and leaving the profile needs the PIN
 * (the same PIN as Master / Base).
 */
object Kids {
    private fun key(c: Context, profile: String = ProfileManager.getActiveProfile(c)) = "kids@" + Accounts.scoped(c, profile)

    fun isOn(c: Context) = Prefs.bool(c, key(c), false)

    fun isKidsProfile(c: Context, profile: String) = Prefs.bool(c, key(c, profile), false)

    fun set(c: Context, on: Boolean) = Prefs.setBool(c, key(c), on)

    /** Children's names for categories ("Kids", "Family", "Cartoons", "Disney"...). */
    private val KIDS = Regex("(?i)\\b(kid|kids|child|children|family|cartoon|cartoons|animat|animation|disney|pixar|junior|nick|nickelodeon|cbeebies|cbbc|boomerang|baby|toddler|dreamworks)")

    fun isKidsCategory(name: String) = KIDS.containsMatchIn(name)

    /** Switching away from a Kids profile needs the PIN; anything else goes straight through. */
    fun guardLeave(activity: Activity, to: String, then: () -> Unit) {
        val from = ProfileManager.getActiveProfile(activity)
        if (from == to || !isOn(activity) || isKidsProfile(activity, to)) { then(); return }
        val pin = Prefs.string(activity, "settings_pin")
        if (pin == null) { then(); return }
        val d = GlassDialog(activity).title("Enter PIN").message("$from is a Kids profile. Enter the PIN to switch to $to.").input("PIN", secret = true)
        d.button("Switch", GlassDialog.Style.BOLD) {
            if (d.inputText.filter { it.isDigit() } == pin) then() else GlassUi.toast(activity, "That PIN isn’t right")
        }
        d.button("Cancel").show()
    }
}
