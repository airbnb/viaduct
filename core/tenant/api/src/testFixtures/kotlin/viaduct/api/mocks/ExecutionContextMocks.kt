@file:OptIn(ExperimentalApi::class)

package viaduct.api.mocks

import graphql.schema.GraphQLObjectType
import viaduct.api.context.ConnectionFieldExecutionContext
import viaduct.api.context.ExecutionContext
import viaduct.api.context.FieldExecutionContext
import viaduct.api.context.MutationFieldExecutionContext
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.context.SelectiveFieldExecutionContext
import viaduct.api.context.SelectiveNodeExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.DefaultGRTConvFactory
import viaduct.api.internal.GRTConvFactory
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ReflectionLoader
import viaduct.api.internal.select.SelectionSetFactory
import viaduct.api.reflect.RootObjectField
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Connection
import viaduct.api.types.ConnectionArguments
import viaduct.api.types.Mutation
import viaduct.api.types.NodeCompositeOutput
import viaduct.api.types.NodeObject
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi
import viaduct.engine.api.NodeEngineObjectData
import viaduct.engine.api.NodeReference
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.mocks.MockSchema
import viaduct.service.api.spi.GlobalIDCodec
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.tenant.runtime.toObjectGRT

interface PrebakedResults<T : CompositeOutput> {
    fun get(selections: SelectionSet<T>): T
}

private class EmptyPrebakedResults<T : CompositeOutput> : PrebakedResults<T> {
    override fun get(selections: SelectionSet<T>): T {
        throw UnsupportedOperationException("No pre-baked results were provided.")
    }
}

/**
 * Provides pre-baked return values for [ResolverExecutionContext.rootFieldRef] calls
 * made by a resolver under test. Implementations are responsible for deciding which
 * value to return for a given [RootObjectField] / [Arguments] pair, and may also use
 * this hook to capture the arguments the resolver invoked the field with.
 */
interface PrebakedRootFieldRefResults {
    fun <A : Arguments, T : Object> get(
        field: RootObjectField<*, T, A>,
        arguments: A
    ): T
}

object EmptyPrebakedRootFieldRefResults : PrebakedRootFieldRefResults {
    override fun <A : Arguments, T : Object> get(
        field: RootObjectField<*, T, A>,
        arguments: A
    ): T {
        throw UnsupportedOperationException("No pre-baked results were provided for rootFieldRef.")
    }
}

/**
 * A [PrebakedRootFieldRefResults] that records every `rootFieldRef` invocation made by a
 * resolver under test, returning configured stub values in declaration order.
 *
 * Stubs are matched by [RootObjectField.pathFromQueryRoot] and consumed in the order they
 * were declared. Multiple stubs for the same field are returned in sequence — declaring
 * `(foo to a, foo to b)` makes the first call to `foo` return `a` and the second return
 * `b`. Calling a field more times than there are stubs throws — this is intentional, so
 * that tests are explicit about expected call counts.
 *
 * Use [calls] to assert on the `(path, arguments)` tuples the resolver invoked.
 */
class RecordingRootFieldRefResults(
    private val stubs: List<Pair<List<String>, Object>>,
) : PrebakedRootFieldRefResults {
    data class Call(val path: List<String>, val arguments: Arguments)

    private val _calls: MutableList<Call> = mutableListOf()
    val calls: List<Call> get() = _calls.toList()

    private val callCountsByPath: MutableMap<List<String>, Int> = mutableMapOf()

    @Suppress("UNCHECKED_CAST")
    override fun <A : Arguments, T : Object> get(
        field: RootObjectField<*, T, A>,
        arguments: A
    ): T {
        val path = field.pathFromQueryRoot
        _calls.add(Call(path, arguments))
        val stubsForPath = stubs.filter { it.first == path }.map { it.second }
        if (stubsForPath.isEmpty()) {
            throw IllegalArgumentException(
                "No pre-baked rootFieldRef result for path '${path.joinToString(".")}'. " +
                    "Available paths: ${stubs.map { it.first.joinToString(".") }.distinct()}"
            )
        }
        val callIndex = callCountsByPath.getOrDefault(path, 0)
        if (callIndex >= stubsForPath.size) {
            throw IllegalStateException(
                "Resolver called rootFieldRef for path '${path.joinToString(".")}' " +
                    "${callIndex + 1} times, but only ${stubsForPath.size} stub(s) were configured."
            )
        }
        callCountsByPath[path] = callIndex + 1
        return stubsForPath[callIndex] as T
    }

    companion object {
        fun of(vararg entries: Pair<RootObjectField<*, *, *>, Object>): RecordingRootFieldRefResults = RecordingRootFieldRefResults(entries.map { (field, value) -> field.pathFromQueryRoot to value })
    }
}

/**
 * Minimal [NodeEngineObjectData] for use in tests.
 *
 * Only the `"id"` field is supported; any other field selection throws
 * [UnsupportedOperationException]. Useful as a lightweight stand-in when a resolved node
 * object needs to be represented without a full engine pipeline.
 */
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
@OptIn(InternalApi::class)
val InternalContext.executionContext: ExecutionContext
    get() =
        this as? ExecutionContext ?: MockExecutionContext(this)

