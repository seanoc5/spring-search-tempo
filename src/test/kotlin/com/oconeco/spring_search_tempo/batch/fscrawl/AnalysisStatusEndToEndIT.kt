package com.oconeco.spring_search_tempo.batch.fscrawl

import com.fasterxml.jackson.databind.ObjectMapper
import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.config.CrawlDefinition
import com.oconeco.spring_search_tempo.base.config.EffectivePatterns
import com.oconeco.spring_search_tempo.base.config.PatternPriority
import com.oconeco.spring_search_tempo.base.config.PatternSet
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.FSFile
import com.oconeco.spring_search_tempo.base.repos.ContentChunkRepository
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import com.oconeco.spring_search_tempo.base.service.ContentChunkMapper
import com.oconeco.spring_search_tempo.base.service.CrawlConfigService
import com.oconeco.spring_search_tempo.base.service.DependencyParseResult
import com.oconeco.spring_search_tempo.base.service.NLPAnalysisResult
import com.oconeco.spring_search_tempo.base.service.NLPService
import com.oconeco.spring_search_tempo.base.service.NamedEntity
import com.oconeco.spring_search_tempo.base.service.POSTag
import com.oconeco.spring_search_tempo.base.service.SentimentResult
import com.oconeco.spring_search_tempo.batch.nlp.NLPChunkProcessor
import com.oconeco.spring_search_tempo.testfixtures.CrawlTreeFixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher
import org.springframework.batch.core.repository.JobRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively

