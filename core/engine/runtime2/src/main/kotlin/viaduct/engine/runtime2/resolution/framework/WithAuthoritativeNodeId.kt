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

/** Restores authoritative identity in demanded output and Airbnb internal compatibility context. */
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
    val demandedFields = demand.merge(identity.type).byKey().keys.mapTo(hashSetOf()) { key -> key.field.name }
    val idDemanded = idField.name in demandedFields
    val context = additionalContextForResolver
    val contextUnchanged = context !is NodeResolverContext || context.identity == identity
    val idUnchanged = if (idDemanded) {
        isPresent(idField.name) && identity.id == outputValue(idField.name)
    } else {
        true
    }
    if (contextUnchanged && idUnchanged) return this

    // Invocation demand covers passive consumption, so identity repair can copy only demanded fields.
    val fields = getSelections()
        .filter { it in demandedFields && it != idField.name }
        .associateWith(::outputValue)
    return engineObjectDataOf(
        identity.type,
        if (idDemanded) fields + (idField.name to identity.id) else fields,
        additionalContextForResolver = if (context is NodeResolverContext && !contextUnchanged) NodeResolverContext(context.retainedContext, identity) else context,
    )
}
