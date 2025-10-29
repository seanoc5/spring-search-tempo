package com.oconeco.spring_search_tempo.base.model

import com.oconeco.spring_search_tempo.base.SpringRoleService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass
import org.springframework.web.servlet.HandlerMapping


/**
 * Validate that the label value isn't taken yet.
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER,
        AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Constraint(validatedBy = [SpringRoleLabelUniqueValidator::class])
annotation class SpringRoleLabelUnique(
    val message: String = "{Exists.springRole.label}",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = []
)


class SpringRoleLabelUniqueValidator(
    private val springRoleService: SpringRoleService,
    private val request: HttpServletRequest
) : ConstraintValidator<SpringRoleLabelUnique, String> {

    override fun isValid(`value`: String?, cvContext: ConstraintValidatorContext): Boolean {
        if (value == null) {
            // no value present
            return true
        }
        @Suppress("unchecked_cast") val pathVariables =
                (request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as
                Map<String, String>)
        val currentId = pathVariables["id"]
        if (currentId != null && value.equals(springRoleService.get(currentId.toLong()).label,
                ignoreCase = true)) {
            // value hasn't changed
            return true
        }
        return !springRoleService.labelExists(value)
    }

}
