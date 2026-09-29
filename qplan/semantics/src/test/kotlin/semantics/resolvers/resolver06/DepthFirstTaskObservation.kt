package semantics.resolvers.resolver06

import model.ListEngineResult
import model.ObjectEngineResult
import model.PathComponent
import model.groundKey
import semantics.contract.ResolverTaskObservation
import semantics.resolvers.resolver01.DepthFirstFieldResolverTask
import semantics.resolvers.resolver01.DepthFirstOrchestrationTask
import semantics.resolvers.resolver01.DepthFirstTask

internal fun DepthFirstTask.toContractObservation(): ResolverTaskObservation =
    when (this) {
        is DepthFirstOrchestrationTask ->
            ResolverTaskObservation.SlotOrchestration(
                objectType = objectOER.occurrence.target.type.name,
                path = path.toContractObservationPath(),
            )

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
