package com.oconeco.spring_search_tempo.base.model

import com.oconeco.spring_search_tempo.base.FSFileService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass
import org.springframework.web.servlet.HandlerMapping


/**
 * Validate that the uri value isn't taken yet.
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER,
        AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Constraint(validatedBy = [FSFileUriUniqueValidator::class])
annotation class FSFileUriUnique(
    val message: String = "{Exists.fSFile.uri}",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = []
)


class FSFileUriUniqueValidator(
    private val fSFileService: FSFileService,
    private val request: HttpServletRequest
) : ConstraintValidator<FSFileUriUnique, String> {

    override fun isValid(`value`: String?, cvContext: ConstraintValidatorContext): Boolean {
        if (value == null) {
            // no value present
            return true
        }
        @Suppress("unchecked_cast") val pathVariables =
                (request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as
                Map<String, String>)
        val currentId = pathVariables["id"]
        if (currentId != null && value.equals(fSFileService.get(currentId.toLong()).uri, ignoreCase
                = true)) {
            // value hasn't changed
            return true
        }
        return !fSFileService.uriExists(value)
    }

}