/**
 * Issue #116 — end-to-end AnalysisStatus contract.
 *
 * Builds the canonical crawl fixture tree (issue #115) under a fixture
 * directory, drives `fsCrawlJob` against it with inline patterns covering
 * all four statuses, then exercises the NLP chunk-processing path against
 * the resulting chunks and verifies the side effects per AnalysisStatus:
 *
 *  - **SKIP** (`node_modules/`): one FSFolder row, no descendants persisted.
 *  - **LOCATE** (`metadata-only/`): FSFile rows with metadata, no bodyText,
 *    no ContentChunks.
 *  - **INDEX** (`docs/`): FSFile rows with bodyText + ContentChunks; chunks
 *    carry no NLP annotations.
 *  - **ANALYZE** (`analyzed/`): FSFile rows with bodyText + ContentChunks
 *    that, after NLP, have `namedEntities` and `sentiment` populated.
 *
 * The auto-NLP-after-crawl path is disabled here so the IT controls when
 * NLP runs — the auto-launched job is fired async on a separate executor
 * and would race the assertions.
 *
 * NOTE — NLP step bypass: this IT does not invoke `NLPJobLauncher` /
 * `nlpProcessingStep`. The full batch step pulls in `JobExecutionAdvisoryLockListener`
 * and the embedding-step (Ollama) wiring, neither of which this IT cares
 * to exercise; the contract under test is "ANALYZE chunks get NLP fields
 * populated," not the surrounding batch plumbing. We drive each ANALYZE
 * chunk through `NLPChunkProcessor` directly. The repo-level queries
 * (`findChunksForNlpProcessing`, `countByParentAnalysisStatus`,
 * `countNlpPendingAtAnalyzeLevel`, `findChunksForEmbedding`) are
 * covered by `ContentChunkNlpQueryIT` (issue #130).
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@TestPropertySource(properties = ["app.nlp.auto-trigger=false"])
@DirtiesContext
@DisplayName("AnalysisStatus end-to-end (issue #116)")
class AnalysisStatusEndToEndIT : BaseIT() {

    /**
     * Fixture root. Deliberately placed under the project's `build/` directory
     * rather than a JUnit `@TempDir` (which lands under `/tmp/junit-…`),
     * because the production default-crawl SKIP patterns include `/tmp/.*` —
     * which would match the whole fixture and short-circuit the IT.
     */
    private lateinit var tmp: Path

    @Autowired lateinit var jobBuilder: FsCrawlJobBuilder
    @Autowired lateinit var jobRepository: JobRepository

    // Stub getEffectivePatterns so the production defaults' SKIP patterns
    // (/tmp, build, .git, etc.) do not leak into the IT and short-circuit
    // the fixture root. The IT's CrawlDefinition carries an explicit,
    // fixture-tailored PatternSet -- we want to assert against exactly that
    // set, not against whatever the prod default crawl deems worth skipping.
    @MockitoSpyBean
    lateinit var crawlConfigService: CrawlConfigService

    @Autowired lateinit var fsFolderRepository: FSFolderRepository
    @Autowired lateinit var fsFileRepository: FSFileRepository
    @Autowired lateinit var contentChunkRepository: ContentChunkRepository

    // Mock NLPService rather than depending on the real Stanford CoreNLP
    // pipeline:
    //
    //   1. The default `stanford-corenlp:4.5.5:models` JAR ships
    //      `englishPCFG.ser.gz` (lex parser) but NOT
    //      `englishSR.ser.gz` (shift-reduce parser), and
    //      `StanfordNLPService` hardcodes
    //      `parse.model=…/srparser/englishSR.ser.gz`, so the pipeline
    //      throws RuntimeIOException on initialization in any
    //      environment that hasn't separately downloaded the SR model.
    //   2. Even when the pipeline starts, real CoreNLP runs add ~5–10 s
    //      to test startup, which puts us close to the 60 s ceiling
    //      from AC #e.
    //
    // The contract this IT is locking down is "ANALYZE chunks get
    // namedEntities + sentiment populated by the NLP step" — i.e. the
    // wiring, not Stanford's accuracy. A deterministic mock makes that
    // assertion sharp without coupling to model availability.
    @MockitoBean
    lateinit var nlpService: NLPService

    @Autowired lateinit var contentChunkMapper: ContentChunkMapper
    @Autowired lateinit var objectMapper: ObjectMapper

    // The container's `jobLauncher` is async-wrapped (BatchTaskExecutorConfig).
    // We need synchronous semantics so the assertions see the terminal state.
    private val syncLauncher: TaskExecutorJobLauncher by lazy {
        TaskExecutorJobLauncher().apply {
            setJobRepository(jobRepository)
            setTaskExecutor(SyncTaskExecutor())
            afterPropertiesSet()
        }
    }

    @AfterEach
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun cleanupFixture() {
        if (::tmp.isInitialized && Files.exists(tmp)) {
            tmp.deleteRecursively()
        }
    }

    @Test
    @DisplayName("crawl + nlp produce the expected per-status side effects on the fixture tree")
    fun crawlAndNlpProduceExpectedSideEffectsPerStatus() {
        val fixtureBase = Path.of("build", "tmp", "it-fixtures").toAbsolutePath()
        Files.createDirectories(fixtureBase)
        tmp = Files.createTempDirectory(fixtureBase, "analysis-status-it-")
        val root = CrawlTreeFixture.build(tmp)

        val folderPatterns = PatternSet(
            skip = listOf(".*/node_modules", ".*/node_modules/.*"),
            analyze = listOf(".*/analyzed", ".*/analyzed/.*"),
            index = listOf(".*/docs", ".*/docs/.*"),
            locate = listOf(".*/metadata-only", ".*/metadata-only/.*")
        )
        val filePatterns = PatternSet(
            analyze = listOf(".*/analyzed/[^/]+\\.(txt|md|adoc)"),
            index = listOf(".*/docs/[^/]+\\.(txt|md|adoc)"),
            locate = listOf(".*/metadata-only/[^/]+")
        )
        val crawl = CrawlDefinition(
            name = "ANALYSIS_STATUS_IT",
            label = "AnalysisStatus end-to-end IT",
            startPaths = listOf(root.toString()),
            maxDepth = 10,
            folderPatterns = folderPatterns,
            filePatterns = filePatterns
        )

        // Replace the merged (defaults + crawl) patterns with just the
        // fixture-scoped patterns — see field-level comment on
        // `crawlConfigService`.
        Mockito.doReturn(
            EffectivePatterns(
                folderPatterns = folderPatterns,
                filePatterns = filePatterns,
                folderPatternPriority = PatternPriority(),
                filePatternPriority = PatternPriority()
            )
        ).`when`(crawlConfigService).getEffectivePatterns(crawl)

        val crawlExec = syncLauncher.run(
            jobBuilder.buildJob(crawl, chunkProcessAll = true),
            JobParametersBuilder()
                .addLong("timestamp", System.currentTimeMillis())
                .addString("crawlName", crawl.name)
                .toJobParameters()
        )
        assertThat(crawlExec.status).isEqualTo(BatchStatus.COMPLETED)

        val nodeModulesUri = root.resolve("node_modules").toString()
        val nodeModulesPrefix = nodeModulesUri + "/"
        val metadataOnlyPrefix = root.resolve("metadata-only").toString() + "/"
        val docsPrefix = root.resolve("docs").toString() + "/"
        val analyzedPrefix = root.resolve("analyzed").toString() + "/"

        SoftAssertions().apply {
            // ── SKIP: node_modules/ persisted as SKIP, children not enumerated ──
            val nodeModulesFolder = fsFolderRepository.findByUri(nodeModulesUri)
            assertThat(nodeModulesFolder)
                .describedAs("node_modules/ folder row must exist")
                .isNotNull
            assertThat(nodeModulesFolder?.analysisStatus)
                .describedAs("node_modules/ must be marked SKIP")
                .isEqualTo(AnalysisStatus.SKIP)

            val foldersUnderNodeModules = fsFolderRepository.findAll()
                .filter { it.uri?.startsWith(nodeModulesPrefix) == true }
            assertThat(foldersUnderNodeModules)
                .describedAs("SKIP optimization: no FSFolder rows under node_modules/ should be persisted")
                .isEmpty()

            val filesUnderNodeModules = fsFileRepository.findAll()
                .filter { it.uri?.startsWith(nodeModulesPrefix) == true }
            assertThat(filesUnderNodeModules)
                .describedAs("SKIP optimization: no FSFile rows under node_modules/ should be persisted")
                .isEmpty()

            // ── LOCATE: metadata-only/* persisted with metadata, no body, no chunks ──
            val locateFiles = fsFileRepository.findAll()
                .filter { it.uri?.startsWith(metadataOnlyPrefix) == true }
            assertThat(locateFiles)
                .describedAs("expected 5 LOCATE files in metadata-only/")
                .hasSize(5)
            locateFiles.forEach { file ->
                assertThat(file.analysisStatus)
                    .describedAs("metadata-only file %s must be LOCATE", file.uri)
                    .isEqualTo(AnalysisStatus.LOCATE)
                assertThat(file.label)
                    .describedAs("LOCATE file %s must have a label (name)", file.uri)
                    .isNotBlank()
                assertThat(file.size)
                    .describedAs("LOCATE file %s must have a size", file.uri)
                    .isNotNull()
                assertThat(file.fsLastModified)
                    .describedAs("LOCATE file %s must have lastModified", file.uri)
                    .isNotNull()
                assertThat(file.bodyText)
                    .describedAs("LOCATE file %s must NOT have bodyText", file.uri)
                    .isNull()
                assertThat(chunkCountFor(file))
                    .describedAs("LOCATE file %s must have NO ContentChunks", file.uri)
                    .isZero()
            }

            // ── INDEX: docs/*.txt have bodyText + chunks, no NLP fields on chunks ──
            val indexFiles = fsFileRepository.findAll()
                .filter { it.uri?.startsWith(docsPrefix) == true && it.uri!!.endsWith(".txt") }
            assertThat(indexFiles)
                .describedAs("expected 3 INDEX files in docs/")
                .hasSize(3)
            indexFiles.forEach { file ->
                assertThat(file.analysisStatus)
                    .describedAs("docs file %s must be INDEX", file.uri)
                    .isEqualTo(AnalysisStatus.INDEX)
                assertThat(file.bodyText)
                    .describedAs("INDEX file %s must have bodyText", file.uri)
                    .isNotBlank()
                assertThat(file.contentType)
                    .describedAs("INDEX file %s must have a contentType (Tika)", file.uri)
                    .isNotNull()
                val chunks = contentChunkRepository.findByConceptIdOrderByChunkNumberAsc(file.id!!)
                assertThat(chunks)
                    .describedAs("INDEX file %s must have at least one ContentChunk", file.uri)
                    .isNotEmpty
                chunks.forEach { c ->
                    assertThat(c.namedEntities)
                        .describedAs("INDEX chunk %d must NOT have NLP namedEntities", c.id)
                        .isNull()
                    assertThat(c.sentiment)
                        .describedAs("INDEX chunk %d must NOT have NLP sentiment", c.id)
                        .isNull()
                    assertThat(c.nlpProcessedAt)
                        .describedAs("INDEX chunk %d must NOT be NLP-processed", c.id)
                        .isNull()
                }
            }

            assertAll()
        }

        // ── ANALYZE preconditions (pre-NLP): bodyText + chunks but no NLP fields ──
        val analyzeFiles = fsFileRepository.findAll()
            .filter { it.uri?.startsWith(analyzedPrefix) == true && it.uri!!.endsWith(".txt") }
        assertThat(analyzeFiles)
            .describedAs("expected 2 ANALYZE files in analyzed/")
            .hasSize(2)
        analyzeFiles.forEach { file ->
            assertThat(file.analysisStatus)
                .describedAs("analyzed file %s must be ANALYZE", file.uri)
                .isEqualTo(AnalysisStatus.ANALYZE)
            assertThat(file.bodyText)
                .describedAs("ANALYZE file %s must have bodyText", file.uri)
                .isNotBlank()
            val chunks = contentChunkRepository.findByConceptIdOrderByChunkNumberAsc(file.id!!)
            assertThat(chunks)
                .describedAs("ANALYZE file %s must have ContentChunks", file.uri)
                .isNotEmpty
        }

        // ── Drive NLP over the ANALYZE chunks (see class-level NOTE on why
        //    we bypass NLPJobLauncher / nlpProcessingStep) ──
        runNlpOverAnalyzeChunks(analyzeFiles)

        // ── ANALYZE post-NLP: namedEntities + sentiment populated on chunks ──
        analyzeFiles.forEach { file ->
            val chunks = contentChunkRepository.findByConceptIdOrderByChunkNumberAsc(file.id!!)
            assertThat(chunks)
                .describedAs("ANALYZE file %s must still have ContentChunks after NLP", file.uri)
                .isNotEmpty
            assertThat(chunks)
                .describedAs("ANALYZE file %s: every chunk must be NLP-processed", file.uri)
                .allMatch { it.nlpProcessedAt != null }
            // Sentiment is populated for every NLP-processed chunk (NEUTRAL
            // falls out for content-free chunks; the assertion is that the
            // column was touched, not the specific polarity).
            assertThat(chunks)
                .describedAs("ANALYZE file %s: every chunk must have sentiment set", file.uri)
                .allMatch { !it.sentiment.isNullOrBlank() }
            // Named entities: the fixture text was authored to contain proper
            // nouns (Acme, Stanford, Goldman Sachs / Apple, Sony, Amazon), so
            // at least one chunk per file must surface entities. We require
            // the JSON column to be non-null and contain a `text` token —
            // this catches the empty-array regression without pinning
            // specific NER tags or surface forms (which can shift across
            // CoreNLP versions).
            assertThat(chunks)
                .describedAs("ANALYZE file %s: at least one chunk must have namedEntities populated", file.uri)
                .anyMatch { !it.namedEntities.isNullOrBlank() && it.namedEntities!!.contains("\"text\"") }
        }
    }

    private fun chunkCountFor(file: FSFile): Int =
        contentChunkRepository.findByConceptIdOrderByChunkNumberAsc(file.id!!).size

    /**
     * Run the production `NLPChunkProcessor` over every chunk attached to
     * the given ANALYZE files, then persist the NLP fields back to the
     * `content_chunks` table.
     *
     * This mirrors what the `nlpProcessingStep` would do — same processor,
     * same fields, same JSON shapes — minus the broken `RepositoryItemReader`
     * (see class-level NOTE).
     */
    private fun runNlpOverAnalyzeChunks(analyzeFiles: List<FSFile>) {
        // Canned NLP result: enough structure that NLPChunkProcessor
        // populates every observable field (namedEntities, nouns, verbs,
        // sentiment, parse trees).
        Mockito.`when`(nlpService.analyze(Mockito.anyString())).thenAnswer { invocation ->
            val text = invocation.getArgument<String>(0)
            NLPAnalysisResult(
                text = text,
                namedEntities = listOf(
                    NamedEntity(text = "Acme", type = "ORGANIZATION", startOffset = 0, endOffset = 4)
                ),
                posTag = listOf(
                    POSTag(token = "company", tag = "NN", lemma = "company"),
                    POSTag(token = "announced", tag = "VBD", lemma = "announce")
                ),
                sentiment = SentimentResult(sentiment = "POSITIVE", score = 0.92),
                sentences = listOf(text),
                dependencyParses = listOf(
                    DependencyParseResult(
                        sentenceIndex = 0,
                        constituencyTree = "(ROOT)",
                        universalDependencies = "",
                        conllu = ""
                    )
                )
            )
        }

        val processor = NLPChunkProcessor(nlpService, objectMapper, contentChunkMapper)
        analyzeFiles.forEach { file ->
            val chunks = contentChunkRepository.findByConceptIdOrderByChunkNumberAsc(file.id!!)
            chunks.forEach { chunk ->
                val dto = contentChunkMapper.updateContentChunkDTO(
                    chunk,
                    com.oconeco.spring_search_tempo.base.model.ContentChunkDTO()
                )
                val processed = processor.process(dto)
                    ?: error("NLPChunkProcessor returned null for chunk ${chunk.id} (text len=${chunk.text?.length})")
                chunk.namedEntities = processed.namedEntities
                chunk.tokenAnnotations = processed.tokenAnnotations
                chunk.nouns = processed.nouns
                chunk.verbs = processed.verbs
                chunk.sentiment = processed.sentiment
                chunk.sentimentScore = processed.sentimentScore
                chunk.parseTree = processed.parseTree
                chunk.parseUd = processed.parseUd
                chunk.conllu = processed.conllu
                chunk.nlpProcessedAt = processed.nlpProcessedAt
                contentChunkRepository.save(chunk)
            }
        }
    }
}
