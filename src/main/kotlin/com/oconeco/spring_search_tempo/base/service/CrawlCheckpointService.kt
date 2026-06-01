package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.domain.CrawlCheckpoint
import com.oconeco.spring_search_tempo.base.repos.CrawlCheckpointRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Manages persistent crawl checkpoints used to resume crawls after JVM crashes
 * or interruptions. One checkpoint row per CrawlConfig; the URI itself encodes
 * which startPath the crawl was inside when the checkpoint was written.
 */
@Service
class CrawlCheckpointService(
    private val repository: CrawlCheckpointRepository
) {

    companion object {
        private val log = LoggerFactory.getLogger(CrawlCheckpointService::class.java)
    }

    fun find(crawlConfigId: Long): CrawlCheckpoint? =
        repository.findByCrawlConfigId(crawlConfigId)

    @Transactional
    fun upsert(crawlConfigId: Long, lastProcessedUri: String): CrawlCheckpoint {
        val existing = repository.findByCrawlConfigId(crawlConfigId)
        val checkpoint = existing ?: CrawlCheckpoint().apply {
            this.crawlConfigId = crawlConfigId
        }
        checkpoint.lastProcessedUri = lastProcessedUri
        return repository.save(checkpoint)
    }

    @Transactional
    fun clear(crawlConfigId: Long): Boolean {
        val deleted = repository.deleteByCrawlConfigId(crawlConfigId)
        if (deleted > 0) {
            log.info("Cleared crawl checkpoint for crawlConfigId={}", crawlConfigId)
        }
        return deleted > 0
    }
}
