package viaduct.apiannotations

import kotlin.RequiresOptIn.Level
import kotlin.annotation.AnnotationRetention.BINARY
import kotlin.annotation.AnnotationTarget.CLASS
import kotlin.annotation.AnnotationTarget.CONSTRUCTOR
import kotlin.annotation.AnnotationTarget.FUNCTION
import kotlin.annotation.AnnotationTarget.PROPERTY
import kotlin.annotation.AnnotationTarget.PROPERTY_GETTER
import kotlin.annotation.AnnotationTarget.PROPERTY_SETTER
import kotlin.annotation.AnnotationTarget.TYPEALIAS

/**
 * Marks an API as intended for testing only.
 *
 * To use testing code, annotate the test file or element with:
 *
 * @file:OptIn(TestingApi::class)
 */
@RequiresOptIn(
    message = "This API is intended for testing only. Do not use it from production code.",
    level = Level.WARNING,
)
@Retention(BINARY)
@Target(
    CLASS,
    FUNCTION,
    PROPERTY,
    CONSTRUCTOR,
    TYPEALIAS,
    PROPERTY_GETTER,
    PROPERTY_SETTER,
)
annotation class TestingApi
