package viaduct.api.documents

import viaduct.apiannotations.StableApi

/**
 * Abstract base class for named GraphQL mutation operation objects.
 *
 * Extend this class with a Kotlin singleton `object` and annotate it with [GraphQLOperation].
 *
 * ```kotlin
 * @GraphQLOperation("mutation { sendMessage(input: \$input) { success } }")
 * object SendMessageMutation : MutationFromAnnotation()
 * ```
 *
 * Execute via `ctx.mutation`:
 * ```kotlin
 * val result = ctx.mutation(SendMessageMutation.operationText, mapOf("input" to inputValue))
 * ```
 *
 * @see GraphQLOperation
 * @see QueryFromAnnotation
 */
@StableApi
abstract class MutationFromAnnotation {
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
