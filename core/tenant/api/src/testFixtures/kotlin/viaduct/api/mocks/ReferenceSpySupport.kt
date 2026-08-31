@file:OptIn(ExperimentalApi::class, InternalApi::class)

package viaduct.api.mocks

import viaduct.api.context.ResolverExecutionContext
import viaduct.api.context.RootFieldCall
import viaduct.api.internal.InputLikeBase
import viaduct.api.internal.InternalContext
import viaduct.api.reflect.RootObjectField
import viaduct.api.testing.types.ReferenceInvocation
import viaduct.api.testing.types.ReferenceSpy
import viaduct.api.types.Arguments
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.RootFieldReference
import viaduct.tenant.runtime.toObjectGRT

/**
 * Teaches [this] spy how to read an expected [RootFieldCall], so it can be compared against a
 * recorded one.
 */
internal fun ReferenceSpy.attachTo(internalContext: InternalContext) {
    attach { call -> invocationFor(call, internalContext) }
}

internal fun <A : Arguments, T : Object> ReferenceSpy.answerReference(
    field: RootObjectField<*, T, A>,
    arguments: A,
    internalContext: InternalContext,
): T {
    record(ReferenceInvocation(field.pathFromQueryRoot, arguments))
    return opaqueReferenceFor(field, arguments, internalContext)
}

/**
 * Reads the field and arguments out of an expected [call], so it can be compared against a recorded one.
 */
private fun invocationFor(
    call: RootFieldCall<*>,
    internalContext: InternalContext,
): ReferenceInvocation {
    lateinit var captured: ReferenceInvocation
    val context: ResolverExecutionContext<Query> = object : MockResolverExecutionContext<Query>(internalContext) {
        override fun <A : Arguments, T : Object> rootFieldRef(
            field: RootObjectField<*, T, A>,
            arguments: A,
        ): T {
            captured = ReferenceInvocation(field.pathFromQueryRoot, arguments)
            return opaqueReferenceFor(field, arguments, internalContext)
        }
    }

    context.ref(call)
    return captured
}

private fun <A : Arguments, T : Object> opaqueReferenceFor(
    field: RootObjectField<*, T, A>,
    arguments: A,
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
