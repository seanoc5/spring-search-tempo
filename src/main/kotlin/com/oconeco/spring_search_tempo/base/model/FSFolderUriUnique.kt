package com.oconeco.spring_search_tempo.base.model

import com.oconeco.spring_search_tempo.base.FSFolderService
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
@Constraint(validatedBy = [FSFolderUriUniqueValidator::class])
annotation class FSFolderUriUnique(
    val message: String = "{Exists.fSFolder.uri}",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = []
)


class FSFolderUriUniqueValidator(
    private val fSFolderService: FSFolderService,
    private val request: HttpServletRequest
) : ConstraintValidator<FSFolderUriUnique, String> {

    override fun isValid(`value`: String?, cvContext: ConstraintValidatorContext): Boolean {
        if (value == null) {
            // no value present
            return true
        }
        @Suppress("unchecked_cast") val pathVariables =
                (request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as
                Map<String, String>)
        val currentId = pathVariables["id"]
        if (currentId != null && value.equals(fSFolderService.get(currentId.toLong()).uri,
                ignoreCase = true)) {
            // value hasn't changed
            return true
        }
        return !fSFolderService.uriExists(value)
    }

}
