package com.ebookreader.data.library

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream

/**
 * Copies [input] into [dest], reporting progress as a fraction of [totalBytes] when that
 * is known. Progress is reported once per whole percent, not per buffer, so a screen
 * showing it redraws a hundred times at most. Stops if the caller is cancelled.
 */
internal suspend fun copyWithProgress(input: InputStream, dest: File, totalBytes: Long, onProgress: (Float) -> Unit) {
    dest.outputStream().use { output ->
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        var lastPercent = -1
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            copied += read
            if (totalBytes > 0) {
                val percent = (copied * 100 / totalBytes).toInt().coerceIn(0, 100)
                if (percent != lastPercent) {
                    lastPercent = percent
                    onProgress(percent / 100f)
                }
            }
        }
    }
}
