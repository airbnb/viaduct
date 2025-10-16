@file:Suppress("MatchingDeclarationName")

package viaduct.service.runtime

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD)
annotation class AdditionalInstrumentation
