package viaduct.api.documents

import org.intellij.lang.annotations.Language
import viaduct.apiannotations.StableApi

/**
 * Annotation that declares a GraphQL executable document on a Kotlin singleton object.
 *
 * Apply this annotation to a Kotlin `object` that extends [QueryFromAnnotation] or
 * [MutationFromAnnotation] to define a GraphQL operation once and execute it via
 * `ctx.query` / `ctx.mutation` within the same tenant module.
 *
 * ```kotlin
 * @GraphQLOperation("{ user(id: \$userId) { id ...UserCoreFields } }")
 * object GetUserQuery : QueryFromAnnotation()
 *
 * @GraphQLOperation("mutation { sendMessage(input: \$input) { success } }")
 * object SendMessageMutation : MutationFromAnnotation()
 * ```
 *
 * The annotated class must be a Kotlin singleton `object`. The document must contain exactly one
 * operation. It may reference named fragments from the same module using spread syntax
 * (`...FragmentName`), and variable declarations are validated against the schema at compile time.
 *
 * @see QueryFromAnnotation
 * @see MutationFromAnnotation
 */
@StableApi
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class GraphQLOperation(
    /**
     * The GraphQL executable document text. Must contain exactly one operation.
     *
     * Example: `"{ viewer { id name } }"`
     */
    @Language("GraphQL") val value: String,
)
