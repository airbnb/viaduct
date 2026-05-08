@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package viaduct.api.testing

import java.lang.reflect.InvocationTargetException
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import viaduct.api.ConnectionResolverBase
import viaduct.api.FieldResolverBase
import viaduct.api.FieldValue
import viaduct.api.MutationResolverBase
import viaduct.api.NodeResolverBase
import viaduct.api.context.ExecutionContext
import viaduct.api.context.VariablesProviderContext
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ObjectBase
import viaduct.api.internal.ObjectBaseTestHelpers
import viaduct.api.internal.internal
import viaduct.api.internal.select.SelectionSetFactory
import viaduct.api.mocks.MockExecutionContext
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.PrebakedResults
import viaduct.api.mocks.mockReflectionLoader
import viaduct.api.reflect.Type
import viaduct.api.testing.spec.FieldBatchResolverSpec
import viaduct.api.testing.spec.FieldResolverSpec
import viaduct.api.testing.spec.MutationResolverSpec
import viaduct.api.testing.spec.NodeBatchResolverSpec
import viaduct.api.testing.spec.NodeResolverSpec
import viaduct.api.testing.spec.base.BaseResolverSpec
import viaduct.api.testing.spec.base.buildContextMutationMap
import viaduct.api.testing.spec.base.buildContextQueryMap
import viaduct.api.types.Arguments
import viaduct.api.types.Mutation
import viaduct.api.types.NodeObject
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi
import viaduct.apiannotations.VisibleForTest
import viaduct.engine.SchemaFactory
import viaduct.engine.api.ViaductSchema
import viaduct.engine.runtime.execution.DefaultCoroutineInterop
import viaduct.engine.runtime.select.EngineSelectionSetFactoryImpl
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.tenant.runtime.select.SelectionSetFactoryImpl

/**
 * Base class for Viaduct resolver tests. Use [runFieldResolver] for non-mutation resolvers
 * or [runMutationFieldResolver] for mutation resolvers to execute the resolver you want to test.
 * These utility functions call the resolve function with the correct Context parameter.
 *
 * Signatures are typed on [FieldResolverBase] / [MutationResolverBase] / [NodeResolverBase], so
 * the lambda receiver's `objectValue`/`queryValue`/`arguments` are checked at compile time.
 * [ConnectionResolverBase] extends [FieldResolverBase], so connection resolvers go through the
 * same `runFieldResolver` entry point.
 *
 * Context construction is fully delegated to each [BaseResolverSpec] subclass —
 * [ResolverTestBase] only runs the resolver and forwards [InternalContext] / [SelectionSetFactory].
 *
 * ### Usage
 *
 *     class MyResolverTest : ResolverTestBase() {
 *         override fun getSchema() = mySchema
 *
 *         @Test
 *         fun test() = runBlocking {
 *             val result = runFieldResolver(MyResolver()) {
 *                 objectValue = myObject
 *                 arguments = myArgs
 *             }
 *             assertEquals(expected, result)
 *         }
 *     }
 */
@ExperimentalApi
@OptIn(VisibleForTest::class, InternalApi::class)
abstract class ResolverTestBase {
    /**
     * An ExecutionContext that can be used to construct a builder, e.g. Foo.Builder(context).
     * This cannot be passed as the `ctx` param to the `resolve` function of a resolver, since
     * that's a subclass unique to the resolver.
     */
    val context: ExecutionContext by lazy {
        val rl = mockReflectionLoader("viaduct.api.grts")
        val internal = MockInternalContext(getSchema(), GlobalIDCodecDefault, rl)
        MockExecutionContext(internal)
    }

    /**
     * Subclasses must provide the schema instance. This allows different implementations
     * to load the schema in their preferred way (e.g., from resources, test data, etc.)
     */
    private fun getSchema(): ViaductSchema = SchemaFactory(DefaultCoroutineInterop).fromResources()

    private val selectionSetFactory: SelectionSetFactory by lazy { mkSelectionSetFactory() }

