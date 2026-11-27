package com.inspect4d.planner

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Disk cache for map tiles. Every satellite or street tile the planner loads is stored,
 * and served from disk next time, so a site viewed (or saved) with internet works offline.
 */
class TileCache(private val dir: File, private val userAgent: String) {

    private val headers = mapOf(
        "Access-Control-Allow-Origin" to "*",   // lets the 3D view use tiles as textures
        "Cache-Control" to "max-age=604800"
    )

    private fun isTileHost(host: String) =
        host == "server.arcgisonline.com" || host.endsWith("tile.openstreetmap.org")

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val host = request.url.host ?: return null
        if (request.method != "GET" || !isTileHost(host)) return null

        val url = request.url.toString()
        val file = File(dir, key(url))
        if (file.isFile && file.length() > 0) return ok(file.readBytes())

        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", userAgent)
            try {
                val code = conn.responseCode
                if (code != 200) {
                    missing(if (code in 400..599) code else 502)
                } else {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    save(file, bytes)
                    ok(bytes)
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            missing(504)   // offline and not cached
        }
    }

    fun describe(): String {
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".tile") } ?: emptyList()
        if (files.isEmpty()) return "No map imagery saved on this controller yet."
        val mb = files.sumOf { it.length() } / (1024.0 * 1024.0)
        return String.format(Locale.US, "Saved maps: %,d tiles (%.0f MB).", files.size, mb)
    }

    fun clear() {
        dir.deleteRecursively()
    }

    private fun save(file: File, bytes: ByteArray) {
        try {
            dir.mkdirs()
            val tmp = File(dir, "${file.name}.${Thread.currentThread().id}.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (_: Exception) {
        }
    }

    private fun key(url: String): String {
        val md = MessageDigest.getInstance("MD5").digest(url.toByteArray())
        return md.joinToString("") { "%02x".format(it) } + ".tile"
    }

    private fun ok(bytes: ByteArray): WebResourceResponse {
        val png = bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()
        return WebResourceResponse(if (png) "image/png" else "image/jpeg", null, 200, "OK", headers, ByteArrayInputStream(bytes))
    }

    private fun missing(code: Int) =
        WebResourceResponse("text/plain", "utf-8", code, "Tile unavailable", headers, ByteArrayInputStream(ByteArray(0)))
}
