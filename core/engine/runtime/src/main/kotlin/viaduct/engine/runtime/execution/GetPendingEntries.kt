package viaduct.engine.runtime.execution

import viaduct.engine.api.PendingEntry

/**
 * Builds pending entries for newly announced delivery groups, as described in Section 6.4.7,
 * Mapping Work Events to Response Events.
 */
object GetPendingEntries {
    operator fun invoke(
        ctx: ExecutionParameters,
        newGroups: List<DeferDeliveryGroup>,
    ): List<PendingEntry> =
        newGroups.map { group ->
            val id = ctx.constants.ensureId(group)
            PendingEntry(id, group.path, group.label)
        }
}
