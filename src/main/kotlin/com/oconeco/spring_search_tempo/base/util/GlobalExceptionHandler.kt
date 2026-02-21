package com.oconeco.spring_search_tempo.base.util

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.servlet.ModelAndView

/**
 * Exception handler for web (non-API) requests, providing appropriate error pages.
 *
 * This handler ONLY applies to controllers in the web/controller packages (HTML views).
 * REST API errors are handled by error-handling-spring-boot-starter library,
 * which provides structured JSON responses with the expected format.
 *
 * Using basePackages ensures REST controllers in base.rest and web.rest are excluded.
 */
@ControllerAdvice(basePackages = [
    "com.oconeco.spring_search_tempo.base.controller",
    "com.oconeco.spring_search_tempo.web.controller"
])
class GlobalExceptionHandler {

    companion object {
        private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    }

    /**
     * Handle entity not found exceptions for web requests.
     */
    @ExceptionHandler(NotFoundException::class)
    fun handleNotFound(
        ex: NotFoundException,
        request: HttpServletRequest
    ): ModelAndView {
        log.debug("Resource not found: {}", request.requestURI)

        return ModelAndView("error/404").apply {
            status = HttpStatus.NOT_FOUND
            addObject("message", ex.message ?: "The requested resource was not found")
            addObject("path", request.requestURI)
        }
    }

    /**
     * Handle referential integrity violations for web requests.
     */
    @ExceptionHandler(ReferencedException::class)
    fun handleReferenced(
        ex: ReferencedException,
        request: HttpServletRequest
    ): ModelAndView {
        log.debug("Referenced entity conflict: {}", ex.message)

        val message = WebUtils.getMessage(ex.key!!, ex.params.toTypedArray())

        return ModelAndView("error/409").apply {
            status = HttpStatus.CONFLICT
            addObject("message", message)
            addObject("path", request.requestURI)
        }
    }

    /**
     * Handle validation errors for web requests.
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(
        ex: MethodArgumentNotValidException,
        request: HttpServletRequest
    ): ModelAndView {
        log.debug("Validation failed for request to {}: {} errors",
            request.requestURI, ex.bindingResult.errorCount)

        val fieldErrors = ex.bindingResult.allErrors.associate { error ->
            val fieldName = (error as? org.springframework.validation.FieldError)?.field ?: error.objectName
            fieldName to (error.defaultMessage ?: "Invalid value")
        }

        return ModelAndView("error/400").apply {
            status = HttpStatus.BAD_REQUEST
            addObject("message", "Validation failed")
            addObject("errors", fieldErrors)
            addObject("path", request.requestURI)
        }
    }

    /**
     * Handle illegal argument exceptions for web requests.
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(
        ex: IllegalArgumentException,
        request: HttpServletRequest
    ): ModelAndView {
        log.debug("Illegal argument: {}", ex.message)

        return ModelAndView("error/400").apply {
            status = HttpStatus.BAD_REQUEST
            addObject("message", ex.message ?: "Invalid request parameter")
            addObject("path", request.requestURI)
        }
    }

    /**
     * Handle unexpected exceptions for web requests.
     */
    @ExceptionHandler(Exception::class)
    fun handleGeneric(
        ex: Exception,
        request: HttpServletRequest
    ): ModelAndView {
        log.error("Unexpected error processing request to {}: {}",
            request.requestURI, ex.message, ex)

        return ModelAndView("error/500").apply {
            status = HttpStatus.INTERNAL_SERVER_ERROR
            addObject("message", "An unexpected error occurred")
            addObject("path", request.requestURI)
        }
    }
}
