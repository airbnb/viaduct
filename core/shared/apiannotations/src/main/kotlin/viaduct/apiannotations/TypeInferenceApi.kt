package viaduct.apiannotations

import kotlin.annotation.AnnotationTarget.CLASS
import kotlin.annotation.AnnotationTarget.CONSTRUCTOR
import kotlin.annotation.AnnotationTarget.FUNCTION
import kotlin.annotation.AnnotationTarget.PROPERTY
import kotlin.annotation.AnnotationTarget.PROPERTY_GETTER
import kotlin.annotation.AnnotationTarget.PROPERTY_SETTER
import kotlin.annotation.AnnotationTarget.TYPEALIAS

/**
 * Marks an API that exists solely to support Kotlin type inference in the Viaduct framework.
 *
 * These APIs are not intended for direct use by consumers. They may change or be removed
 * without notice. Unlike [InternalApi], no opt-in is required because these interfaces are
 * implemented transparently by generated code — consumers never reference them directly.
 */
@Target(
    CLASS,
    FUNCTION,
    PROPERTY,
    CONSTRUCTOR,
    TYPEALIAS,
    PROPERTY_GETTER,
    PROPERTY_SETTER,
)
@Retention(AnnotationRetention.BINARY)
annotation class TypeInferenceApi
