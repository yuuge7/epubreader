package com.ebookreader.domain.model

/** A stored sitting, reduced to what merging needs to decide. */
data class SittingSpan(
    val id: Long,
    val bookId: Long,
    val startedAt: Long,
    val endedAt: Long,
    val durationSeconds: Long
)

/**
 * What to do to the table: [extended] rows keep their id and take on a longer duration
 * and a later end, [absorbedIds] are folded into one of those and should be deleted.
 */
data class SittingMergePlan(
    val extended: List<SittingSpan>,
    val absorbedIds: List<Long>
)

/**
 * Works out how to fold a run of fragments back into whole sittings.
 *
 * Kept separate from the migration that runs it so the rule is testable without a
 * database, and so a future "repair history" action can reuse it.
 *
 * Total time is conserved exactly: every absorbed row's duration is added to the sitting
 * that swallows it, and nothing is dropped.
 *
 * [rows] may arrive in any order; they are grouped per book and walked oldest first.
 */
fun planSittingMerge(rows: List<SittingSpan>): SittingMergePlan {
    val extended = mutableListOf<SittingSpan>()
    val absorbedIds = mutableListOf<Long>()

    rows.groupBy { it.bookId }.forEach { (_, bookRows) ->
        var open: SittingSpan? = null
        var openGrew = false

        for (row in bookRows.sortedBy { it.startedAt }) {
            val current = open
            val continues = current != null &&
                ReadingSessionPolicy.continuesSitting(
                    lastStartedAt = current.startedAt,
                    lastEndedAt = current.endedAt,
                    now = row.startedAt
                )

            if (continues && current != null) {
                open = current.copy(
                    endedAt = maxOf(current.endedAt, row.endedAt),
                    durationSeconds = current.durationSeconds + row.durationSeconds
                )
                absorbedIds += row.id
                openGrew = true
            } else {
                if (openGrew && current != null) extended += current
                open = row
                openGrew = false
            }
        }
        val last = open
        if (openGrew && last != null) extended += last
    }

    return SittingMergePlan(extended, absorbedIds)
}
