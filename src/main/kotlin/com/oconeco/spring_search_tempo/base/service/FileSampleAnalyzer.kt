package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.CrawlDiscoveryFileSample
import com.oconeco.spring_search_tempo.base.domain.DetectedFolderType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Analyzes file samples to detect folder types and improve classification suggestions.
 *
 * This service examines file extensions in discovery file samples to determine
 * what type of content a folder contains. The detected type can then be used
 * to provide better classification suggestions during discovery.
 */
@Service
class FileSampleAnalyzer {

    companion object {
        private val log = LoggerFactory.getLogger(FileSampleAnalyzer::class.java)

        // Minimum samples needed for reliable detection
        const val MIN_SAMPLES_FOR_DETECTION = 3

        // Confidence threshold for declaring a dominant type
        const val DOMINANT_TYPE_THRESHOLD = 0.6

        // File extension mappings by category
        private val SOURCE_CODE_EXTENSIONS = setOf(
            // JVM languages
            "kt", "kts", "java", "scala", "groovy", "clj", "cljs",
            // Web/JavaScript
            "js", "ts", "tsx", "jsx", "mjs", "cjs", "vue", "svelte",
            // Systems languages
            "c", "cpp", "cc", "cxx", "h", "hpp", "hxx", "rs", "go",
            // Scripting
            "py", "rb", "pl", "pm", "php", "lua", "r",
            // Mobile
            "swift", "m", "mm",
            // .NET
            "cs", "fs", "vb",
            // Functional
            "hs", "ml", "elm", "ex", "exs", "erl",
            // Shell
            "sh", "bash", "zsh", "fish", "ps1", "psm1",
            // Other
            "dart", "zig", "nim", "cr", "jl"
        )

        private val DOCUMENTATION_EXTENSIONS = setOf(
            "md", "markdown", "txt", "rst", "adoc", "asciidoc",
            "html", "htm", "xhtml",
            "tex", "latex",
            "org", "wiki"
        )

        private val OFFICE_EXTENSIONS = setOf(
            // Microsoft Office
            "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            // OpenDocument
            "odt", "ods", "odp", "odg",
            // PDF
            "pdf",
            // Other
            "rtf", "pages", "numbers", "key"
        )

        private val MEDIA_EXTENSIONS = setOf(
            // Images
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "ico",
            "tiff", "tif", "raw", "cr2", "nef", "psd", "ai", "eps",
            // Video
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v",
            // Audio
            "mp3", "wav", "flac", "aac", "ogg", "wma", "m4a", "opus"
        )

        private val DATA_EXTENSIONS = setOf(
            "json", "csv", "tsv", "xml", "yaml", "yml", "toml",
            "parquet", "avro", "orc",
            "sql", "sqlite", "db",
            "ndjson", "jsonl"
        )

        private val CONFIG_EXTENSIONS = setOf(
            "conf", "cfg", "ini", "env", "properties",
            "editorconfig", "gitignore", "gitattributes",
            "eslintrc", "prettierrc", "babelrc",
            "dockerignore", "npmrc", "yarnrc"
        )

        private val BUILD_ARTIFACT_EXTENSIONS = setOf(
            // Compiled
            "class", "o", "obj", "a", "so", "dll", "exe", "dylib",
            // Python bytecode
            "pyc", "pyo", "pyd",
            // Archives (often build outputs)
            "jar", "war", "ear", "aar",
            // Maps and caches
            "map", "cache"
        )

        // Known artifact folder names (for heuristic boost)
        private val ARTIFACT_FOLDER_INDICATORS = setOf(
            "node_modules", ".gradle", "build", "dist", "target",
            "__pycache__", ".cache", "vendor", ".npm", ".yarn"
        )
    }

