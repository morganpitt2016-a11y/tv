package com.morgan.minimallauncher

import android.content.Context

/** What kind of box the launcher is running on, so heavy work can go gentler on small ones. */
object Device {
    @Volatile private var liteCache: Boolean? = null

    /** An Amazon Fire TV (stick, cube or TV). */
    fun isFireTv(c: Context) = c.packageManager.hasSystemFeature("amazon.hardware.fire_tv")

    /**
     * A small box: about 1 GB of memory (Fire TV Stick Lite, older sticks) or flagged low-memory by the system.
     * These get gentler TV Colour Lights from the start and do recording analysis only when nothing is playing.
     */
    fun lite(c: Context): Boolean = liteCache ?: run {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        (am.isLowRamDevice || mi.totalMem < 1_500_000_000L).also { liteCache = it }
    }
}
