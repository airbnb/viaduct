package viaduct.api.mocks

import graphql.schema.GraphQLObjectType
import viaduct.api.context.ExecutionContext
import viaduct.api.context.FieldExecutionContext
import viaduct.api.context.MutationFieldExecutionContext
import viaduct.api.context.NodeExecutionContext
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.globalid.GlobalIDCodec
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ReflectionLoader
import viaduct.api.internal.select.SelectionSetFactory
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Mutation
import viaduct.api.types.NodeObject
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.NodeEngineObjectData
import viaduct.engine.api.NodeReference
import viaduct.engine.api.RawSelectionSet
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.mocks.MockSchema
import viaduct.tenant.runtime.globalid.GlobalIDImpl
import viaduct.tenant.runtime.toObjectGRT

interface PrebakedResults<T : CompositeOutput> {
    fun get(selections: SelectionSet<T>): T
}

private class EmptyPrebakedResults<T : CompositeOutput> : PrebakedResults<T> {
    override fun get(selections: SelectionSet<T>): T {
        throw UnsupportedOperationException("No pre-baked results were provided.")
    }
}

class MockNodeEngineObjectData(
    override val id: String,
    override val graphQLObjectType: GraphQLObjectType,
) : NodeEngineObjectData, NodeReference {
    override suspend fun fetch(selection: String): Any? = idOrThrow(selection)

    override suspend fun fetchOrNull(selection: String): Any? = idOrThrow(selection)

    override suspend fun fetchSelections(): Iterable<String> {
        throw UnsupportedOperationException()
    }

    private suspend fun idOrThrow(selection: String): Any? {
        if (selection == "id") {
            return id
        }
        throw UnsupportedOperationException()
    }

    override suspend fun resolveData(
        selections: RawSelectionSet,
        context: EngineExecutionContext
    ): Boolean {
        throw UnsupportedOperationException()
    }
}

/**
 * Re-project this InternalContext back to an [ExecutionContext].
 * If this InternalContext was originally extracted from an ExecutionContext,
 * then the original ExecutionContext will be returned. Otherwise, a minimal
 * ExecutionContext will be returned.
 */
val InternalContext.executionContext: ExecutionContext
    get() =
        this as? ExecutionContext ?: MockExecutionContext(this)

/**
 * Re-project this InternalContext back to an [ResolverExecutionContext].
 * If this InternalContext was originally extracted from an ExecutionContext,
 * then the original ExecutionContext will be returned. Otherwise, a minimal
 * ExecutionContext will be returned.
 */
val InternalContext.resolverExecutionContext: ResolverExecutionContext
    get() =
        this as? ResolverExecutionContext ?: MockResolverExecutionContext(this)

class MockInternalContext(
    override val schema: ViaductSchema,
    override val globalIDCodec: GlobalIDCodec = MockGlobalIDCodec(),
    override val reflectionLoader: ReflectionLoader = mockReflectionLoader("viaduct.api.grts")
) : InternalContext {
    companion object {
        fun mk(
            schema: ViaductSchema,
            grtPackage: String = "viaduct.api.grts"
        ): MockInternalContext = MockInternalContext(schema, MockGlobalIDCodec(), mockReflectionLoader(grtPackage))
    }
}

open class MockExecutionContext(
    internalContext: InternalContext,
    override val requestContext: Any? = null
) : ExecutionContext, InternalContext by internalContext {
    override fun <T : NodeObject> globalIDFor(
        type: Type<T>,
        internalID: String
    ): GlobalID<T> {
        return GlobalIDImpl(type, internalID)
    }

    companion object {
        fun mk(schema: ViaductSchema = MockSchema.minimal): MockResolverExecutionContext = MockResolverExecutionContext(MockInternalContext.mk(schema))
    }
}