    /**
     * Analyze file samples to detect the folder type.
     *
     * @param samples List of file samples from a discovery observation
     * @param folderPath Optional folder path for heuristic boosts
     * @return Analysis result with detected type and confidence
     */
    fun analyzeFolder(
        samples: List<CrawlDiscoveryFileSample>,
        folderPath: String? = null
    ): FolderAnalysisResult {
        if (samples.size < MIN_SAMPLES_FOR_DETECTION) {
            return FolderAnalysisResult(
                detectedType = DetectedFolderType.UNKNOWN,
                extensionCounts = emptyMap(),
                confidence = 0.0,
                reason = "Insufficient samples (${samples.size} < $MIN_SAMPLES_FOR_DETECTION)"
            )
        }

        // Extract and count extensions
        val extensionCounts = samples
            .mapNotNull { extractExtension(it.fileName) }
            .filter { it.isNotBlank() && it.length <= 15 }
            .groupingBy { it }
            .eachCount()

        if (extensionCounts.isEmpty()) {
            return FolderAnalysisResult(
                detectedType = DetectedFolderType.UNKNOWN,
                extensionCounts = emptyMap(),
                confidence = 0.0,
                reason = "No valid extensions found in samples"
            )
        }

        // Count files by type category
        val typeCounts = mutableMapOf<DetectedFolderType, Int>()

        for ((ext, count) in extensionCounts) {
            val type = categorizeExtension(ext)
            if (type != null) {
                typeCounts.merge(type, count, Int::plus)
            }
        }

        // Apply folder path heuristic boost for artifact folders
        if (folderPath != null) {
            val folderName = folderPath.substringAfterLast('/').lowercase()
            if (ARTIFACT_FOLDER_INDICATORS.any { folderName.contains(it) }) {
                // Boost artifact detection for known artifact folders
                typeCounts.merge(DetectedFolderType.BUILD_ARTIFACTS, 3, Int::plus)
            }
        }

        val totalCategorized = typeCounts.values.sum()
        if (totalCategorized == 0) {
            return FolderAnalysisResult(
                detectedType = DetectedFolderType.UNKNOWN,
                extensionCounts = extensionCounts,
                confidence = 0.0,
                reason = "No recognized file types in samples"
            )
        }

        // Find dominant type
        val dominant = typeCounts.maxByOrNull { it.value }!!
        val confidence = dominant.value.toDouble() / totalCategorized

        val detectedType = if (confidence >= DOMINANT_TYPE_THRESHOLD) {
            dominant.key
        } else {
            DetectedFolderType.MIXED
        }

        val reason = if (detectedType == DetectedFolderType.MIXED) {
            "No dominant type (best: ${dominant.key} at ${(confidence * 100).toInt()}%)"
        } else {
            "${dominant.key} detected with ${(confidence * 100).toInt()}% confidence"
        }

        return FolderAnalysisResult(
            detectedType = detectedType,
            extensionCounts = extensionCounts,
            confidence = confidence,
            reason = reason
        )
    }

    /**
     * Suggest an analysis status based on detected folder type.
     *
     * @param folderType The detected folder type
     * @param confidence Confidence level of the detection
     * @return Suggested analysis status
     */
    fun suggestAnalysisStatus(
        folderType: DetectedFolderType,
        confidence: Double = 1.0
    ): AnalysisStatus {
        // Only suggest if confidence is high enough
        if (confidence < 0.5 && folderType != DetectedFolderType.BUILD_ARTIFACTS) {
            return AnalysisStatus.LOCATE
        }

        return when (folderType) {
            DetectedFolderType.SOURCE_CODE -> AnalysisStatus.ANALYZE  // NLP-worthy: extract entities, keywords
            DetectedFolderType.DOCUMENTATION -> AnalysisStatus.ANALYZE  // NLP-worthy: important text content
            DetectedFolderType.OFFICE_DOCS -> AnalysisStatus.INDEX  // Full-text search, but not NLP
            DetectedFolderType.DATA -> AnalysisStatus.INDEX  // Searchable, but not NLP
            DetectedFolderType.CONFIG -> AnalysisStatus.INDEX  // Searchable config files
            DetectedFolderType.MEDIA -> AnalysisStatus.LOCATE  // Metadata only, no text extraction
            DetectedFolderType.BUILD_ARTIFACTS -> AnalysisStatus.SKIP  // Regeneratable, skip entirely
            DetectedFolderType.MIXED -> AnalysisStatus.LOCATE  // Mixed: default to metadata
            DetectedFolderType.UNKNOWN -> AnalysisStatus.LOCATE  // Unknown: safe default
        }
    }

    /**
     * Analyze multiple observations and return analysis results keyed by path.
     */
    fun analyzeObservations(
        observations: List<ObservationWithSamples>
    ): Map<String, FolderAnalysisResult> {
        return observations.associate { (path, samples) ->
            path to analyzeFolder(samples, path)
        }
    }

    private fun extractExtension(fileName: String?): String? {
        if (fileName.isNullOrBlank()) return null
        val lastDot = fileName.lastIndexOf('.')
        if (lastDot < 0 || lastDot == fileName.length - 1) return null
        return fileName.substring(lastDot + 1).lowercase()
    }

    private fun categorizeExtension(ext: String): DetectedFolderType? {
        return when {
            ext in SOURCE_CODE_EXTENSIONS -> DetectedFolderType.SOURCE_CODE
            ext in DOCUMENTATION_EXTENSIONS -> DetectedFolderType.DOCUMENTATION
            ext in OFFICE_EXTENSIONS -> DetectedFolderType.OFFICE_DOCS
            ext in MEDIA_EXTENSIONS -> DetectedFolderType.MEDIA
            ext in DATA_EXTENSIONS -> DetectedFolderType.DATA
            ext in CONFIG_EXTENSIONS -> DetectedFolderType.CONFIG
            ext in BUILD_ARTIFACT_EXTENSIONS -> DetectedFolderType.BUILD_ARTIFACTS
            else -> null
        }
    }
}

/**
 * Result of folder type analysis.
 */
data class FolderAnalysisResult(
    /** The detected folder type */
    val detectedType: DetectedFolderType,

    /** Raw extension counts from the samples */
    val extensionCounts: Map<String, Int>,

    /** Confidence level (0.0 to 1.0) */
    val confidence: Double,

    /** Human-readable explanation of the detection */
    val reason: String
)

/**
 * Input data for batch observation analysis.
 */
data class ObservationWithSamples(
    val path: String,
    val samples: List<CrawlDiscoveryFileSample>
)
