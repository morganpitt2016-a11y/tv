package com.morgan.minimallauncher

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Tiny HTTP helper for the widgets and ambient photos (blocking; call off the main thread). */
object Net {
    const val USER_AGENT = "MinimalTVLauncher/1.0 (personal Android TV launcher; https://github.com/morganpitt2016-a11y/tv)"

    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 12_000): String {
        val conn = open(url, headers, timeoutMs)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    fun download(url: String, to: File, headers: Map<String, String> = emptyMap()): Boolean {
        val conn = open(url, headers, 20_000)
        return try {
            if (conn.responseCode !in 200..299) return false
            val tmp = File(to.path + ".part")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(to)
        } catch (e: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, headers: Map<String, String>, timeoutMs: Int): HttpURLConnection {
        var current = URL(url)
        // Follow redirects, including http -> https (HttpURLConnection won't by itself).
        repeat(5) {
            val conn = current.openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", USER_AGENT)
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: return conn
                conn.disconnect()
                current = URL(current, loc)
            } else {
                return conn
            }
        }
        throw java.io.IOException("Too many redirects")
    }
}
