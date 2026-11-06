package com.driverlab.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Finds a names file online: symbols-<build ID>.tsv.gz in the Banners-Turnip releases on GitHub. Only used
 * after the user allowed it (Settings → Find names files online). What is sent: the build ID in the file
 * name of the request, nothing else. A file found once is kept in the app, so it works offline after that.
 */
object NamesOnline {
    const val REPO = "The412Banner/Banners-Turnip"
    private val TAG = Regex("""v\d+\.\d+\.\d+-\d{8}(?:-r\d+)?""")

    /** Downloads the names file for [buildId] to [dest]. [hint] is the driver's name, which carries its release tag. */
    fun fetch(buildId: String, hint: String, dest: File): Boolean {
        val name = NamesFile.PREFIX + buildId + NamesFile.SUFFIX
        TAG.find(hint)?.value?.let { tag ->
            if (download("https://github.com/$REPO/releases/download/$tag/$name", dest)) return true
        }
        // Not in the release its name points to (renamed or repacked driver): the latest 100 releases.
        val list = JSONArray(get("https://api.github.com/repos/$REPO/releases?per_page=100"))
        for (i in 0 until list.length()) {
            val assets = list.getJSONObject(i).optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val a: JSONObject = assets.getJSONObject(j)
                if (a.optString("name") == name) return download(a.getString("browser_download_url"), dest)
            }
        }
        return false
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "DriverLab")
        setRequestProperty("Accept", "application/vnd.github+json")
    }

    private fun get(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode != 200) throw IOException("GitHub answered ${c.responseCode}" + if (c.responseCode == 403) " (too many requests, try again in an hour)" else "")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String, dest: File): Boolean {
        val c = open(url)
        try {
            if (c.responseCode == 404) return false
            if (c.responseCode != 200) throw IOException("GitHub answered ${c.responseCode}")
            dest.parentFile?.mkdirs()
            c.inputStream.use { i -> dest.outputStream().use { i.copyTo(it) } }
            return true
        } catch (e: FileNotFoundException) {
            return false
        } finally {
            c.disconnect()
        }
    }
}
