package com.morgan.minimallauncher

import java.util.concurrent.ConcurrentHashMap

/**
 * How channel names and film titles are compared (TiviMate's names, the TV guide's, your
 * provider's). No Android in here, so it's covered by the unit tests. Patterns are compiled
 * once: the tuner compares hundreds of names a second.
 */
object TextMatch {
    private val leadingNumber = Regex("^\\s*\\d{1,4}[.)]?\\s+")
    private val noise = Regex("\\b(uk|gb|hd|fhd|uhd|sd|4k|hevc|h265|raw|vip|backup)\\b|[|:\\[\\]()_.\\-]")
    private val spaces = Regex("\\s+")

    /** "UK: BBC One HD" -> "bbc one"; "101 ITV1 FHD" -> "itv1". */
    fun normalise(s: String): String = s.lowercase()
        .replace(leadingNumber, "")
        .replace(noise, " ")
        .replace("bbc 1", "bbc one").replace("bbc1", "bbc one").replace("bbc 2", "bbc two").replace("bbc2", "bbc two")
        .replace("itv 1", "itv1").replace("channel 5", "5").replace("channel5", "5").replace("ch4", "channel 4")
        .replace(spaces, " ").trim()

    private val wholeWords = ConcurrentHashMap<String, Regex>()

    /** Same channel? Exact, or [wanted] as whole words inside a slightly longer name ("bbc one london"). */
    fun matches(candidate: String, wanted: String): Boolean {
        if (candidate.isEmpty() || wanted.isEmpty()) return false
        if (candidate == wanted) return true
        if (candidate.contains("+1") != wanted.contains("+1")) return false
        val re = wholeWords.getOrPut(wanted) { Regex("(^| )" + Regex.escape(wanted) + "( |$)") }
        return candidate.length <= wanted.length + 14 && re.containsMatchIn(candidate)
    }

    private val vodPrefix = Regex("^\\s*(\\[[^\\]]*\\]|\\|[^|]*\\||[a-z]{2,3}\\s*[-|])\\s*")
    private val bracketYear = Regex("[(\\[](19|20)\\d{2}[)\\]]")
    private val nonWord = Regex("[^a-z0-9 ]+")
    private val vodNoise = Regex("\\b(4k|uhd|fhd|hd|sd|hevc|multi|sub|subs|dub|dubbed|vod)\\b")

    /**
     * "EN - The Batman (2022) 4K" -> "the batman". Only a year in brackets is dropped, so
     * "2012" and "Blade Runner 2049" keep their numbers.
     */
    fun vodKey(s: String): String = s.lowercase()
        .replace(vodPrefix, "")
        .replace(bracketYear, " ")
        .replace("&", "and")
        .replace(nonWord, " ")
        .replace(vodNoise, " ")
        .replace(spaces, " ").trim()
}
