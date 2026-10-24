package com.sokolovskiy.party.core

import android.content.Context

/**
 * Everything that makes one app out of the shared platform: its name, where it updates from and which games it hosts.
 * The app sets [PartyApp.config] once (in its `Application`, or in the desktop launcher) before the hub starts.
 */
class AppConfig(
    /** Shown in the page header, the notification and the update dialog. */
    val name: String,
    val emoji: String,
    /** The lobby heading above the list of games. */
    val pickerTitle: String,
    /** GitHub "owner/repo" whose latest release carries [apkName]. */
    val repo: String,
    val apkName: String,
    /** Builds the games of this app; [Hub.broadcast] is available through the hub for async changes. */
    val modules: (Context, Hub) -> List<GameModule>,
)

object PartyApp {
    lateinit var config: AppConfig
    val isSet get() = ::config.isInitialized
}
