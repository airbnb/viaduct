package viaduct.tenant.runtime.context

import viaduct.api.context.ResolverExecutionContext
import viaduct.api.context.RootFieldCall
import viaduct.api.documents.QueryFromAnnotation
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.InternalContext
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.api.types.NodeObject
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.errors.handleFrameworkErrors

sealed class ResolverExecutionContextImpl<Q : Query>(
    baseData: InternalContext,
    protected val engineExecutionContextWrapper: EngineExecutionContextWrapper,
) : ResolverExecutionContext<Q>, ExecutionContextImpl(baseData) {
    @Deprecated("This API is not supported and will be deleted. Use the GraphQLOperation-based query(operation, variables) instead.")
    override suspend fun query(
        selections: String,
        variables: Map<String, Any?>
    ): Q = query(selectionsFor(queryType(), selections, variables))

    override suspend fun query(
        operation: QueryFromAnnotation,
        variables: Map<String, Any?>
    ): Q = query(engineExecutionContextWrapper.selectionsForOperation(queryType(), operation.operationText, variables))

    @Suppress("UNCHECKED_CAST")
    private fun queryType(): Type<Q> = reflectionLoader.reflectionFor(schema.schema.queryType.name) as Type<Q>

    private suspend fun <T : Query> query(selections: SelectionSet<T>) = engineExecutionContextWrapper.query(this, selections)

    override fun <T : CompositeOutput> selectionsFor(
        type: Type<T>,
        selections: String,
        variables: Map<String, Any?>
    ) = engineExecutionContextWrapper.selectionsFor(type, selections, variables)

    override fun <T : NodeObject> nodeRef(id: GlobalID<T>) = engineExecutionContextWrapper.nodeRef(this, id)

    override fun <T : Object> ref(call: RootFieldCall<T>): T = engineExecutionContextWrapper.rootFieldRef(this, call.field(), call.arguments(this))

    override fun <T : NodeObject> globalIDStringFor(
        type: Type<T>,
        internalID: String,
    ) = handleFrameworkErrors("globalIDStringFor(${type.name})") { globalIDCodec.serialize(type.name, internalID) }
}
