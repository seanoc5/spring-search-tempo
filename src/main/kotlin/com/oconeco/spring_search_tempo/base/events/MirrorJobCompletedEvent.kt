package com.oconeco.spring_search_tempo.base.events

import java.time.OffsetDateTime

/**
 * Published by `MirrorJobLifecycleListener.afterJob()` after a mirror run
 * finishes with `BatchStatus.COMPLETED`. The listener side
 * (`MirrorIndexingTriggerListener`) consumes this to launch indexing
 * (email quick-sync + in-job NLP) against the destination account so the
 * just-mirrored messages stop being "dark" and become searchable.
 *
 * Carries enough payload for the listener to make the handoff without a
 * second DB round-trip: the mirror config id, the originating JobRun id,
 * the destination email account id, the completion timestamp, and the
 * count of messages mirrored in this run.
 */
data class MirrorJobCompletedEvent(
    val mirrorConfigId: Long,
    val jobRunId: Long?,
    val destAccountId: Long?,
    val completedAt: OffsetDateTime,
    val messagesMirrored: Long
)
