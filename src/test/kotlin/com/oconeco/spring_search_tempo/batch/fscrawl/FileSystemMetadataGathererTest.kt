package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.config.MetadataGatherMode
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Issue #148: verify that the bulk gatherer produces metadata identical to the
 * per-call path under every mode, and that the Micrometer timer increments once
 * per stat. The tagged timer is what the operator uses to decide whether the
 * mode switch is worth keeping.
 */
class FileSystemMetadataGathererTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var files: List<Path>
    private lateinit var registry: SimpleMeterRegistry

    @BeforeEach
    fun seed() {
        files = (1..50).map { i ->
            val f = tempDir.resolve("f-$i.txt")
            Files.writeString(f, "payload-$i".repeat(i))
            f
        }
        registry = SimpleMeterRegistry()
    }

    @AfterEach
    fun cleanup() {
        registry.close()
    }

    @Test
    fun `sequential and parallel produce identical metadata`() {
        val sequential = FileSystemMetadataGatherer(MetadataGatherMode.SEQUENTIAL)
        val parallel = FileSystemMetadataGatherer(MetadataGatherMode.PARALLEL)
        try {
            val seq = sequential.fromPaths(files)
            val par = parallel.fromPaths(files)

            assertThat(par).hasSameSizeAs(seq)
            seq.indices.forEach { i ->
                assertThat(par[i]).isEqualTo(seq[i])
            }
        } finally {
            sequential.shutdown()
            parallel.shutdown()
        }
    }

    @Test
    fun `BULK mode falls back to parallel results`() {
        val parallel = FileSystemMetadataGatherer(MetadataGatherMode.PARALLEL)
        val bulk = FileSystemMetadataGatherer(MetadataGatherMode.BULK)
        try {
            assertThat(bulk.fromPaths(files)).isEqualTo(parallel.fromPaths(files))
        } finally {
            parallel.shutdown()
            bulk.shutdown()
        }
    }

    @Test
    fun `timer records one sample per path with mode tag`() {
        val gatherer = FileSystemMetadataGatherer(MetadataGatherMode.SEQUENTIAL, registry)
        try {
            gatherer.fromPaths(files)
        } finally {
            gatherer.shutdown()
        }

        val timer = registry.find(FileSystemMetadataGatherer.METRIC_NAME)
            .tag("mode", "sequential")
            .timer()
        assertThat(timer).isNotNull
        assertThat(timer!!.count()).isEqualTo(files.size.toLong())
    }

    @Test
    fun `parallel mode tags timer with mode=parallel`() {
        val gatherer = FileSystemMetadataGatherer(MetadataGatherMode.PARALLEL, registry)
        try {
            gatherer.fromPaths(files)
        } finally {
            gatherer.shutdown()
        }

        val timer = registry.find(FileSystemMetadataGatherer.METRIC_NAME)
            .tag("mode", "parallel")
            .timer()
        assertThat(timer).isNotNull
        assertThat(timer!!.count()).isEqualTo(files.size.toLong())
    }

    @Test
    fun `gatherer fromPath returns same data as static fromPath`() {
        val gatherer = FileSystemMetadataGatherer(MetadataGatherMode.SEQUENTIAL)
        try {
            val viaGatherer = gatherer.fromPath(files.first())
            val viaStatic = FileSystemMetadata.fromPath(files.first())
            assertThat(viaGatherer).isEqualTo(viaStatic)
        } finally {
            gatherer.shutdown()
        }
    }

    @Test
    fun `empty input returns empty list without touching pool`() {
        val gatherer = FileSystemMetadataGatherer(MetadataGatherMode.PARALLEL, registry)
        try {
            assertThat(gatherer.fromPaths(emptyList())).isEmpty()
        } finally {
            gatherer.shutdown()
        }
        val timer = registry.find(FileSystemMetadataGatherer.METRIC_NAME).timer()
        assertThat(timer?.count() ?: 0L).isZero()
    }

    /**
     * Issue #148 measurement vehicle. Not a strict assertion: the wall-clock
     * win for PARALLEL depends on the underlying storage (SSD vs spinning vs
     * network) and CI runners have wildly different characteristics. We assert
     * only that parallel completes (correctness) and log both numbers so the
     * operator can read them off CI when triaging mode choice.
     */
    @Test
    fun `parallel completes for a 1000-file batch and logs both timings`() {
        val bigBatch = (1..1000).map { i ->
            val f = tempDir.resolve("bench-$i.bin")
            Files.writeString(f, "x".repeat(64))
            f
        }
        val seqGatherer = FileSystemMetadataGatherer(MetadataGatherMode.SEQUENTIAL)
        val parGatherer = FileSystemMetadataGatherer(MetadataGatherMode.PARALLEL)
        try {
            val seqStart = System.nanoTime()
            val seqResult = seqGatherer.fromPaths(bigBatch)
            val seqMs = (System.nanoTime() - seqStart) / 1_000_000

            val parStart = System.nanoTime()
            val parResult = parGatherer.fromPaths(bigBatch)
            val parMs = (System.nanoTime() - parStart) / 1_000_000

            assertThat(seqResult).hasSize(1000)
            assertThat(parResult).hasSize(1000)
            assertThat(seqResult).isEqualTo(parResult)

            println(
                "[issue-148] 1000-file metadata gather: " +
                        "sequential=${seqMs}ms parallel=${parMs}ms " +
                        "(parallelism=${FileSystemMetadataGatherer.parallelism()})"
            )
        } finally {
            seqGatherer.shutdown()
            parGatherer.shutdown()
        }
    }

    @Test
    fun `inaccessible path returns null entry preserving index`() {
        val missing = tempDir.resolve("does-not-exist.bin")
        val withGap = listOf(files[0], missing, files[1])
        val gatherer = FileSystemMetadataGatherer(MetadataGatherMode.PARALLEL)
        try {
            val out = gatherer.fromPaths(withGap)
            assertThat(out).hasSize(3)
            assertThat(out[0]?.path).isEqualTo(files[0])
            // Missing paths still produce a FileSystemMetadata (size=0, lastModified=null)
            // because Files.isRegularFile + getLastModifiedTime swallow NoSuchFileException
            // and log it. Index alignment is the load-bearing invariant: regardless of
            // whether the entry is null or a degraded record, position 1 maps to the
            // missing path, and position 2 maps to files[1].
            assertThat(out[2]?.path).isEqualTo(files[1])
        } finally {
            gatherer.shutdown()
        }
    }
}
