package viaduct.engine.runtime2.resolution.framework

import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.registry.ProviderFragment
import viaduct.graphql.schema.ViaductSchema

/** One field occurrence identified by its owning OER, path, and schema field. */
data class ResolverFieldOccurrence(
    val root: ObjectEngineResult,
    val path: List<PathComponent>,
    val field: ViaductSchema.ObjectField,
)

/** One provider cell read for one resolver variable at its exact provider and consumer paths. */
data class ResolverProviderReadObservation(
    val provider: ResolverFieldOccurrence,
    val consumer: ResolverFieldOccurrence,
    val variableName: String,
    val providerFragment: ProviderFragment,
    val outcome: ProviderCellOutcome,
)

enum class ProviderCellOutcome { VALUE, NULL, ERROR }
