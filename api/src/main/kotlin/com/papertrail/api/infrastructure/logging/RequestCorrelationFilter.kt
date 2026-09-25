package com.papertrail.api.infrastructure.logging

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Component
class RequestCorrelationFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requestId = request.getHeader(REQUEST_ID_HEADER)
            ?.takeIf(REQUEST_ID_PATTERN::matches)
            ?: UUID.randomUUID().toString()
        response.setHeader(REQUEST_ID_HEADER, requestId)
        val previousRequestId = MDC.get(REQUEST_ID_MDC_KEY)
        MDC.put(REQUEST_ID_MDC_KEY, requestId)
        val startedAt = System.nanoTime()

        var failure: Exception? = null
        try {
            filterChain.doFilter(request, response)
        } catch (exception: Exception) {
            failure = exception
            throw exception
        } finally {
            try {
                val status = if (failure != null && response.status < HttpServletResponse.SC_BAD_REQUEST) {
                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                } else {
                    response.status
                }
                val failed = failure != null || status >= HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                val eventBuilder = if (failed) log.atError() else log.atInfo()
                eventBuilder
                    .addKeyValue("httpMethod", request.method)
                    .addKeyValue("httpPath", request.requestURI)
                    .addKeyValue("httpStatus", status)
                    .addKeyValue("durationMs", (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND)
                failure?.let { eventBuilder.addKeyValue("errorType", it.javaClass.simpleName) }
                eventBuilder.log(if (failed) "HTTP request failed" else "HTTP request completed")
            } finally {
                if (previousRequestId == null) MDC.remove(REQUEST_ID_MDC_KEY) else MDC.put(REQUEST_ID_MDC_KEY, previousRequestId)
            }
        }
    }

    companion object {
        const val REQUEST_ID_HEADER = "X-Request-ID"
        const val REQUEST_ID_MDC_KEY = "requestId"
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,128}")
        private val log = LoggerFactory.getLogger(RequestCorrelationFilter::class.java)
    }
}
