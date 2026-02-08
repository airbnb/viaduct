package viaduct.tenant.runtime.execution

import javax.inject.Provider
import kotlin.reflect.KFunction
import kotlin.reflect.full.callSuspend
import viaduct.api.ViaductTenantUsageException
import viaduct.api.internal.NodeResolverBase
import viaduct.api.internal.ObjectBase
import viaduct.api.internal.ReflectionLoader
import viaduct.api.wrapResolveException
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.NodeReference
import viaduct.engine.api.NodeResolverExecutor
import viaduct.engine.api.RawSelectionSet
import viaduct.engine.api.ResolverMetadata
import viaduct.service.api.spi.GlobalIDCodec
import viaduct.tenant.runtime.context.factory.NodeExecutionContextFactory

class NodeUnbatchedResolverExecutorImpl(
    val resolver: Provider<out @JvmSuppressWildcards NodeResolverBase<*>>,
    private val resolveFunction: KFunction<*>,
    override val typeName: String,
    private val globalIDCodec: GlobalIDCodec,
    private val reflectionLoader: ReflectionLoader,
    private val factory: NodeExecutionContextFactory,
    private val resolverName: String,
    override val isSelective: Boolean,
) : NodeResolverExecutor {
    override val metadata = ResolverMetadata.forModern(resolverName)
    override val isBatching = false

    override suspend fun batchResolve(
        selectors: List<NodeResolverExecutor.Selector>,
        context: EngineExecutionContext
    ): Map<NodeResolverExecutor.Selector, Result<EngineObjectData>> {
        // Only handle single selector case because this is an unbatched resolver
        require(selectors.size == 1) { "Unbatched resolver should only receive single selector, got {}".format(selectors.size) }
        val selector = selectors.first()
        return mapOf(selector to runCatching { resolve(selector.id, selector.selections, context) })
    }

    private suspend fun resolve(
        id: String,
        selections: RawSelectionSet,
        context: EngineExecutionContext
    ): EngineObjectData {
        val ctx = factory(context, selections, context.requestContext, id)
        val resolver = resolver.get()
        val result = wrapResolveException(typeName) {
            resolveFunction.callSuspend(resolver, ctx)
        }
        return unwrapNodeResolverResult(result)
    }

    companion object {
        internal fun unwrapNodeResolverResult(result: Any?): EngineObjectData {
            if (result !is ObjectBase) {
                throw IllegalStateException("Unexpected result type that is not a GRT for a node object: $result")
            }

            return when (val eo = result.engineObject) {
                is NodeReference -> throw ViaductTenantUsageException(
                    "NodeReference returned from node resolver. Use a GRT builder instead of Context.nodeFor to construct your node object."
                )

                is EngineObjectData -> eo
                else -> throw IllegalStateException("engineObject has unknown type ${eo.javaClass.name}")
            }
        }
    }
}
