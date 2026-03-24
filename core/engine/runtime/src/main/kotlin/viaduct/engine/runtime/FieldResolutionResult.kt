package viaduct.engine.runtime

import graphql.GraphQLError
import graphql.execution.FetchedValue
import viaduct.engine.api.ResolutionPolicy
import viaduct.engine.runtime.context.CompositeLocalContext

/**
 * Data class to hold the engine result along with the fetched value.
 *
 * @property engineResult The engine result after fetching.
 * @property originalSource The fetched value from the data fetcher.
 */
data class FieldResolutionResult(
    val engineResult: Any?,
    val errors: List<GraphQLError>,
    val localContext: CompositeLocalContext,
    val extensions: Map<Any, Any?>,
    val originalSource: Any?,
    val resolutionPolicy: ResolutionPolicy = ResolutionPolicy.STANDARD,
) {
    companion object {
        private val Any?.asCompositeLocalContext: CompositeLocalContext
            get() = when (val ctx = this) {
                null -> CompositeLocalContext.empty
                is CompositeLocalContext -> ctx
                else ->
                    throw IllegalStateException("Expected CompositeLocalContext but found ${ctx::class}")
            }

        fun fromErrors(errors: List<GraphQLError>,) =
            FieldResolutionResult(
                engineResult = null,
                errors = errors,
                localContext = CompositeLocalContext.empty,
                extensions = emptyMap(),
                originalSource = null,
            )

        fun fromFetchedValue(
            engineResult: Any?,
            fetchedValue: FetchedValue,
            resolutionPolicy: ResolutionPolicy,
            originalSource: Any? = fetchedValue.fetchedValue,
        ) = FieldResolutionResult(
            engineResult,
            fetchedValue.errors,
            fetchedValue.localContext.asCompositeLocalContext,
            when (fetchedValue) {
                is FetchedValueWithExtensions -> fetchedValue.extensions
                else -> emptyMap()
            },
            originalSource,
            resolutionPolicy,
        )
    }
}
