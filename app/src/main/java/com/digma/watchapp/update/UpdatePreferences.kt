package com.digma.watchapp.update

import android.content.Context
import android.content.SharedPreferences

class UpdatePreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var autoCheckEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CHECK, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CHECK, value).apply()

    var lastCheckTime: Long
        get() = prefs.getLong(KEY_LAST_CHECK_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CHECK_TIME, value).apply()

    var ignoredVersion: String?
        get() = prefs.getString(KEY_IGNORED_VERSION, null)
        set(value) = prefs.edit().putString(KEY_IGNORED_VERSION, value).apply()

    var repositoryOwner: String
        get() = prefs.getString(KEY_REPO_OWNER, DEFAULT_OWNER) ?: DEFAULT_OWNER
        set(value) = prefs.edit().putString(KEY_REPO_OWNER, value).apply()

    var repositoryName: String
        get() = prefs.getString(KEY_REPO_NAME, DEFAULT_REPO) ?: DEFAULT_REPO
        set(value) = prefs.edit().putString(KEY_REPO_NAME, value).apply()

    companion object {
        private const val PREFS_NAME = "digma_update_prefs"
        private const val KEY_AUTO_CHECK = "auto_check_updates"
        private const val KEY_LAST_CHECK_TIME = "last_check_time"
        private const val KEY_IGNORED_VERSION = "ignored_version"
        private const val KEY_REPO_OWNER = "repo_owner"
        private const val KEY_REPO_NAME = "repo_name"

        const val DEFAULT_OWNER = "pastur253"
        const val DEFAULT_REPO = "DigmaWatchApp"
    }
}
