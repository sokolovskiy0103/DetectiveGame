package com.sokolovskiy.party.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Presentation
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * The TV page on a second screen. When the host casts the phone's screen to a TV (Miracast, Samsung Smart View,
 * "wireless display"), Android offers the TV as a presentation display: instead of mirroring the host controls it
 * gets its own window with [url] (`/?tv` on this phone's server). Sound of that page plays on the TV too.
 */
class TvPresenter(private val activity: Activity, private val url: String) : DisplayManager.DisplayListener {

    private val displays = activity.getSystemService(DisplayManager::class.java)
    private var screen: Screen? = null

    fun start() {
        displays.registerDisplayListener(this, Handler(Looper.getMainLooper()))
        update()
    }

    fun stop() {
        displays.unregisterDisplayListener(this)
        dismiss()
    }

    private fun update() {
        val d = displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()
        val cur = screen
        if (cur != null && cur.isShowing && cur.display.displayId == d?.displayId) return
        dismiss()
        if (d == null || activity.isFinishing) return
        val s = Screen(activity, d, url)
        try {
            s.show()
            screen = s
            Log.i(TAG, "TV on display ${d.displayId} (${d.name})")
        } catch (e: WindowManager.InvalidDisplayException) {
            Log.w(TAG, "display ${d.displayId} went away", e)
        }
    }

    private fun dismiss() {
        screen?.dismiss()
        screen = null
    }

    override fun onDisplayAdded(displayId: Int) = update()
    override fun onDisplayRemoved(displayId: Int) = update()
    override fun onDisplayChanged(displayId: Int) {}

    private class Screen(context: Context, display: Display, private val url: String) : Presentation(context, display) {
        private var web: WebView? = null

        @SuppressLint("SetJavaScriptEnabled")
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val w = WebView(context).apply {
                setBackgroundColor(Color.parseColor("#0E1320"))
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // nobody taps this screen: let its sounds play without a gesture
                settings.mediaPlaybackRequiresUserGesture = false
                webViewClient = WebViewClient()
            }
            web = w
            setContentView(w)
            w.loadUrl(url)
        }

        override fun onStop() {
            web?.destroy()
            web = null
            super.onStop()
        }
    }

    companion object {
        private const val TAG = "PartyTv"

        /**
         * Opens the system screen in which the host picks the TV to cast to. An app may not start casting itself
         * (that needs the system-only CONFIGURE_WIFI_DISPLAY), so this is as close as it gets: Samsung's Smart View,
         * then the stock cast list, then the "wireless display" page some vendors keep. False — none of them exists.
         */
        fun openScreenCast(activity: Activity): Boolean {
            val tries = listOfNotNull(
                activity.packageManager.getLaunchIntentForPackage("com.samsung.android.smartmirroring"),
                Intent(Settings.ACTION_CAST_SETTINGS),
                Intent("android.settings.WIFI_DISPLAY_SETTINGS"),
            )
            for (i in tries) {
                try {
                    activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return true
                } catch (_: ActivityNotFoundException) {
                } catch (e: SecurityException) {
                    Log.w(TAG, "cast screen refused: $i", e)
                }
            }
            return false
        }
    }
}