open class MockResolverExecutionContext(
    internalContext: InternalContext,
    val queryResults: PrebakedResults<Query> = EmptyPrebakedResults<Query>(),
    private val selectionSetFactory: SelectionSetFactory? = null,
) : MockExecutionContext(internalContext), ResolverExecutionContext {
    override fun <T : CompositeOutput> selectionsFor(
        type: Type<T>,
        selections: String,
        variables: Map<String, Any?>
    ): SelectionSet<T> {
        return if (selectionSetFactory != null) {
            selectionSetFactory.selectionsOn(type, selections, variables)
        } else {
            throw UnsupportedOperationException("selectionsFor() requires a selectionSetFactory to be provided")
        }
    }

    override suspend fun <T : Query> query(selections: SelectionSet<T>): T {
        @Suppress("UNCHECKED_CAST")
        return queryResults.get(selections as SelectionSet<Query>) as T
    }

    override fun <T : NodeObject> nodeFor(globalID: GlobalID<T>): T {
        val id = globalIDCodec.serialize(globalID)
        val graphqlObjectType = schema.schema.getObjectType(globalID.type.name)
        return MockNodeEngineObjectData(id, graphqlObjectType).toObjectGRT(this, globalID.type.kcls)
    }

    override fun <T : NodeObject> globalIDStringFor(
        type: Type<T>,
        internalID: String
    ): String {
        return globalIDCodec.serialize(globalIDFor(type, internalID))
    }

    companion object {
        fun mk(schema: ViaductSchema = MockSchema.minimal): MockResolverExecutionContext = MockResolverExecutionContext(MockInternalContext.mk(schema))
    }
}

class MockFieldExecutionContext<T : Object, Q : Query, A : Arguments, O : CompositeOutput>(
    override val objectValue: T,
    override val queryValue: Q,
    override val arguments: A,
    override val requestContext: Any?,
    private val selectionsValue: SelectionSet<O>,
    internalContext: InternalContext,
    queryResults: PrebakedResults<Query> = EmptyPrebakedResults<Query>(),
    selectionSetFactory: SelectionSetFactory? = null,
) : MockResolverExecutionContext(internalContext, queryResults, selectionSetFactory),
    FieldExecutionContext<T, Q, A, O> {
    override fun selections() = selectionsValue
}

class MockMutationFieldExecutionContext<T : Object, Q : Query, A : Arguments, O : CompositeOutput>(
    override val objectValue: T,
    override val queryValue: Q,
    override val arguments: A,
    override val requestContext: Any?,
    private val selectionsValue: SelectionSet<O>,
    internalContext: InternalContext,
    queryResults: PrebakedResults<Query> = EmptyPrebakedResults<Query>(),
    private val mutationResults: PrebakedResults<Mutation> = EmptyPrebakedResults<Mutation>(),
    selectionSetFactory: SelectionSetFactory? = null,
) : MockResolverExecutionContext(internalContext, queryResults, selectionSetFactory),
    MutationFieldExecutionContext<T, Q, A, O> {
    override fun selections() = selectionsValue

    override suspend fun <T : Mutation> mutation(selections: SelectionSet<T>): T {
        @Suppress("UNCHECKED_CAST")
        return mutationResults.get(selections as SelectionSet<Mutation>) as T
    }
}

class MockNodeExecutionContext<T : NodeObject>(
    override val id: GlobalID<T>,
    private val selectionsValue: SelectionSet<T>,
    internalContext: InternalContext,
    queryResults: PrebakedResults<Query> = EmptyPrebakedResults<Query>(),
    selectionSetFactory: SelectionSetFactory? = null,
) : MockResolverExecutionContext(internalContext, queryResults, selectionSetFactory),
    NodeExecutionContext<T> {
    override fun selections() = selectionsValue
}

// Helper function to create a consistent key for SelectionSet lookup
private fun createSelectionSetKey(selectionSet: SelectionSet<*>): String {
    return when (selectionSet) {
        SelectionSet.NoSelections -> "NoSelections"
        else -> selectionSet.toString()
    }
}
