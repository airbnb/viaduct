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
 * Connects a [ReferenceSpy] to a mock resolver context.
 *
 * Teaches the spy how to read an expected [RootFieldCall], and returns the factory that records the
 * references the resolver creates. The resolver always gets an opaque reference, never a stubbed value.
 */
internal fun referenceSpyResultsOf(
    referenceSpy: ReferenceSpy,
    internalContext: InternalContext,
): PrebakedRootFieldRefResults {
    referenceSpy.attach { call -> invocationFor(call, internalContext) }

    return object : PrebakedRootFieldRefResults {
        override fun <A : Arguments, T : Object> get(
            field: RootObjectField<*, T, A>,
            arguments: A,
        ): T {
            referenceSpy.record(ReferenceInvocation(field.pathFromQueryRoot, arguments))
            return opaqueReferenceFor(field, arguments, internalContext)
        }
    }
}

/**
 * Reads the field and arguments out of an expected [call], so it can be compared against a recorded one.
 */
private fun invocationFor(
    call: RootFieldCall<*>,
    internalContext: InternalContext,
): ReferenceInvocation {
    lateinit var captured: ReferenceInvocation
    val context: ResolverExecutionContext<Query> = MockResolverExecutionContext(
        internalContext = internalContext,
        rootFieldRefResults = object : PrebakedRootFieldRefResults {
            override fun <A : Arguments, T : Object> get(
                field: RootObjectField<*, T, A>,
                arguments: A,
            ): T {
                captured = ReferenceInvocation(field.pathFromQueryRoot, arguments)
                return opaqueReferenceFor(field, arguments, internalContext)
            }
        },
    )

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
 * Unresolved reference returned to the resolver under test.
 *
 * Satisfies the engine's reference contract, but every read throws so a test cannot assert on data
 * that only a real execution would populate.
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
