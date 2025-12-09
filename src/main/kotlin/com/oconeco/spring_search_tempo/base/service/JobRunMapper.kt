package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.domain.JobRun
import com.oconeco.spring_search_tempo.base.model.JobRunDTO
import com.oconeco.spring_search_tempo.base.repos.CrawlConfigRepository
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import org.mapstruct.AfterMapping
import org.mapstruct.Context
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingConstants
import org.mapstruct.MappingTarget
import org.mapstruct.ReportingPolicy

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.IGNORE
)
interface JobRunMapper {

    @Mapping(target = "crawlConfig", ignore = true)
    fun updateJobRunDTO(jobRun: JobRun, @MappingTarget jobRunDTO: JobRunDTO): JobRunDTO

    @AfterMapping
    fun afterUpdateJobRunDTO(jobRun: JobRun, @MappingTarget jobRunDTO: JobRunDTO) {
        jobRunDTO.crawlConfig = jobRun.crawlConfig?.id
    }

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "crawlConfig", ignore = true)
    fun updateJobRun(
        jobRunDTO: JobRunDTO,
        @MappingTarget jobRun: JobRun,
        @Context crawlConfigRepository: CrawlConfigRepository
    ): JobRun

    @AfterMapping
    fun afterUpdateJobRun(
        jobRunDTO: JobRunDTO,
        @MappingTarget jobRun: JobRun,
        @Context crawlConfigRepository: CrawlConfigRepository
    ) {
        val crawlConfig = if (jobRunDTO.crawlConfig == null) null else
            crawlConfigRepository.findById(jobRunDTO.crawlConfig!!)
                .orElseThrow { NotFoundException("crawlConfig not found") }
        jobRun.crawlConfig = crawlConfig
    }

}
