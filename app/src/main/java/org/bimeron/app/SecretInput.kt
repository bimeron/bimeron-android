package org.bimeron.app

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

/**
 * Keyboard requests for fields that can hold a secret (passwords, UUIDs, keys, links, raw config).
 * Every kind turns off autocorrect and capitalization; [SecretInput] adds the no-learning and no-suggestion
 * flags to the EditorInfo. Visual masking is up to the field and does not change here.
 *
 * Gboard honours IME_FLAG_NO_PERSONALIZED_LEARNING (incognito) but still shows a suggestion strip for plain
 * text and URI fields despite TYPE_TEXT_FLAG_NO_SUGGESTIONS; it hides the strip only for password variations.
 * Fields shown in clear therefore use TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ([visiblePassword]), which keeps
 * the text visible and multi-line editing working.
 */
internal enum class SecretKeyboard(val keyboardType: KeyboardType, val visiblePassword: Boolean) {
    /** A single-line value shown masked: the IME's password mode. */
    Masked(KeyboardType.Password, false),
    /** A single-line value shown in clear, for example a server name classed as secret. */
    Clear(KeyboardType.Text, true),
    /** A single-line number classed as secret (keeps the number pad). */
    Number(KeyboardType.Number, false),
    /** A key or link typed in clear. */
    Link(KeyboardType.Uri, true),
    /**
     * Multi-line text (raw config). KeyboardType.Password is single-line on some IMEs, so Compose gets plain
     * text (which keeps the multi-line flag and the newline key) and the EditorInfo becomes a visible password.
     */
    MultiLine(KeyboardType.Text, true);

    val options: KeyboardOptions
        get() = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = keyboardType)
}

/**
 * Adds the secret-field flags to [info] after Compose filled it in: IME_FLAG_NO_PERSONALIZED_LEARNING
 * (the IME must not learn or store what is typed) and, for text classes, TYPE_TEXT_FLAG_NO_SUGGESTIONS,
 * plus the visible-password variation for [keyboard] kinds shown in clear.
 */
internal fun secretEditorInfo(info: EditorInfo, keyboard: SecretKeyboard) {
    info.imeOptions = info.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    if (info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT) {
        var type = (info.inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) and
            InputType.TYPE_TEXT_FLAG_AUTO_CORRECT.inv() and InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE.inv()
        if (keyboard.visiblePassword) {
            type = (type and InputType.TYPE_MASK_VARIATION.inv()) or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        info.inputType = type
    }
}

// Compose 1.7 KeyboardOptions has no imeOptions flags (PlatformImeOptions only carries privateImeOptions),
// so the flags are added where the platform request creates its InputConnection.
@OptIn(ExperimentalComposeUiApi::class)
private class SecretTextInputInterceptor(private val keyboard: SecretKeyboard) : PlatformTextInputInterceptor {
    override suspend fun interceptStartInputMethod(
        request: PlatformTextInputMethodRequest,
        nextHandler: PlatformTextInputSession,
    ): Nothing = nextHandler.startInputMethod(PlatformTextInputMethodRequest { info ->
        request.createInputConnection(info).also { secretEditorInfo(info, keyboard) }
    })
}

@OptIn(ExperimentalComposeUiApi::class)
private val interceptors = SecretKeyboard.entries.associateWith { SecretTextInputInterceptor(it) }

/**
 * Hosts one secret text field: [content] receives the [keyboard] options and every text input session
 * started inside it gets the secret-field EditorInfo flags.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SecretInput(keyboard: SecretKeyboard, content: @Composable (KeyboardOptions) -> Unit) {
    InterceptPlatformTextInput(interceptors.getValue(keyboard)) { content(keyboard.options) }
}
