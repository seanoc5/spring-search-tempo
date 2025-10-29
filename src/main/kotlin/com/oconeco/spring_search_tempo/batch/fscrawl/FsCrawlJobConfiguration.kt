package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.FSFolderService
import com.oconeco.spring_search_tempo.base.model.FSFolderDTO
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import com.oconeco.spring_search_tempo.base.service.FSFolderMapper
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.launch.support.RunIdIncrementer
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.item.ItemProcessor
import org.springframework.batch.item.ItemReader
import org.springframework.batch.item.ItemWriter
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Sample batch job to crawl a file system
 * learning kotlin
 * todo - convert logging to more kotlin specific
 */
@Configuration
class FsCrawlJobConfiguration(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val fsFolderRepository: FSFolderRepository,
    private val folderService: FSFolderService,
    private val folderMapper: FSFolderMapper
) {
    companion object {
        private val log = LoggerFactory.getLogger(FsCrawlJobConfiguration::class.java)
    }

    @Value("\${app.crawl.startPath}")
    lateinit var startPath: String

    @Value("\${app.crawl.maxDepth}")
    var maxDepth: Int = 10

    @Value("\${app.crawl.followLinks}")
    var followLinks: Boolean = false

    @Bean
    fun fsCrawlJob(): Job {
        log.info("Starting fsCrawlJob with startPath: $startPath")
        return JobBuilder("fsCrawlJob", jobRepository)
            .incrementer(RunIdIncrementer())
            .start(fsCrawlFoldersStep())
            .build()
    }

    @Bean
    fun fsCrawlFoldersStep(): Step {
        log.info("Starting fsCrawlFoldersStep with startPath: $startPath, maxDepth: $maxDepth, followLinks: $followLinks")
        return StepBuilder("fsCrawlFoldersStep", jobRepository)
            .chunk<Path, FSFolderDTO>(1000, transactionManager)
            .reader(fsCrawlReader())
            .processor(fsCrawlProcessor())
            .writer(fsCrawlWriter())
            .build()
    }

    @Bean
    fun fsCrawlReader(): ItemReader<Path> {
        log.info("Creating FolderReader with startPath: $startPath, maxDepth: $maxDepth, followLinks: $followLinks")
        return FolderReader(
            startPath = Path(startPath),
            maxDepth = maxDepth,
            followLinks = followLinks
        )
    }

    @Bean
    fun fsCrawlProcessor(): ItemProcessor<Path, FSFolderDTO> {
        log.info("Creating FolderProcessor with startPath: $startPath")
        return FolderProcessor(
            startPath = Path(startPath),
            folderRepository = fsFolderRepository,
            folderMapper = folderMapper
        )
    }

    @Bean
    fun fsCrawlWriter(): ItemWriter<FSFolderDTO> {
        log.info("Creating FolderWriter")
        return FolderWriter(folderService = folderService)
    }

//    @Bean
//    fun fsCrawlTasklet(): FsCrawlTasklet {
//        return FsCrawlTasklet(startPath, fsFolderRepository)
//    }

}
