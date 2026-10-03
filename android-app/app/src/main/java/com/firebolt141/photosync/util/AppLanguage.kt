package com.firebolt141.ubertrag.util

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * The app's own language choice (English / 日本語 / follow the phone).
 *
 * AppCompat applies it to activities (and on Android 13+ to the whole app,
 * where it also shows under Settings → Apps → Übertrag → Language). Code that
 * builds text outside an activity — log lines, notifications, results —
 * gets its strings through [loc], which returns a context in that language.
 */
object AppLanguage {
    const val SYSTEM = ""
    const val ENGLISH = "en"
    const val JAPANESE = "ja"

    /** "en", "ja", or "" when following the phone's language. */
    fun current(): String {
        val l = AppCompatDelegate.getApplicationLocales()
        return if (l.isEmpty) SYSTEM else l[0]?.language.orEmpty()
    }

    /** Switches the language; open activities are recreated in it. */
    fun set(tag: String) {
        AppCompatDelegate.setApplicationLocales(
            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
        )
    }
}

/** This context, with resources in the app's chosen language. */
fun Context.loc(): Context {
    val chosen = AppCompatDelegate.getApplicationLocales()
    if (chosen.isEmpty) return this
    val cfg = Configuration(resources.configuration)
    cfg.setLocales(LocaleList.forLanguageTags(chosen.toLanguageTags()))
    return createConfigurationContext(cfg)
}
