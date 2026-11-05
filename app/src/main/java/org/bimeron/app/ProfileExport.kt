package org.bimeron.app

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun writeProfileExport(text: String, open: () -> OutputStream?) {
    val stream = open() ?: throw java.io.IOException()
    stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
}

// Deliberately memory-only: secrets must never enter saved instance state.
class ProfileExport : ViewModel() {
    private var snapshot: String? = null
    var busy by mutableStateOf(false)
        private set
    var confirming by mutableStateOf(false)
        private set
    var message by mutableStateOf<Int?>(null)
        private set

    fun request(text: String) {
        if (busy) return
        snapshot = text
        busy = true
        confirming = true
        message = null
    }

    fun confirm(): Boolean {
        if (!confirming || snapshot == null) return false
        confirming = false
        return true
    }

    fun cancel() {
        snapshot = null
        confirming = false
        busy = false
    }

    fun dismissMessage() { message = null }

    fun fail() {
        cancel()
        message = R.string.export_failure
    }

    fun complete(uri: Uri?, resolver: ContentResolver) {
        val text = snapshot
        snapshot = null
        if (uri == null) { cancel(); return }
        if (text == null) { fail(); return }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    writeProfileExport(text) { resolver.openOutputStream(uri, "wt") }
                }
                message = R.string.export_success
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = R.string.export_failure
            } finally {
                busy = false
            }
        }
    }
}