    /**
     * Calls the resolve function for the given field [resolver].
     * Use this for non-mutation field resolvers (including connection resolvers, which
     * extend [FieldResolverBase] via [ConnectionResolverBase]). For mutation field resolvers,
     * use [runMutationFieldResolver] instead.
     */
    @ExperimentalApi
    suspend fun <O : Object, Q : Query, A : Arguments, R> runFieldResolver(
        resolver: FieldResolverBase<O, Q, A, R>,
        block: FieldResolverSpec<O, Q, A>.() -> Unit = {}
    ): R {
        val inputs = FieldResolverSpec<O, Q, A>().apply(block)
        val ctx = inputs.createContext(resolver::class.java, context.internal, selectionSetFactory)
        return resolver.callResolve(ctx)
    }

    /**
     * Calls the resolve function for the given mutation field [resolver].
     */
    @ExperimentalApi
    suspend fun <Q : Query, M : Mutation, A : Arguments, R> runMutationFieldResolver(
        resolver: MutationResolverBase<Q, M, A, R>,
        block: MutationResolverSpec<Q, M, A>.() -> Unit = {}
    ): R {
        val inputs = MutationResolverSpec<Q, M, A>().apply(block)
        val ctx = inputs.createContext(resolver::class.java, context.internal, selectionSetFactory)
        return resolver.callResolve(ctx)
    }

    /**
     * Calls the batchResolve function for the given field [resolver].
     */
    @ExperimentalApi
    suspend fun <O : Object, Q : Query, A : Arguments, R> runFieldBatchResolver(
        resolver: FieldResolverBase<O, Q, A, R>,
        block: FieldBatchResolverSpec<O, Q>.() -> Unit = {}
    ): List<FieldValue<R>> {
        val inputs = FieldBatchResolverSpec<O, Q>().apply(block)
        val contexts = inputs.createContexts(resolver::class.java, context.internal, selectionSetFactory)
        return resolver.callBatchResolve(contexts)
    }

    /**
     * Calls the resolve function for the given node [resolver].
     * [viaduct.api.testing.spec.NodeResolverSpec.id] is required.
     */
    @ExperimentalApi
    suspend fun <T : NodeObject> runNodeResolver(
        resolver: NodeResolverBase<T>,
        block: NodeResolverSpec<T>.() -> Unit
    ): T {
        val inputs = NodeResolverSpec<T>().apply(block)
        val ctx = inputs.createContext(resolver::class.java, context.internal, selectionSetFactory)
        return resolver.callResolve(ctx)
    }

    /**
     * Calls the batchResolve function for the given node [resolver].
     */
    @ExperimentalApi
    suspend fun <T : NodeObject> runNodeBatchResolver(
        resolver: NodeResolverBase<T>,
        block: NodeBatchResolverSpec<T>.() -> Unit = {}
    ): List<FieldValue<T>> {
        val inputs = NodeBatchResolverSpec<T>().apply(block)
        val contexts = inputs.createContexts(resolver::class.java, context.internal, selectionSetFactory)
        return resolver.callBatchResolve(contexts)
    }

    fun <T : NodeObject> globalIDFor(
        type: Type<T>,
        internalID: String
    ): GlobalID<T> = context.globalIDFor(type, internalID)

    fun buildContextQueryMap(contextQueries: List<Query>): PrebakedResults<Query> = buildContextQueryMap(contextQueries, context.internal, selectionSetFactory)

    fun buildContextMutationMap(contextMutations: List<Mutation>): PrebakedResults<Mutation> = buildContextMutationMap(contextMutations, context.internal, selectionSetFactory)

    fun <T : Arguments> createVariablesProviderContext(arguments: T): VariablesProviderContext<T> =
        object : VariablesProviderContext<T>, ExecutionContext by context, InternalContext by context.internal {
            override val arguments: T = arguments
        }

    protected fun mkSelectionSetFactory(): SelectionSetFactory = SelectionSetFactoryImpl(EngineSelectionSetFactoryImpl(getSchema()))

    fun <R, T : ObjectBase.Builder<R>> T.put(
        name: String,
        value: Any?,
        alias: String
    ): T = ObjectBaseTestHelpers.putWithAlias(builder = this, name = name, value = value, alias = alias)

    @Suppress("UNCHECKED_CAST")
    private suspend fun <R> Any.callResolve(ctx: Any): R =
        try {
            this::class.declaredFunctions.first { it.name == "resolve" }
                .callSuspend(this, ctx) as R
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <R> Any.callBatchResolve(contexts: List<*>): R =
        try {
            this::class.declaredFunctions.first { it.name == "batchResolve" }
                .callSuspend(this, contexts) as R
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
}
