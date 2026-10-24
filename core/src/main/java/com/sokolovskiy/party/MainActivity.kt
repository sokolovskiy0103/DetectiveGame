package com.sokolovskiy.party

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.sokolovskiy.party.tv.TvPresenter
import com.sokolovskiy.party.update.UpdateUi
import com.sokolovskiy.party.update.Updater

class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var updates: UpdateUi
    private var tv: TvPresenter? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.parseColor("#0E1320")
        window.navigationBarColor = Color.parseColor("#0E1320")

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0E1320"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
            }
        }
        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0E1320"))
            addView(web, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(root)

        // Android 15 draws apps edge-to-edge: keep the page clear of the status bar,
        // navigation bar and keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        updates = UpdateUi(this)
        web.addJavascriptInterface(AppBridge(), "PGApp")

        startForegroundService(Intent(this, PartyService::class.java))

        // Starting the server touches the network: do it off the UI thread.
        Thread {
            val hub = PartyHost.ensure(this)
            runOnUiThread {
                web.loadUrl("http://127.0.0.1:${hub.local.port}/host#${hub.hostToken}")
                // casting the screen (Miracast / Smart View) gives a second display: the TV gets its own page there
                if (!isDestroyed) tv = TvPresenter(this, "http://127.0.0.1:${hub.local.port}/?tv").also { it.start() }
            }
        }.start()

        updates.start()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // The page closes dialogs first, otherwise toggles its side menu (which has "Вийти"); only before
                // joining does it leave the screen (the game keeps running; stop it via "Вийти" or the notification).
                web.evaluateJavascript("(window.PG&&PG.back&&PG.back())===true") { handled ->
                    if (handled != "true") moveTaskToBack(true)
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        updates.resume()
    }

    /** `window.PGApp` on the host page: version info and the update button in the menu. */
    private inner class AppBridge {
        @JavascriptInterface fun version(): String = Updater.installedName(this@MainActivity)
        @JavascriptInterface fun updateAvailable(): String = updates.available?.name ?: ""
        @JavascriptInterface fun checkUpdate() { runOnUiThread { updates.check(manual = true) } }
        /** "📺 Транслювати" in the invite card: the system's cast picker; the TV then gets its own page (TvPresenter). */
        @JavascriptInterface fun castScreen(): Boolean = TvPresenter.openScreenCast(this@MainActivity)
        /** "Вийти" in the menu: same as the notification's "Завершити гру" — stops the server and the app. */
        @JavascriptInterface fun quit() {
            runOnUiThread {
                finishAndRemoveTask()
                startService(Intent(this@MainActivity, PartyService::class.java).setAction(PartyService.ACTION_STOP))
            }
        }
    }

    override fun onDestroy() {
        tv?.stop()
        updates.stop()
        web.destroy()
        super.onDestroy()
    }
}
