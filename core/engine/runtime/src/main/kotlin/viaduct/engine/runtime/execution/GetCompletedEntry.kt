package viaduct.engine.runtime.execution

import graphql.GraphQLError
import viaduct.engine.api.CompletedEntry

/**
 * Builds a completion entry for a delivery group, as described in Section 6.4.7,
 * Mapping Work Events to Response Events.
 */
object GetCompletedEntry {
    operator fun invoke(
        ctx: ExecutionParameters,
        group: DeferDeliveryGroup,
        errors: List<GraphQLError>
    ): CompletedEntry = CompletedEntry(ctx.constants.ensureId(group), errors)
}
