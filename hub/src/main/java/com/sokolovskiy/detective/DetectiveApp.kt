package com.sokolovskiy.detective

import android.app.Application
import com.sokolovskiy.party.core.PartyApp

class DetectiveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PartyApp.config = detectiveConfig()
    }
}
