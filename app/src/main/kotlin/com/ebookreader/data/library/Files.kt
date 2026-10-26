package com.ebookreader.data.library

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Copies [input] into [dest], reporting progress as a fraction of [totalBytes] when that
 * is known. Progress is reported once per whole percent, not per buffer, so a screen
 * showing it redraws a hundred times at most. Stops if the caller is cancelled.
 */
internal suspend fun copyWithProgress(
    input: InputStream,
    dest: File,
    totalBytes: Long,
    onProgress: (Float) -> Unit,
    /** Fed every byte copied, to know the file by its content. */
    digest: MessageDigest? = null,
) {
    dest.outputStream().use { output ->
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        var lastPercent = -1
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            digest?.update(buffer, 0, read)
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

/** SHA-256 as lower-case hex. */
internal fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }

/** The SHA-256 of a file already on the device, for books imported before hashes were kept. */
internal fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.hex()
}
