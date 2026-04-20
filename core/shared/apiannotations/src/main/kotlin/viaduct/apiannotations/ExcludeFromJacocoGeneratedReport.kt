package viaduct.apiannotations

import kotlin.annotation.AnnotationRetention.BINARY
import kotlin.annotation.AnnotationTarget.CLASS
import kotlin.annotation.AnnotationTarget.FUNCTION

/**
 * Excludes the annotated class or function from JaCoCo coverage reports.
 * JaCoCo (0.8.2+) ignores any element annotated with an annotation whose
 * simple name contains "Generated".
 */
@Retention(BINARY)
@Target(CLASS, FUNCTION)
annotation class ExcludeFromJacocoGeneratedReport
