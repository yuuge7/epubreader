package com.ebookreader.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Reading time by calendar day: the basis of the heat map, the streaks, the weekly and
 * monthly bars and the daily goal. A sitting counts on the day it began.
 */
class ReadingCalendar(sessions: List<ReadingSession>, private val zone: ZoneId = ZoneId.systemDefault()) {

    /** Seconds read on each day that has any reading. */
    val secondsByDay: Map<LocalDate, Long> = sessions
        .groupBy { Instant.ofEpochMilli(it.startedAt.time).atZone(zone).toLocalDate() }
        .mapValues { (_, day) -> day.sumOf { it.durationSeconds } }

    fun secondsOn(day: LocalDate): Long = secondsByDay[day] ?: 0L

    /** A day counts towards a streak once a minute of reading is on it. */
    private fun counts(day: LocalDate) = secondsOn(day) >= MIN_STREAK_SECONDS

    /**
     * Days in a row with reading, up to [today]. Today not read yet does not break the
     * streak: it only ends once a whole day goes by without a page.
     */
    fun currentStreak(today: LocalDate): Int {
        var day = if (counts(today)) today else today.minusDays(1)
        var run = 0
        while (counts(day)) {
            run++
            day = day.minusDays(1)
        }
        return run
    }

    fun longestStreak(): Int {
        val days = secondsByDay.filterValues { it >= MIN_STREAK_SECONDS }.keys.sorted()
        var best = 0
        var run = 0
        var previous: LocalDate? = null
        for (day in days) {
            run = if (previous != null && previous.plusDays(1) == day) run + 1 else 1
            best = maxOf(best, run)
            previous = day
        }
        return best
    }

    /** The [count] days ending with [today], oldest first. */
    fun lastDays(today: LocalDate, count: Int): List<Pair<LocalDate, Long>> =
        (count - 1 downTo 0).map { back -> today.minusDays(back.toLong()).let { it to secondsOn(it) } }

    /** The [count] months ending with the month of [today], oldest first. */
    fun lastMonths(today: LocalDate, count: Int): List<Pair<YearMonth, Long>> {
        val byMonth = secondsByDay.entries.groupBy({ YearMonth.from(it.key) }, { it.value })
            .mapValues { it.value.sum() }
        val now = YearMonth.from(today)
        return (count - 1 downTo 0).map { back -> now.minusMonths(back.toLong()).let { it to (byMonth[it] ?: 0L) } }
    }

    /** Days of [year] with any reading. */
    fun daysReadIn(year: Int): Int = secondsByDay.keys.count { it.year == year && counts(it) }

    fun secondsIn(year: Int): Long = secondsByDay.filterKeys { it.year == year }.values.sum()

    companion object {
        const val MIN_STREAK_SECONDS = 60L
    }
}

/**
 * The heat map's five steps: nothing, under a quarter hour, under half an hour, under an
 * hour, an hour or more. Fixed bounds rather than quantiles, so a level means the same
 * thing from one year to the next.
 */
fun heatLevel(seconds: Long): Int = when {
    seconds < ReadingCalendar.MIN_STREAK_SECONDS -> 0
    seconds < 15 * 60 -> 1
    seconds < 30 * 60 -> 2
    seconds < 60 * 60 -> 3
    else -> 4
}
