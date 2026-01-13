package viaduct.tenant.runtime.context

import viaduct.api.globalid.GlobalID
import viaduct.api.internal.InternalContext
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Mutation
import viaduct.api.types.NodeObject
import viaduct.api.types.Query
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.ExecuteSelectionSetOptions
import viaduct.tenant.runtime.select.SelectionSetImpl
import viaduct.tenant.runtime.toObjectGRT

/**
 * A wrapper around [EngineExecutionContext] that converts EEC methods into
 * their Tenant-API equivalents.  We define an interface here for testing
 * purposes: this is a good place to provide mocks to test the other
 * context implementations.
 */
interface EngineExecutionContextWrapper {
    val engineExecutionContext: EngineExecutionContext

    suspend fun <T : Query> query(
        ctx: InternalContext,
        resolverId: String,
        selections: SelectionSet<T>
    ): T

    suspend fun <T : Mutation> mutation(
        ctx: InternalContext,
        resolverId: String,
        selections: SelectionSet<T>
    ): T

    fun <T : NodeObject> nodeFor(
        ctx: InternalContext,
        globalID: GlobalID<T>
    ): T

    fun <T : CompositeOutput> selectionsFor(
        type: Type<T>,
        selections: String,
        variables: Map<String, Any?>
    ): SelectionSet<T>
}

class EngineExecutionContextWrapperImpl(
    override val engineExecutionContext: EngineExecutionContext,
) : EngineExecutionContextWrapper {
    override suspend fun <T : Query> query(
        ctx: InternalContext,
        resolverId: String,
        selections: SelectionSet<T>
    ): T =
        engineExecutionContext.executeSelectionSet(
            resolverId,
            selections.getRawSelectionSet(),
            ExecuteSelectionSetOptions.DEFAULT
        ).toObjectGRT(ctx, selections.type.kcls)

    override suspend fun <T : Mutation> mutation(
        ctx: InternalContext,
        resolverId: String,
        selections: SelectionSet<T>
    ): T =
        engineExecutionContext.executeSelectionSet(
            resolverId,
            selections.getRawSelectionSet(),
            ExecuteSelectionSetOptions.MUTATION
        ).toObjectGRT(ctx, selections.type.kcls)

    private fun SelectionSet<*>.getRawSelectionSet() =
        (this as? SelectionSetImpl)?.rawSelectionSet
            ?: throw IllegalStateException("Unexpected implementation of SelectionSet: $this")

    override fun <T : CompositeOutput> selectionsFor(
        type: Type<T>,
        selections: String,
        variables: Map<String, Any?>
    ): SelectionSet<T> =
        SelectionSetImpl(
            type,
            engineExecutionContext.rawSelectionSetFactory.rawSelectionSet(typeName = type.name, selections, variables)
        )

    override fun <T : NodeObject> nodeFor(
        ctx: InternalContext,
        globalID: GlobalID<T>
    ): T {
        val typeName = globalID.type.name
        val graphqlObjectType = ctx.schema.schema.getObjectType(typeName)
        val id = ctx.globalIDCodec.serialize(globalID.type.name, globalID.internalID)
        val nodeReference = engineExecutionContext.createNodeReference(id, graphqlObjectType)

        return nodeReference.toObjectGRT(ctx, globalID.type.kcls)
    }
}
