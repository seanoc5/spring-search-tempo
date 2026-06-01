package com.oconeco.spring_search_tempo.batch.historycrawl

import com.oconeco.spring_search_tempo.base.service.FirefoxPlacesService
import com.oconeco.spring_search_tempo.base.service.FirefoxPlacesService.FirefoxHistoryData
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemReader
import java.nio.file.Path


/**
 * ItemReader that pulls history entries from a Firefox places.sqlite database,
 * filtered by the previous-sync watermark and a retention cutoff.
 *
 * Entries are loaded into memory up front so the temp copy of places.sqlite
 * can be released before chunked writing begins.
 */
class HistoryImportReader(
    private val placesDbPath: Path,
    private val firefoxPlacesService: FirefoxPlacesService,
    private val sinceVisitPrTime: Long?,
    private val retentionDays: Int?
) : ItemReader<FirefoxHistoryData> {

    companion object {
        private val log = LoggerFactory.getLogger(HistoryImportReader::class.java)
    }

    private var entries: Iterator<FirefoxHistoryData>? = null
    private var totalCount = 0
    private var readCount = 0

    override fun read(): FirefoxHistoryData? {
        if (entries == null) {
            initialize()
        }

        val iterator = entries ?: return null

        return if (iterator.hasNext()) {
            readCount++
            if (readCount % 500 == 0) {
                log.info("Progress: read {} / {} history entries", readCount, totalCount)
            }
            iterator.next()
        } else {
            log.info("Finished reading all {} history entries", totalCount)
            null
        }
    }

    private fun initialize() {
        log.info(
            "Reading history from: {} (sincePrTime={}, retentionDays={})",
            placesDbPath, sinceVisitPrTime, retentionDays
        )
        val list = firefoxPlacesService.readHistory(placesDbPath, sinceVisitPrTime, retentionDays)
        totalCount = list.size
        entries = list.iterator()
        log.info("Loaded {} history entries from Firefox", totalCount)
    }

}
