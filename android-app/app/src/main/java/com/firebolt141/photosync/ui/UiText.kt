package com.firebolt141.ubertrag.ui

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * A message chosen in a ViewModel but worded only when it is shown, so it
 * follows a language switch (ViewModels outlive the screen being recreated).
 */
sealed class UiText {
    data object None : UiText()
    data class Raw(val text: String) : UiText()
    class Res(@StringRes val id: Int, vararg val args: Any) : UiText()
    class Plural(@PluralsRes val id: Int, val count: Int, vararg val args: Any) : UiText()

    val isEmpty: Boolean get() = this is None || (this is Raw && text.isBlank())

    fun resolve(context: Context): String = when (this) {
        None -> ""
        is Raw -> text
        is Res -> context.getString(id, *args)
        is Plural -> context.resources.getQuantityString(id, count, *args)
    }

    @Composable
    fun text(): String = resolve(LocalContext.current)
}
