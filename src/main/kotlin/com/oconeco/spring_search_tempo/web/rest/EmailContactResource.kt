package com.oconeco.spring_search_tempo.web.rest

import com.oconeco.spring_search_tempo.base.EmailContactService
import com.oconeco.spring_search_tempo.base.model.EmailContactDTO
import com.oconeco.spring_search_tempo.batch.contactgraph.EmailContactAggregationOrchestrator
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController


/**
 * Issue #146 Phase 1 REST API.
 *
 *  - `GET /api/email/contacts?account_id=X&sort=last_seen&limit=100` — paginated
 *    contact aggregates. The `sort` param uses the snake_case names from the
 *    issue spec; they map to entity property names below.
 *  - `POST /api/email/contacts/recompute?account_id=X` — dispatch the
 *    aggregation job for a specific account.
 */
@RestController
@RequestMapping("/api/email/contacts")
class EmailContactResource(
    private val emailContactService: EmailContactService,
    private val aggregationOrchestrator: EmailContactAggregationOrchestrator
) {

    companion object {
        private val log = LoggerFactory.getLogger(EmailContactResource::class.java)

        private const val DEFAULT_LIMIT = 100
        private const val MAX_LIMIT = 500

        private val SORT_ALIAS = mapOf(
            "last_seen" to "lastSeen",
            "first_seen" to "firstSeen",
            "sent_to_count" to "sentToCount",
            "received_from_count" to "receivedFromCount",
            "replied_to_count" to "repliedToCount",
            "replied_from_count" to "repliedFromCount",
            "threads_appeared_in" to "threadsAppearedIn",
            "normalized_address" to "normalizedAddress"
        )
    }

    @GetMapping
    fun list(
        @RequestParam(name = "account_id", required = false) accountId: Long?,
        @RequestParam(name = "sort", required = false, defaultValue = "last_seen") sort: String,
        @RequestParam(name = "direction", required = false, defaultValue = "DESC") direction: String,
        @RequestParam(name = "limit", required = false, defaultValue = "$DEFAULT_LIMIT") limit: Int,
        @RequestParam(name = "page", required = false, defaultValue = "0") page: Int
    ): ResponseEntity<EmailContactsResponse> {
        val sortField = SORT_ALIAS[sort] ?: sort
        val dir = runCatching { Sort.Direction.fromString(direction) }.getOrDefault(Sort.Direction.DESC)
        val size = limit.coerceIn(1, MAX_LIMIT)
        val pageable = PageRequest.of(page.coerceAtLeast(0), size, Sort.by(dir, sortField))

        val result = emailContactService.findContacts(accountId, pageable)
        return ResponseEntity.ok(
            EmailContactsResponse(
                content = result.content,
                page = result.number,
                size = result.size,
                totalElements = result.totalElements,
                totalPages = result.totalPages
            )
        )
    }

    @PostMapping("/recompute")
    fun recomputeAll(
        @RequestParam(name = "account_id", required = false) accountId: Long?
    ): ResponseEntity<Map<String, String>> {
        log.info("REST API request to recompute email contacts (accountId={})", accountId)
        val results = if (accountId != null) {
            val execution = aggregationOrchestrator.runForAccount(accountId)
            mapOf("accountId=$accountId" to "STARTED (executionId=${execution.id})")
        } else {
            aggregationOrchestrator.runForCurrentUser()
        }
        return ResponseEntity.ok(results)
    }

    @PostMapping("/recompute/{accountId}")
    fun recomputeAccount(@PathVariable accountId: Long): ResponseEntity<Map<String, String>> {
        log.info("REST API request to recompute email contacts for account {}", accountId)
        val execution = aggregationOrchestrator.runForAccount(accountId)
        return ResponseEntity.ok(
            mapOf("accountId=$accountId" to "STARTED (executionId=${execution.id})")
        )
    }
}

data class EmailContactsResponse(
    val content: List<EmailContactDTO>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int
)
