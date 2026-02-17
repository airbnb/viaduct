package viaduct.api.mocks

import graphql.schema.GraphQLObjectType
import viaduct.api.context.ExecutionContext
import viaduct.api.context.FieldExecutionContext
import viaduct.api.context.MutationFieldExecutionContext
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.context.SelectiveNodeExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ReflectionLoader
import viaduct.api.internal.select.SelectionSetFactory
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Mutation
import viaduct.api.types.NodeCompositeOutput
import viaduct.api.types.NodeObject
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.engine.api.NodeEngineObjectData
import viaduct.engine.api.NodeReference
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.mocks.MockSchema
import viaduct.service.api.spi.GlobalIDCodec
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
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
    override val type: GraphQLObjectType,
) : NodeEngineObjectData, NodeReference {
    override suspend fun fetch(selection: String): Any = idOrThrow(selection)

    override suspend fun fetchOrNull(selection: String): Any = idOrThrow(selection)

    override suspend fun fetchSelections(): Iterable<String> {
        throw UnsupportedOperationException()
    }

    private fun idOrThrow(selection: String): Any {
        if (selection == "id") {
            return id
        }
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
val InternalContext.resolverExecutionContext: ResolverExecutionContext<Query>
    get() =
        this as? ResolverExecutionContext<Query> ?: MockResolverExecutionContext<Query>(this)

class MockInternalContext(
    override val schema: ViaductSchema,
    override val globalIDCodec: GlobalIDCodec = GlobalIDCodecDefault,
    override val reflectionLoader: ReflectionLoader = mockReflectionLoader("viaduct.api.grts")
) : InternalContext {
    override fun <T : NodeCompositeOutput> deserializeGlobalID(serialized: String): GlobalID<T> {
        val (typeName, localID) = globalIDCodec.deserialize(serialized)
        @Suppress("UNCHECKED_CAST")
        val type = reflectionLoader.reflectionFor(typeName) as Type<T>
        return GlobalIDImpl(type, localID)
    }

    companion object {
        fun create(
            schema: ViaductSchema,
            grtPackage: String = "viaduct.api.grts",
            classLoader: ClassLoader = ClassLoader.getSystemClassLoader()
        ): MockInternalContext = MockInternalContext(schema, GlobalIDCodecDefault, mockReflectionLoader(grtPackage, classLoader))
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
        fun create(
            schema: ViaductSchema = MockSchema.minimal,
            classLoader: ClassLoader = ClassLoader.getSystemClassLoader()
        ): MockResolverExecutionContext<Query> = MockResolverExecutionContext(MockInternalContext.create(schema, classLoader = classLoader))
    }
}

open class MockResolverExecutionContext<Q : Query>(
    internalContext: InternalContext,
    val queryResults: PrebakedResults<Query> = EmptyPrebakedResults<Query>(),
    private val selectionSetFactory: SelectionSetFactory? = null,
) : MockExecutionContext(internalContext), ResolverExecutionContext<Q> {
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

    private suspend fun <T : Query> query(selections: SelectionSet<T>): T {
        @Suppress("UNCHECKED_CAST")
        return queryResults.get(selections as SelectionSet<Query>) as T
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun query(
        selections: String,
        variables: Map<String, Any?>
    ): Q {
        val selectionSet = selectionsFor(
            reflectionLoader.reflectionFor(schema.schema.queryType.name) as Type<Query>,
            selections,
            variables
        )
        return query(selectionSet) as Q
    }

    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun <T : NodeObject> nodeFor(globalID: GlobalID<T>): T {
        val id = globalIDCodec.serialize(globalID.type.name, globalID.internalID)
        val graphqlObjectType = schema.schema.getObjectType(globalID.type.name)
        return MockNodeEngineObjectData(id, graphqlObjectType).toObjectGRT(this, globalID.type.kcls)
    }

    override fun <T : NodeObject> globalIDStringFor(
        type: Type<T>,
        internalID: String
    ): String {
        return globalIDCodec.serialize(type.name, internalID)
    }

    companion object {
        fun create(
            schema: ViaductSchema = MockSchema.minimal,
            classLoader: ClassLoader = ClassLoader.getSystemClassLoader()
        ): MockResolverExecutionContext<Query> = MockResolverExecutionContext(MockInternalContext.create(schema, classLoader = classLoader))
    }
}

@Suppress("DIFFERENT_NAMES_FOR_THE_SAME_PARAMETER_IN_SUPERTYPES")
class MockFieldExecutionContext<T : Object, Q : Query, A : Arguments, O : CompositeOutput>(
    override val objectValue: T,
    override val queryValue: Q,
    override val arguments: A,
    override val requestContext: Any?,
    private val selectionsValue: SelectionSet<O>,
    internalContext: InternalContext,
    queryResults: PrebakedResults<Query> = EmptyPrebakedResults<Query>(),
    selectionSetFactory: SelectionSetFactory? = null,
) : MockResolverExecutionContext<Q>(internalContext, queryResults, selectionSetFactory),
    FieldExecutionContext<T, Q, A, O> {
    override fun selections() = selectionsValue

    // In mock contexts, sync and lazy values are the same
    override suspend fun getObjectValue(): T = objectValue

    override suspend fun getQueryValue(): Q = queryValue
}

@Suppress("DIFFERENT_NAMES_FOR_THE_SAME_PARAMETER_IN_SUPERTYPES")
class MockMutationFieldExecutionContext<Q : Query, M : Mutation, A : Arguments, O : CompositeOutput>(
    override val queryValue: Q,
    override val arguments: A,
    override val requestContext: Any?,
    private val selectionsValue: SelectionSet<O>,
    internalContext: InternalContext,
    queryResults: PrebakedResults<Query> = EmptyPrebakedResults<Query>(),
    private val mutationResults: PrebakedResults<Mutation> = EmptyPrebakedResults<Mutation>(),
    selectionSetFactory: SelectionSetFactory? = null,
) : MockResolverExecutionContext<Q>(internalContext, queryResults, selectionSetFactory),
    MutationFieldExecutionContext<Q, M, A, O> {
    override fun selections() = selectionsValue

    // In mock contexts, sync and lazy values are the same
    override suspend fun getQueryValue(): Q = queryValue

    private suspend fun <T : Mutation> mutation(selections: SelectionSet<T>): T {
        @Suppress("UNCHECKED_CAST")
        return mutationResults.get(selections as SelectionSet<Mutation>) as T
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun mutation(
        selections: String,
        variables: Map<String, Any?>
    ): M {
        val mutationType = reflectionLoader.reflectionFor(schema.schema.mutationType.name) as Type<M>
        return mutation(selectionsFor(mutationType, selections, variables))
    }
}

@Suppress("DIFFERENT_NAMES_FOR_THE_SAME_PARAMETER_IN_SUPERTYPES")
class MockNodeExecutionContext<T : NodeObject>(
    override val id: GlobalID<T>,
    override val requestContext: Any? = null,
    private val selectionsValue: SelectionSet<T>,
    internalContext: InternalContext,
    queryResults: PrebakedResults<Query> = EmptyPrebakedResults<Query>(),
    selectionSetFactory: SelectionSetFactory? = null,
) : MockResolverExecutionContext<Query>(internalContext, queryResults, selectionSetFactory),
    SelectiveNodeExecutionContext<T> {
    override fun selections() = selectionsValue
}
