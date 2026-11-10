@file:OptIn(ExperimentalApi::class, InternalApi::class)

package viaduct.api.mocks

import viaduct.api.internal.InputLikeBase
import viaduct.api.internal.InternalContext
import viaduct.api.reflect.RootObjectField
import viaduct.api.testing.types.ReferenceInvocation
import viaduct.api.testing.types.ReferenceSpy
import viaduct.api.types.Arguments
import viaduct.api.types.Object
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.RootFieldReference
import viaduct.tenant.runtime.toObjectGRT

/**
 * Gives [this] spy the context it needs to read an expected root field call.
 */
internal fun ReferenceSpy.attachTo(internalContext: InternalContext) {
    attach(internalContext.executionContext)
}

internal fun <T : Object> ReferenceSpy.answerReference(
    field: RootObjectField<*, T, Arguments>,
    arguments: Arguments,
    internalContext: InternalContext,
): T {
    record(ReferenceInvocation(field.pathFromQueryRoot, arguments))
    return opaqueReferenceFor(field, arguments, internalContext)
}

private fun <T : Object> opaqueReferenceFor(
    field: RootObjectField<*, T, Arguments>,
    arguments: Arguments,
    internalContext: InternalContext,
): T {
    val args = when (arguments) {
        is Arguments.NoArguments -> emptyMap()
        is InputLikeBase -> arguments.inputData
        else -> throw IllegalArgumentException(
            "Expected Arguments class to be NoArguments or an instance of InputLikeBase, got $arguments"
        )
    }
    val reference = OpaqueRootFieldReference(
        rootFieldPath = field.pathFromQueryRoot,
        type = internalContext.schema.schema.getObjectType(field.type.name),
        args = args,
    )
    return reference.toObjectGRT(internalContext, field.type.kcls)
}

/**
 * Unresolved reference returned to the resolver under test. It holds no data, so every read throws.
 */
private class OpaqueRootFieldReference(
    override val rootFieldPath: List<String>,
    override val type: graphql.schema.GraphQLObjectType,
    override val args: Map<String, Any?>,
) : RootFieldReference, EngineObjectData {
    override suspend fun fetch(selection: String): Any? = unreadable()

    override suspend fun fetchOrNull(selection: String): Any? = unreadable()

    override suspend fun fetchSelections(): Iterable<String> = unreadable()

    private fun unreadable(): Nothing = throw UnsupportedOperationException("Fields cannot be read from an unresolved reference.")
}
