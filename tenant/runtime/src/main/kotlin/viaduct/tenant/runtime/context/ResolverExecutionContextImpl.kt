package viaduct.tenant.runtime.context

import viaduct.api.context.ResolverExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.InternalContext
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.api.types.NodeObject
import viaduct.api.types.Query

sealed class ResolverExecutionContextImpl<Q : Query>(
    baseData: InternalContext,
    protected val engineExecutionContextWrapper: EngineExecutionContextWrapper,
) : ResolverExecutionContext<Q>, ExecutionContextImpl(baseData) {
    @Suppress("UNCHECKED_CAST")
    override suspend fun query(
        selections: String,
        variables: Map<String, Any?>
    ): Q {
        val queryType = reflectionLoader.reflectionFor(schema.schema.queryType.name) as Type<Q>
        return query(selectionsFor(queryType, selections, variables))
    }

    private suspend fun <T : Query> query(selections: SelectionSet<T>) = engineExecutionContextWrapper.query(this, "query", selections)

    override fun <T : CompositeOutput> selectionsFor(
        type: Type<T>,
        selections: String,
        variables: Map<String, Any?>
    ) = engineExecutionContextWrapper.selectionsFor(type, selections, variables)

    override fun <T : NodeObject> nodeFor(id: GlobalID<T>) = engineExecutionContextWrapper.nodeFor(this, id)

    override fun <T : NodeObject> globalIDStringFor(
        type: Type<T>,
        internalID: String,
    ) = globalIDCodec.serialize(type.name, internalID)
}
