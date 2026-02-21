package com.oconeco.spring_search_tempo.batch.bookmarkcrawl

import com.oconeco.spring_search_tempo.base.service.FirefoxPlacesService
import com.oconeco.spring_search_tempo.base.service.FirefoxPlacesService.FirefoxBookmarkData
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemReader
import java.nio.file.Path


/**
 * ItemReader that reads bookmarks from Firefox places.sqlite.
 *
 * Loads all bookmarks into memory on initialization (Firefox bookmarks
 * are typically < 10K items). This allows the database copy to be
 * released quickly.
 */
class BookmarkImportReader(
    private val placesDbPath: Path,
    private val firefoxPlacesService: FirefoxPlacesService
) : ItemReader<FirefoxBookmarkData> {

    companion object {
        private val log = LoggerFactory.getLogger(BookmarkImportReader::class.java)
    }

    private var bookmarks: Iterator<FirefoxBookmarkData>? = null
    private var totalCount = 0
    private var readCount = 0

    override fun read(): FirefoxBookmarkData? {
        if (bookmarks == null) {
            initialize()
        }

        val iterator = bookmarks ?: return null

        return if (iterator.hasNext()) {
            readCount++
            if (readCount % 500 == 0) {
                log.info("Progress: read {} / {} bookmarks", readCount, totalCount)
            }
            iterator.next()
        } else {
            log.info("Finished reading all {} bookmarks", totalCount)
            null
        }
    }

    private fun initialize() {
        log.info("Reading bookmarks from: {}", placesDbPath)

        val bookmarkList = firefoxPlacesService.readBookmarks(placesDbPath)
        totalCount = bookmarkList.size
        bookmarks = bookmarkList.iterator()

        log.info("Loaded {} bookmarks from Firefox", totalCount)
    }

}
