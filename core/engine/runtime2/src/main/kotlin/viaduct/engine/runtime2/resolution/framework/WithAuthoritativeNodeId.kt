@file:Suppress("DEPRECATION")

package viaduct.engine.runtime2.resolution.framework

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.airbnb.node.NodeResolverContext
import viaduct.engine.runtime2.model.NodeReferenceIdentity
import viaduct.engine.runtime2.model.ResolverOutputData
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.additionalContextForResolver
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.outputValue
import viaduct.engine.runtime2.model.schemaType

/** Restores identity in Airbnb internal compatibility context regardless of demand; publishes `id` only when demanded. */
internal fun ResolverOutputData?.withAuthoritativeNodeId(
    identity: NodeReferenceIdentity?,
    demand: SelectionForest,
): ResolverOutputData? {
    if (identity == null || this !is EngineObjectData.Sync) return this
    require(schemaType(identity.type) == identity.type) {
        "Node reference for ${identity.type.name} resolved to ${type.name}"
    }
    val idField = identity.type.field("id")
        ?: throw IllegalArgumentException("Node type ${identity.type.name} has no id field")
    val idDemanded =
        demand.merge(identity.type).byKey().keys.any { key -> key.field == idField }
    val context = additionalContextForResolver
    val contextUnchanged = context !is NodeResolverContext || context.identity == identity
    val idUnchanged = if (idDemanded) {
        isPresent(idField.name) && identity.id == outputValue(idField.name)
    } else {
        !isPresent(idField.name)
    }
    if (contextUnchanged && idUnchanged) return this

    val fields = getSelections().associateWith(::outputValue) - idField.name
    return engineObjectDataOf(
        identity.type,
        if (idDemanded) fields + (idField.name to identity.id) else fields,
        additionalContextForResolver = if (context is NodeResolverContext && !contextUnchanged) NodeResolverContext(context.retainedContext, identity) else context,
    )
}
