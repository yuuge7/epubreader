package com.ebookreader.presentation.common

/** Format seconds into a human-readable session time: "5m 23s" or "1h 2m" */
fun formatSessionTime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

/** Format accumulated reading time into a compact label: "2h 15m", "45m", "<1m" */
fun formatTotalReadingTime(seconds: Long): String {
    if (seconds <= 0L) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m"
        else -> "<1m"
    }
}
