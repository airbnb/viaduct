package viaduct.apiannotations

import kotlin.RequiresOptIn.Level
import kotlin.annotation.AnnotationTarget.CLASS
import kotlin.annotation.AnnotationTarget.CONSTRUCTOR
import kotlin.annotation.AnnotationTarget.FUNCTION
import kotlin.annotation.AnnotationTarget.PROPERTY
import kotlin.annotation.AnnotationTarget.PROPERTY_GETTER
import kotlin.annotation.AnnotationTarget.PROPERTY_SETTER
import kotlin.annotation.AnnotationTarget.TYPEALIAS

/**
 * Marks an API as experimental, i.e., these classes can be changed or removed.
 *
 * This annotation can be applied to classes, functions, and properties to indicate
 * that they are part of the stable API surface.
 *
 */
@RequiresOptIn(
    message = "This API is experimental. Can be changed or removed without any advise.",
    level = Level.WARNING,
)
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
annotation class ExperimentalApi