/**
 * Re-project this InternalContext back to an [ResolverExecutionContext].
 * If this InternalContext was originally extracted from an ExecutionContext,
 * then the original ExecutionContext will be returned. Otherwise, a minimal
 * ExecutionContext will be returned.
 */
@OptIn(InternalApi::class)
val InternalContext.resolverExecutionContext: ResolverExecutionContext<Query>
    @Suppress("UNCHECKED_CAST")
    get() =
        this as? ResolverExecutionContext<Query> ?: MockResolverExecutionContext<Query>(this)

/**
 * Test-only [InternalContext] backed by explicit dependencies.
 *
 * Allows tests to supply a [ViaductSchema], an optional [GlobalIDCodec], an optional
 * [ReflectionLoader], and an optional [GRTConvFactory] without requiring a full Viaduct
 * service stack. Use [Companion.create] to construct one from a schema and a GRT package name.
 */
@OptIn(InternalApi::class)
class MockInternalContext(
    override val schema: ViaductSchema,
    override val globalIDCodec: GlobalIDCodec = GlobalIDCodecDefault,
    override val reflectionLoader: ReflectionLoader = mockReflectionLoader("viaduct.api.grts"),
    override val grtConvFactory: GRTConvFactory = DefaultGRTConvFactory,
) : InternalContext {
    override fun <T : NodeCompositeOutput> deserializeGlobalID(serialized: String): GlobalID<T> {
        val (typeName, localID) = globalIDCodec.deserialize(serialized)
        @Suppress("UNCHECKED_CAST")
        val type = reflectionLoader.reflectionFor(typeName) as Type<T>
        return GlobalID(type, localID)
    }

    companion object {
        fun create(
            schema: ViaductSchema,
            grtPackage: String = "viaduct.api.grts",
            classLoader: ClassLoader = ClassLoader.getSystemClassLoader()
        ): MockInternalContext = MockInternalContext(schema, GlobalIDCodecDefault, mockReflectionLoader(grtPackage, classLoader))
    }
}

/**
 * Test-only [ExecutionContext] that wraps a [MockInternalContext].
 *
 * Provides a concrete [ExecutionContext] for tests that need to call resolver code without a
 * running engine. [globalIDFor] returns a plain [GlobalID] with no encoding. Use
 * [Companion.create] to build an instance from an optional [ViaductSchema].
 */
@OptIn(InternalApi::class)
open class MockExecutionContext(
    internalContext: InternalContext,
    override val requestContext: Any? = null
) : ExecutionContext, InternalContext by internalContext {
    override fun <T : NodeObject> globalIDFor(
        type: Type<T>,
        internalID: String
    ): GlobalID<T> {
        return GlobalID(type, internalID)
    }

    companion object {
        fun create(
            schema: ViaductSchema = MockSchema.minimal,
            classLoader: ClassLoader = ClassLoader.getSystemClassLoader()
        ): MockResolverExecutionContext<Query> = MockResolverExecutionContext(MockInternalContext.create(schema, classLoader = classLoader))
    }
}

/**
 * Test-only [ResolverExecutionContext] that extends [MockExecutionContext] with query and node
 * resolution support.
 *
 * Optionally accepts [queryResults] (a [PrebakedResults] provider) and a [selectionSetFactory]
 * so that tests can control what [query] returns without invoking the engine. If [queryResults]
 * is omitted, calls to [query] throw. Use [Companion.create] for the common minimal case.
 */
