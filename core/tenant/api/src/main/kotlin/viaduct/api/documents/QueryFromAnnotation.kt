package viaduct.api.documents

import viaduct.apiannotations.ExperimentalApi

/**
 * Abstract base class for named GraphQL query operation objects.
 *
 * Extend this class with a Kotlin singleton `object` and annotate it with [GraphQLOperation].
 *
 * ```kotlin
 * @GraphQLOperation("{ user(id: \$userId) { id name } }")
 * object GetUserQuery : QueryFromAnnotation()
 * ```
 *
 * Execute via `ctx.query`:
 * ```kotlin
 * val result = ctx.query(GetUserQuery.operationText)
 * ```
 *
 * @see GraphQLOperation
 * @see MutationFromAnnotation
 */
@ExperimentalApi
abstract class QueryFromAnnotation {
    /**
     * The GraphQL operation document text declared in [@GraphQLOperation][GraphQLOperation].
     *
     * @throws IllegalStateException if the subclass is not annotated with [@GraphQLOperation][GraphQLOperation].
     */
    val operationText: String by lazy {
        this::class.annotations
            .filterIsInstance<GraphQLOperation>()
            .firstOrNull()
            ?.value
            ?: error("${this::class.simpleName} must be annotated with @GraphQLOperation")
    }
}
