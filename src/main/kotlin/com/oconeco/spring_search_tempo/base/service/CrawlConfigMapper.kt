package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.domain.CrawlConfig
import com.oconeco.spring_search_tempo.base.model.CrawlConfigDTO
import org.mapstruct.AfterMapping
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingConstants
import org.mapstruct.MappingTarget
import org.mapstruct.ReportingPolicy

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.IGNORE
)
interface CrawlConfigMapper {

    @Mapping(target = "startPaths", ignore = true)
    fun updateCrawlConfigDTO(crawlConfig: CrawlConfig, @MappingTarget crawlConfigDTO: CrawlConfigDTO): CrawlConfigDTO

    @AfterMapping
    fun afterUpdateCrawlConfigDTO(crawlConfig: CrawlConfig, @MappingTarget crawlConfigDTO: CrawlConfigDTO) {
        crawlConfigDTO.startPaths = crawlConfig.startPaths?.toList()
    }

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "startPaths", ignore = true)
    @Mapping(target = "jobRuns", ignore = true)
    fun updateCrawlConfig(crawlConfigDTO: CrawlConfigDTO, @MappingTarget crawlConfig: CrawlConfig): CrawlConfig

    @AfterMapping
    fun afterUpdateCrawlConfig(crawlConfigDTO: CrawlConfigDTO, @MappingTarget crawlConfig: CrawlConfig) {
        crawlConfig.startPaths = crawlConfigDTO.startPaths?.toTypedArray()
    }

}
