package com.oconeco.spring_search_tempo.base

import com.oconeco.spring_search_tempo.base.model.EmailContactDTO
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable


/**
 * Email contact-graph Phase 1 (issue #146) — per-account aggregate counters
 * over [com.oconeco.spring_search_tempo.base.domain.EmailMessage] rows.
 */
interface EmailContactService {

    /**
     * Paginated read for the REST endpoint and the admin list page.
     * Filters by account when [accountId] is non-null; otherwise returns
     * contacts across all accounts (admin-only callers).
     */
    fun findContacts(accountId: Long?, pageable: Pageable): Page<EmailContactDTO>

    /**
     * Re-aggregate every [com.oconeco.spring_search_tempo.base.domain.EmailMessage]
     * row owned by [accountId] into [com.oconeco.spring_search_tempo.base.domain.EmailContact]
     * rows. Idempotent — counters are computed from a single pass over the
     * messages, then `upsert`-ed onto the contact rows, so a second invocation
     * with no new messages yields the same numbers.
     *
     * Address normalization rules and the reply-classification heuristic live
     * in the implementation; see [com.oconeco.spring_search_tempo.base.util.EmailAddressNormalizer]
     * and the impl-class KDoc.
     *
     * @return the number of contact rows touched.
     */
    fun recomputeForAccount(accountId: Long): Int
}
