package com.oconeco.spring_search_tempo.base.model

import java.time.OffsetDateTime


/**
 * Per-account email-contact aggregate (issue #146).
 * Mirrors [com.oconeco.spring_search_tempo.base.domain.EmailContact] for the
 * REST API and admin UI list page.
 */
class EmailContactDTO {

    var id: Long? = null

    var emailAccountId: Long? = null

    var normalizedAddress: String? = null

    var displayNameLatest: String? = null

    var sentToCount: Long = 0

    var receivedFromCount: Long = 0

    var repliedToCount: Long = 0

    var repliedFromCount: Long = 0

    var threadsAppearedIn: Long = 0

    var firstSeen: OffsetDateTime? = null

    var lastSeen: OffsetDateTime? = null

    var lastRecomputedAt: OffsetDateTime? = null
}
