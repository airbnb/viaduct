package viaduct.engine.runtime.execution

import graphql.execution.ResultPath
import viaduct.engine.api.IncrementalEntry

/**
 * Builds a data entry for a delivery group, as described in Section 6.4.7,
 * Mapping Work Events to Response Events.
 */
object GetIncrementalEntry {
    operator fun invoke(
        ctx: ExecutionParameters,
        group: DeferDeliveryGroup,
        value: GroupValue
    ): IncrementalEntry.Object {
        val id = ctx.constants.ensureId(group)

        val groupPath = group.path.toList()
        val valuePath = value.path.toList()
        require(valuePath.take(groupPath.size) == groupPath) {
            "Incremental values must be below their group's response path"
        }
        val subPath = valuePath.drop(groupPath.size)
        return IncrementalEntry.Object(
            id,
            data = value.data,
            errors = value.errors,
            subPath = subPath.takeIf { it.isNotEmpty() }?.let { ResultPath.fromList(it) }
        )
    }
}
