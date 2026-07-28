package viaduct.api.internal

import viaduct.api.context.BaseFieldExecutionContext
import viaduct.api.context.NodeExecutionContext
import viaduct.apiannotations.InternalApi

@InternalApi
interface BaseUnbatchedFieldResolver {
    suspend fun invokeFieldResolver(context: BaseFieldExecutionContext<*, *, *>): Any?
}

@InternalApi
interface BaseBatchedFieldResolver {
    suspend fun invokeFieldBatchResolver(contexts: List<BaseFieldExecutionContext<*, *, *>>): Any?
}

@InternalApi
interface BaseUnbatchedNodeResolver {
    suspend fun invokeNodeResolver(context: NodeExecutionContext<*>): Any?
}

@InternalApi
interface BaseBatchedNodeResolver {
    suspend fun invokeNodeBatchResolver(contexts: List<NodeExecutionContext<*>>): Any?
}