@OptIn(InternalApi::class)
open class MockResolverExecutionContext<Q : Query>(
    internalContext: InternalContext,
    val queryResults: PrebakedResults<Query> = EmptyPrebakedResults<Query>(),
    private val selectionSetFactory: SelectionSetFactory? = null,
    val rootFieldRefResults: PrebakedRootFieldRefResults = EmptyPrebakedRootFieldRefResults,
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

    private fun <T : Query> query(selections: SelectionSet<T>): T {
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
    override fun <T : NodeObject> nodeRef(globalID: GlobalID<T>): T {
        val id = globalIDCodec.serialize(globalID.type.name, globalID.internalID)
        val graphqlObjectType = schema.schema.getObjectType(globalID.type.name)
        return MockNodeEngineObjectData(id, graphqlObjectType).toObjectGRT(this, globalID.type.kcls)
    }

    override fun <A : Arguments, BR : Object> rootFieldRef(
        field: RootObjectField<*, BR, A>,
        arguments: A
    ): BR = rootFieldRefResults.get(field, arguments)

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

/**
 * Test-only [FieldExecutionContext] carrying pre-built object, query, argument, and selection-set
 * values.
 *
 * Allows unit tests to drive field-resolver logic directly by supplying all context values
 * explicitly. [getObjectValue] and [getQueryValue] return the provided values synchronously;
 * [selections] returns the provided [SelectionSet].
 */
@Suppress("DIFFERENT_NAMES_FOR_THE_SAME_PARAMETER_IN_SUPERTYPES")
@OptIn(InternalApi::class)
class MockFieldExecutionContext<O : Object, Q : Query, A : Arguments, R : CompositeOutput>(
    val objectValue: O,
    val queryValue: Q,
    override val arguments: A,
    override val requestContext: Any?,
    private val selectionsValue: SelectionSet<R>,
    internalContext: InternalContext,
    queryResults: PrebakedResults<Query> = EmptyPrebakedResults<Query>(),
    selectionSetFactory: SelectionSetFactory? = null,
    rootFieldRefResults: PrebakedRootFieldRefResults = EmptyPrebakedRootFieldRefResults,
) : MockResolverExecutionContext<Q>(internalContext, queryResults, selectionSetFactory, rootFieldRefResults),
    FieldExecutionContext<O, Q, A, R>,
    SelectiveFieldExecutionContext<R> {
    override fun selections() = selectionsValue

    // In mock contexts, sync and lazy values are the same
    override suspend fun getObjectValue(): O = objectValue

    override suspend fun getQueryValue(): Q = queryValue
}

/**
 * Test-only [ConnectionFieldExecutionContext] carrying pre-built object, query, connection
 * arguments, and selection-set values.
 *
 * The connection-field variant of [MockFieldExecutionContext], used when the field under test
 * returns a Relay connection type.
 */
@Suppress("DIFFERENT_NAMES_FOR_THE_SAME_PARAMETER_IN_SUPERTYPES")
@OptIn(InternalApi::class)
class MockConnectionFieldExecutionContext<O : Object, Q : Query, A : ConnectionArguments, R : Connection<*, *>>(
    val objectValue: O,
    val queryValue: Q,
    override val arguments: A,
    override val requestContext: Any?,
    private val selectionsValue: SelectionSet<R>,
    internalContext: InternalContext,
    queryResults: PrebakedResults<Query> = EmptyPrebakedResults<Query>(),
    selectionSetFactory: SelectionSetFactory? = null,
    rootFieldRefResults: PrebakedRootFieldRefResults = EmptyPrebakedRootFieldRefResults,
) : MockResolverExecutionContext<Q>(internalContext, queryResults, selectionSetFactory, rootFieldRefResults),
    ConnectionFieldExecutionContext<O, Q, A, R>,
    SelectiveFieldExecutionContext<R> {
    override fun selections() = selectionsValue

    // In mock contexts, sync and lazy values are the same
    override suspend fun getObjectValue(): O = objectValue

    override suspend fun getQueryValue(): Q = queryValue
}

/**
 * Test-only [MutationFieldExecutionContext] carrying pre-built query, mutation, argument, and
 * selection-set values.
 *
 * Allows unit tests for mutation resolvers to supply all context values directly. Optionally
 * accepts [mutationResults] to control what [mutation] returns without invoking the engine.
 */
@Suppress("DIFFERENT_NAMES_FOR_THE_SAME_PARAMETER_IN_SUPERTYPES")
@OptIn(InternalApi::class)
class MockMutationFieldExecutionContext<Q : Query, M : Mutation, A : Arguments, R : CompositeOutput>(
    val queryValue: Q,
    override val arguments: A,
    override val requestContext: Any?,
    private val selectionsValue: SelectionSet<R>,
    internalContext: InternalContext,
    queryResults: PrebakedResults<Query> = EmptyPrebakedResults<Query>(),
    private val mutationResults: PrebakedResults<Mutation> = EmptyPrebakedResults<Mutation>(),
    selectionSetFactory: SelectionSetFactory? = null,
    rootFieldRefResults: PrebakedRootFieldRefResults = EmptyPrebakedRootFieldRefResults,
) : MockResolverExecutionContext<Q>(internalContext, queryResults, selectionSetFactory, rootFieldRefResults),
    MutationFieldExecutionContext<Q, M, A, R>,
    SelectiveFieldExecutionContext<R> {
    override fun selections() = selectionsValue

    // In mock contexts, sync and lazy values are the same
    override suspend fun getQueryValue(): Q = queryValue

    private fun <T : Mutation> mutation(selections: SelectionSet<T>): T {
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

/**
 * Test-only [SelectiveNodeExecutionContext] carrying a pre-built [GlobalID] and [SelectionSet].
 *
 * Allows unit tests for node resolvers to supply the node ID and pre-computed selections
 * without running a real engine. [selections] returns the provided [SelectionSet] directly.
 */
@Suppress("DIFFERENT_NAMES_FOR_THE_SAME_PARAMETER_IN_SUPERTYPES")
@OptIn(InternalApi::class)
class MockNodeExecutionContext<R : NodeObject>(
    override val id: GlobalID<R>,
    override val requestContext: Any? = null,
    private val selectionsValue: SelectionSet<R>,
    internalContext: InternalContext,
    queryResults: PrebakedResults<Query> = EmptyPrebakedResults<Query>(),
    selectionSetFactory: SelectionSetFactory? = null,
    rootFieldRefResults: PrebakedRootFieldRefResults = EmptyPrebakedRootFieldRefResults,
) : MockResolverExecutionContext<Query>(internalContext, queryResults, selectionSetFactory, rootFieldRefResults),
    SelectiveNodeExecutionContext<R> {
    override fun selections() = selectionsValue
}
