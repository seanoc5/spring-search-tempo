package com.oconeco.spring_search_tempo.base.config

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.zip.GZIPInputStream

/**
 * Transparently decompress gzipped request bodies for JSON API clients.
 */
@Component
class GzipRequestBodyFilter : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val contentEncoding = request.getHeader("Content-Encoding") ?: return true
        return !contentEncoding.lowercase().contains("gzip")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        filterChain.doFilter(GzipHttpServletRequestWrapper(request), response)
    }
}

private class GzipHttpServletRequestWrapper(
    request: HttpServletRequest
) : HttpServletRequestWrapper(request) {

    private val gzipStream = GZIPInputStream(request.inputStream)
    private val inputStream = object : ServletInputStream() {
        private var finished = false

        override fun read(): Int {
            val value = gzipStream.read()
            finished = value == -1
            return value
        }

        override fun isFinished(): Boolean = finished

        override fun isReady(): Boolean = true

        override fun setReadListener(readListener: ReadListener?) {
            // Async request body reads are not used for these API endpoints.
        }
    }

    override fun getInputStream(): ServletInputStream = inputStream

    override fun getReader(): BufferedReader =
        BufferedReader(InputStreamReader(inputStream, requestCharset()))

    override fun getHeader(name: String): String? {
        return if (name.equals("Content-Encoding", ignoreCase = true)) {
            null
        } else {
            super.getHeader(name)
        }
    }

    override fun getHeaders(name: String): java.util.Enumeration<String> {
        return if (name.equals("Content-Encoding", ignoreCase = true)) {
            Collections.emptyEnumeration()
        } else {
            super.getHeaders(name)
        }
    }

    override fun getHeaderNames(): java.util.Enumeration<String> {
        val headerNames = Collections.list(super.getHeaderNames())
            .filterNot { it.equals("Content-Encoding", ignoreCase = true) }
        return Collections.enumeration(headerNames)
    }

    override fun getContentLength(): Int = -1

    override fun getContentLengthLong(): Long = -1

    private fun requestCharset(): Charset {
        val encoding = characterEncoding?.trim().orEmpty()
        return if (encoding.isBlank()) StandardCharsets.UTF_8 else Charset.forName(encoding)
    }
}
