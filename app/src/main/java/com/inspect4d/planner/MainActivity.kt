package com.inspect4d.planner

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.WindowManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.IOException

/**
 * Hosts the Inspect4D planner (assets/index.html) in a WebView.
 * - Serves bundled files from https://appassets.androidplatform.net/assets/
 * - Caches satellite tiles on the controller so the map works offline on site
 * - Saves exported KMZ files to Download/Inspect4D for DJI Pilot 2 to import
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var tiles: TileCache
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        tiles = TileCache(File(filesDir, "tiles"), "Inspect4D-MissionPlanner/1.0 (Android)")
        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        setContentView(web)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // autosave of the current plan
            setGeolocationEnabled(true)       // "My location" button
            allowFileAccess = false
            allowContentAccess = false
        }
        web.addJavascriptInterface(Bridge(), "Android")

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assets.shouldInterceptRequest(request.url) ?: tiles.intercept(request)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == ASSET_HOST) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (_: Exception) {
                }
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                if (hasLocationPermission()) {
                    callback.invoke(origin, true, false)
                } else {
                    pendingGeoOrigin = origin
                    pendingGeoCallback = callback
                    requestPermissions(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        REQ_LOCATION
                    )
                }
            }
        }

        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
        }

        web.loadUrl("https://$ASSET_HOST/assets/index.html")
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            pendingGeoCallback?.invoke(pendingGeoOrigin, granted, false)
            pendingGeoCallback = null
            pendingGeoOrigin = null
        }
    }

    /** Called from JavaScript as window.Android.* */
    inner class Bridge {
        @JavascriptInterface
        fun saveKmz(name: String, base64: String): String = try {
            val path = saveToDownloads(cleanName(name), Base64.decode(base64, Base64.DEFAULT))
            runOnUiThread { Toast.makeText(this@MainActivity, "Saved to $path", Toast.LENGTH_LONG).show() }
            "ok:$path"
        } catch (e: Exception) {
            "error:${e.message ?: e.javaClass.simpleName}"
        }

        @JavascriptInterface
        fun cacheInfo(): String = tiles.describe()

        @JavascriptInterface
        fun clearCache() = tiles.clear()
    }

    private fun cleanName(name: String): String {
        val base = name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().replace(Regex("\\s+"), "_")
        return base.ifEmpty { "mission" }.take(60) + ".kmz"
    }

    private fun saveToDownloads(fileName: String, bytes: ByteArray): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.google-earth.kmz")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Storage is not available")
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IOException("Could not open the file for writing")
            // Android renames duplicates ("name (1).kmz"), so report the real name
            val actual = contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: fileName
            return "Download/$FOLDER/$actual"
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Could not create Download/$FOLDER")
        File(dir, fileName).writeBytes(bytes)
        return "Download/$FOLDER/$fileName"
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        AlertDialog.Builder(this)
            .setMessage("Close the planner? Your current plan is kept for next time.")
            .setPositiveButton("Close") { _, _ -> finish() }
            .setNegativeButton("Stay", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    companion object {
        const val ASSET_HOST = "appassets.androidplatform.net"
        const val FOLDER = "Inspect4D"
        const val REQ_LOCATION = 1
        const val REQ_STORAGE = 2
    }
}
