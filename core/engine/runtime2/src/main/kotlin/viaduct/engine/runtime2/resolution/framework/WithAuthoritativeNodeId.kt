package viaduct.engine.runtime2.resolution.framework

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.NodeReferenceIdentity
import viaduct.engine.runtime2.model.ResolverOutputData
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.outputValue
import viaduct.engine.runtime2.model.schemaType

/**
 * Preserves the originating node reference's ID in an object result when [demand] selects `id`.
 * All resolvers use this after following reference tails, whose final resolver may return
 * a different ID. The returned object's type must match the originating node identity.
 */
internal fun ResolverOutputData?.withAuthoritativeNodeId(
    identity: NodeReferenceIdentity?,
    demand: SelectionForest,
): ResolverOutputData? {
    if (identity == null || this !is EngineObjectData.Sync) return this
    require(schemaType == identity.type) {
        "Node reference for ${identity.type.name} resolved to ${schemaType.name}"
    }
    val idField = identity.type.field("id")
        ?: throw IllegalArgumentException("Node type ${identity.type.name} has no id field")
    val idDemanded =
        demand.merge(identity.type).byKey().keys.any { key -> key.field == idField }
    if (!idDemanded) return this
    return engineObjectDataOf(
        identity.type,
        getSelections().associateWith(::outputValue) + (idField.name to identity.id),
    )
}
