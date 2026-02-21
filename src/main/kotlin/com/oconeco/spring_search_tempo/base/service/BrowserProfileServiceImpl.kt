package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.BrowserProfileService
import com.oconeco.spring_search_tempo.base.domain.BrowserProfile
import com.oconeco.spring_search_tempo.base.model.BrowserProfileDTO
import com.oconeco.spring_search_tempo.base.repos.BrowserProfileRepository
import com.oconeco.spring_search_tempo.base.util.CustomCollectors
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import java.time.OffsetDateTime


@Service
class BrowserProfileServiceImpl(
    private val browserProfileRepository: BrowserProfileRepository,
    private val browserProfileMapper: BrowserProfileMapper
) : BrowserProfileService {

    override fun count(): Long = browserProfileRepository.count()

    override fun findAll(): List<BrowserProfileDTO> {
        val profiles = browserProfileRepository.findAll(Sort.by("id"))
        return profiles.map { profile ->
            browserProfileMapper.updateBrowserProfileDTO(profile, BrowserProfileDTO())
        }
    }

    override fun findEnabled(): List<BrowserProfileDTO> {
        val profiles = browserProfileRepository.findByEnabledTrue()
        return profiles.map { profile ->
            browserProfileMapper.updateBrowserProfileDTO(profile, BrowserProfileDTO())
        }
    }

    override fun get(id: Long): BrowserProfileDTO = browserProfileRepository.findById(id)
        .map { profile -> browserProfileMapper.updateBrowserProfileDTO(profile, BrowserProfileDTO()) }
        .orElseThrow { NotFoundException() }

    override fun create(browserProfileDTO: BrowserProfileDTO): Long {
        val browserProfile = BrowserProfile()
        browserProfileMapper.updateBrowserProfile(browserProfileDTO, browserProfile)
        return browserProfileRepository.save(browserProfile).id!!
    }

    override fun update(id: Long, browserProfileDTO: BrowserProfileDTO) {
        val browserProfile = browserProfileRepository.findById(id)
            .orElseThrow { NotFoundException() }
        browserProfileMapper.updateBrowserProfile(browserProfileDTO, browserProfile)
        browserProfileRepository.save(browserProfile)
    }

    override fun delete(id: Long) {
        val browserProfile = browserProfileRepository.findById(id)
            .orElseThrow { NotFoundException() }
        browserProfileRepository.delete(browserProfile)
    }

    override fun profilePathExists(profilePath: String): Boolean =
        browserProfileRepository.existsByProfilePath(profilePath)

    override fun findByProfilePath(profilePath: String): BrowserProfileDTO? {
        return browserProfileRepository.findByProfilePath(profilePath)?.let { profile ->
            browserProfileMapper.updateBrowserProfileDTO(profile, BrowserProfileDTO())
        }
    }

    override fun getBrowserProfileValues(): Map<Long, Long> =
        browserProfileRepository.findAll(Sort.by("id"))
            .stream()
            .collect(CustomCollectors.toSortedMap(BrowserProfile::id, BrowserProfile::id))

    override fun updateSyncState(id: Long, bookmarkCount: Int) {
        val browserProfile = browserProfileRepository.findById(id)
            .orElseThrow { NotFoundException() }

        browserProfile.lastSyncAt = OffsetDateTime.now()
        browserProfile.lastSyncBookmarkCount = bookmarkCount

        browserProfileRepository.save(browserProfile)
    }

    override fun recordError(id: Long, error: String) {
        val browserProfile = browserProfileRepository.findById(id)
            .orElseThrow { NotFoundException() }

        browserProfile.lastError = error.take(2000)
        browserProfile.lastErrorAt = OffsetDateTime.now()

        browserProfileRepository.save(browserProfile)
    }

    override fun clearError(id: Long) {
        val browserProfile = browserProfileRepository.findById(id)
            .orElseThrow { NotFoundException() }

        browserProfile.lastError = null
        browserProfile.lastErrorAt = null

        browserProfileRepository.save(browserProfile)
    }

}
