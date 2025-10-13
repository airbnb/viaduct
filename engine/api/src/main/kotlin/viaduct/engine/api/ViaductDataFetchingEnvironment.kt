package viaduct.engine.api

import graphql.schema.DataFetchingEnvironment

/**
 * Viaduct's DataFetchingEnvironment implementation that bridges GraphQL-Java compatibility
 * with Viaduct's modern execution model.
 *
 * This interface provides access to Viaduct's stable execution context API while maintaining
 * backwards compatibility with GraphQL-Java's DataFetchingEnvironment.
 */
interface ViaductDataFetchingEnvironment : DataFetchingEnvironment {
    /**
     * Viaduct's engine execution context containing all engine-level execution state.
     *
     * This is the stable API for accessing execution context. Prefer this over
     * [DataFetchingEnvironment] methods when possible, as it:
     * - Makes context-sensitivity explicit (fragments/variables change with child plans)
     * - Provides forward compatibility (when DFE is eventually deprecated)
     * - Offers clearer semantics (Viaduct concepts vs GraphQL-Java concepts)
     *
     * Access execution state via:
     * - `engineExecutionContext.fragments` instead of `fragmentsByName`
     * - `engineExecutionContext.variables` instead of `variables`
     * - `engineExecutionContext.fullSchema` for schema access
     * - etc.
     */
    val engineExecutionContext: EngineExecutionContext
}

/**
 * Safely casts a DataFetchingEnvironment to ViaductDataFetchingEnvironment.
 * Throws an error if the cast is not possible.
 */
fun DataFetchingEnvironment.requireViaductDataFetchingEnvironment(): ViaductDataFetchingEnvironment =
    this as? ViaductDataFetchingEnvironment
        ?: error("Expected ViaductDataFetchingEnvironment, got ${this::class}")

/**
 * Extension property to access Viaduct's EngineExecutionContext from any DataFetchingEnvironment.
 * This provides a seamless way to access Viaduct-specific execution context while maintaining
 * compatibility with GraphQL-Java's DataFetchingEnvironment.
 *
 * This will throw an error if the DataFetchingEnvironment is not a ViaductDataFetchingEnvironment.
 */
val DataFetchingEnvironment.engineExecutionContext
    get() = requireViaductDataFetchingEnvironment().engineExecutionContext
