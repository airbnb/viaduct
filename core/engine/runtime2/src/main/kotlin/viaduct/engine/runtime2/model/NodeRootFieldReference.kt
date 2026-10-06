@file:Suppress("MatchingDeclarationName")

package viaduct.engine.runtime2.model

import viaduct.graphql.schema.ViaductSchema

/** The decoded identity carried by a root-field reference to the built-in `Query.node`. */
data class NodeReferenceIdentity(
    val type: ViaductSchema.Object,
    val id: String,
)

/** Internal encoding used to retain a node reference's concrete type and original ID. */
const val NODE_REFERENCE_ID_PREFIX: String = "\$node:"

/** Creates the canonical root-field-reference representation of one node reference. */
fun nodeRootFieldReferenceOf(
    queryNode: ViaductSchema.ObjectField,
    type: ViaductSchema.Object,
    id: String,
): RootFieldReferenceData {
    require(queryNode.containingDef.name == "Query" && queryNode.name == "node") {
        "A node reference must target Query/node"
    }
    val declaredType = queryNode.outputType.baseTypeDef as? ViaductSchema.CompositeTypeDef
        ?: throw IllegalArgumentException("Query/node must return a composite type")
    require(type in declaredType.possibleObjectTypes) {
        "Node reference type ${type.name} is not valid for Query/node"
    }
    return RootFieldReferenceData.of(
        path = listOf(queryNode),
        arguments = mapOf("id" to encodeNodeReferenceId(type, id)),
    )
}

/** Returns this reference's concrete node identity, or null when it does not target Query.node. */
fun RootFieldReferenceData.nodeReferenceIdentityOrNull(): NodeReferenceIdentity? {
    if (path.size != 1) return null
    val target = targetField
    if (target.containingDef.name != "Query" || target.name != "node") return null
    val encodedId = arguments.fieldValues["id"] as? String ?: return null
    return decodeNodeReferenceId(target, encodedId)
}

/** Encodes a concrete node type without changing the authoritative resolver ID. */
fun encodeNodeReferenceId(
    type: ViaductSchema.Object,
    id: String,
): String = "$NODE_REFERENCE_ID_PREFIX${type.name.length}:${type.name}$id"

/** Decodes and validates a node identity against the declared Query.node output type. */
fun decodeNodeReferenceId(
    queryNode: ViaductSchema.ObjectField,
    encodedId: String,
): NodeReferenceIdentity? {
    if (!encodedId.startsWith(NODE_REFERENCE_ID_PREFIX)) return null
    val encoded = encodedId.removePrefix(NODE_REFERENCE_ID_PREFIX)
    val separator = encoded.indexOf(':')
    if (separator <= 0) return null
    val typeNameLength = encoded.substring(0, separator).toIntOrNull() ?: return null
    val typeNameStart = separator + 1
    val typeNameEnd = typeNameStart + typeNameLength
    if (typeNameLength <= 0 || typeNameEnd > encoded.length) return null
    val typeName = encoded.substring(typeNameStart, typeNameEnd)
    val declaredType = queryNode.outputType.baseTypeDef as? ViaductSchema.CompositeTypeDef
        ?: return null
    val type = declaredType.possibleObjectTypes.singleOrNull { it.name == typeName } ?: return null
    return NodeReferenceIdentity(type, encoded.substring(typeNameEnd))
}
