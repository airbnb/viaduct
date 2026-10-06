package viaduct.engine.runtime2.resolvers.resolver06

import viaduct.engine.runtime2.contract.ResolverTaskObservation
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.groundKey
import viaduct.engine.runtime2.resolvers.resolver01.DepthFirstFieldResolverTask
import viaduct.engine.runtime2.resolvers.resolver01.DepthFirstMutationTask
import viaduct.engine.runtime2.resolvers.resolver01.DepthFirstOrchestrationTask
import viaduct.engine.runtime2.resolvers.resolver01.DepthFirstTask

internal fun DepthFirstTask.toContractObservation(): ResolverTaskObservation =
    when (this) {
        is DepthFirstOrchestrationTask ->
            ResolverTaskObservation.SlotOrchestration(
                objectType = objectOER.occurrence.target.type.name,
                path = path.toContractObservationPath(),
            )

        is DepthFirstMutationTask -> ResolverTaskObservation.SlotOrchestration("Mutation", path.toContractObservationPath())

        is DepthFirstFieldResolverTask -> {
            ResolverTaskObservation.SlotResolver(
                fieldName = publication.selection.groundKey().field.name,
                path = path.toContractObservationPath(),
            )
        }
    }

private fun List<PathComponent>.toContractObservationPath(): List<String> =
    map { component ->
        when (component) {
            is ObjectEngineResult.ObjectKey -> component.field.name
            is ListEngineResult.Index -> "[${component.index}]"
        }
    }
