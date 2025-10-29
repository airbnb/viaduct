package viaduct.tenant.runtime.context

import viaduct.api.globalid.GlobalID
import viaduct.api.internal.InternalContext
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Mutation
import viaduct.api.types.NodeObject
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.engine.api.ViaductSchema
import viaduct.engine.runtime.mocks.ContextMocks

/**
 * Base class providing common utilities for context integration tests.
 * Provides helpers to create EngineExecutionContextWrapper implementations that mock
 * query/mutation calls to return constant objects.
 */
abstract class ContextTestBase {
    protected object Obj : Object

    protected object Q : Query

    protected object Args : Arguments

    @Suppress("UNCHECKED_CAST")
    protected val noSelections = SelectionSet.NoSelections as SelectionSet<CompositeOutput>

    /**
     * Creates an EngineExecutionContextWrapper that returns mock objects for query/mutation calls.
     *
     * @param schema The schema to use for the underlying engine context
     * @param queryMock Optional mock to return for query() calls
     * @param mutationMock Optional mock to return for mutation() calls
     */
    protected fun createMockingWrapper(
        schema: ViaductSchema,
        queryMock: Query? = null,
        mutationMock: Mutation? = null
    ): EngineExecutionContextWrapper {
        val contextMocks = ContextMocks(schema)
        val realWrapper = EngineExecutionContextWrapperImpl(contextMocks.engineExecutionContext)

        return object : EngineExecutionContextWrapper {
            override val engineExecutionContext = contextMocks.engineExecutionContext

            override suspend fun <T : Query> query(
                ctx: InternalContext,
                resolverId: String,
                selections: SelectionSet<T>
            ): T {
                if (queryMock != null) {
                    @Suppress("UNCHECKED_CAST")
                    return queryMock as T
                }
                return realWrapper.query(ctx, resolverId, selections)
            }

            override suspend fun <T : Mutation> mutation(
                ctx: InternalContext,
                resolverId: String,
                selections: SelectionSet<T>
            ): T {
                if (mutationMock != null) {
                    @Suppress("UNCHECKED_CAST")
                    return mutationMock as T
                }
                return realWrapper.mutation(ctx, resolverId, selections)
            }

            override fun <T : NodeObject> nodeFor(
                ctx: InternalContext,
                globalID: GlobalID<T>
            ): T = realWrapper.nodeFor(ctx, globalID)

            override fun <T : CompositeOutput> selectionsFor(
                type: Type<T>,
                selections: String,
                variables: Map<String, Any?>
            ): SelectionSet<T> = realWrapper.selectionsFor(type, selections, variables)
        }
    }
}
