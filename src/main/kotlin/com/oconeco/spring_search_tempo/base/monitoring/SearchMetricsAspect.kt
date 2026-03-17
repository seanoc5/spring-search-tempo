package com.oconeco.spring_search_tempo.base.monitoring

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Aspect that measures search operation latency.
 *
 * Metrics:
 * - tempo.search.fts.duration - Full-text search latency
 * - tempo.search.semantic.duration - Semantic search latency
 * - tempo.search.hybrid.duration - Hybrid search latency
 * - tempo.search.suggest.duration - Suggestion lookup latency
 */
@Aspect
@Component
class SearchMetricsAspect(
    private val meterRegistry: MeterRegistry
) {
    companion object {
        private val log = LoggerFactory.getLogger(SearchMetricsAspect::class.java)
    }

    @Around("execution(* com.oconeco.spring_search_tempo.base.service.FullTextSearchService.searchAll(..))")
    fun measureFtsSearchAll(joinPoint: ProceedingJoinPoint): Any? {
        return measureSearch(joinPoint, "fts", "all")
    }

    @Around("execution(* com.oconeco.spring_search_tempo.base.service.FullTextSearchService.searchFiles(..))")
    fun measureFtsSearchFiles(joinPoint: ProceedingJoinPoint): Any? {
        return measureSearch(joinPoint, "fts", "files")
    }

    @Around("execution(* com.oconeco.spring_search_tempo.base.service.FullTextSearchService.searchChunks(..))")
    fun measureFtsSearchChunks(joinPoint: ProceedingJoinPoint): Any? {
        return measureSearch(joinPoint, "fts", "chunks")
    }

    @Around("execution(* com.oconeco.spring_search_tempo.base.service.FullTextSearchService.searchWithFilters(..))")
    fun measureFtsSearchWithFilters(joinPoint: ProceedingJoinPoint): Any? {
        return measureSearch(joinPoint, "fts", "filtered")
    }

    @Around("execution(* com.oconeco.spring_search_tempo.base.service.SemanticSearchService.search(..))")
    fun measureSemanticSearch(joinPoint: ProceedingJoinPoint): Any? {
        return measureSearch(joinPoint, "semantic", "query")
    }

    @Around("execution(* com.oconeco.spring_search_tempo.base.service.SemanticSearchService.findSimilar(..))")
    fun measureSemanticSimilar(joinPoint: ProceedingJoinPoint): Any? {
        return measureSearch(joinPoint, "semantic", "similar")
    }

    @Around("execution(* com.oconeco.spring_search_tempo.base.service.HybridSearchService.search(..))")
    fun measureHybridSearch(joinPoint: ProceedingJoinPoint): Any? {
        return measureSearch(joinPoint, "hybrid", "query")
    }

    @Around("execution(* com.oconeco.spring_search_tempo.base.service.SearchSuggestionService.suggest(..))")
    fun measureSuggestions(joinPoint: ProceedingJoinPoint): Any? {
        return measureSearch(joinPoint, "suggest", "query")
    }

    private fun measureSearch(joinPoint: ProceedingJoinPoint, type: String, operation: String): Any? {
        val timer = Timer.builder("tempo.search.duration")
            .description("Search operation duration")
            .tag("type", type)
            .tag("operation", operation)
            .publishPercentileHistogram()
            .register(meterRegistry)

        val sample = Timer.start(meterRegistry)
        var success = true

        return try {
            joinPoint.proceed()
        } catch (e: Exception) {
            success = false
            meterRegistry.counter("tempo.search.errors",
                "type", type,
                "operation", operation,
                "exception", e.javaClass.simpleName
            ).increment()
            throw e
        } finally {
            sample.stop(timer)
            meterRegistry.counter("tempo.search.requests",
                "type", type,
                "operation", operation,
                "success", success.toString()
            ).increment()
        }
    }
}
