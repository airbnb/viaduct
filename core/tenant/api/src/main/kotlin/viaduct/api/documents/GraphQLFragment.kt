package viaduct.api.documents

import org.intellij.lang.annotations.Language
import viaduct.apiannotations.ExperimentalApi

/**
 * Annotation that declares a named GraphQL fragment on a Kotlin singleton object.
 *
 * Apply this annotation to a Kotlin `object` that extends [FragmentFromAnnotation] to define a
 * reusable GraphQL fragment and spread it (`...MyFragment`) in resolver fragments and
 * `ctx.query`/`ctx.mutation` calls within the same tenant module.
 *
 * ```kotlin
 * @GraphQLFragment("""
 *   fragment UserCoreFields on User {
 *     id
 *     name
 *     email
 *   }
 * """)
 * object UserCoreFieldsFragment : FragmentFromAnnotation<User>()
 * ```
 *
 * The annotated class must be a Kotlin singleton `object`. The fragment text must contain exactly
 * one fragment definition. Named fragments are scoped to the tenant module in which they are
 * declared — spreading a fragment from a different module is a compile error.
 *
 * @see FragmentFromAnnotation
 * @see [Resolver][viaduct.api.resolver.Resolver]
 */
@ExperimentalApi
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class GraphQLFragment(
    /**
     * The GraphQL fragment definition text. Must contain exactly one fragment definition.
     *
     * Example: `"fragment UserFields on User { id name email }"`
     */
    @Language("GraphQL") val value: String,
)
