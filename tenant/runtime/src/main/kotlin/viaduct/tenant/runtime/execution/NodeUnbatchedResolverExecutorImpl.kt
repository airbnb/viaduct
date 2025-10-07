package viaduct.tenant.runtime.execution

import javax.inject.Provider
import kotlin.reflect.KFunction
import kotlin.reflect.full.callSuspend
import kotlin.text.get
import viaduct.api.ViaductTenantUsageException
import viaduct.api.globalid.GlobalIDCodec
import viaduct.api.internal.NodeResolverBase
import viaduct.api.internal.ObjectBase
import viaduct.api.internal.ReflectionLoader
import viaduct.api.wrapResolveException
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.NodeReference
import viaduct.engine.api.NodeResolverExecutor
import viaduct.engine.api.RawSelectionSet
import viaduct.tenant.runtime.context.factory.NodeArgs
import viaduct.tenant.runtime.context.factory.NodeExecutionContextFactory
import viaduct.tenant.runtime.internal.InternalContextImpl
import viaduct.tenant.runtime.select.SelectionSetFactoryImpl
import viaduct.tenant.runtime.select.SelectionsLoaderImpl

class NodeUnbatchedResolverExecutorImpl(
    val resolver: Provider<out @JvmSuppressWildcards NodeResolverBase<*>>,
    private val resolveFunction: KFunction<*>,
    override val typeName: String,
    private val globalIDCodec: GlobalIDCodec,
    private val reflectionLoader: ReflectionLoader,
    private val factory: NodeExecutionContextFactory,
) : NodeResolverExecutor {
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
        val ctx = factory.make(
            NodeArgs(
                InternalContextImpl(context.fullSchema, globalIDCodec, reflectionLoader),
                selections = selections,
                globalID = id,
                selectionsLoaderFactory = SelectionsLoaderImpl.Factory(context.rawSelectionsLoaderFactory),
                selectionSetFactory = SelectionSetFactoryImpl(context.rawSelectionSetFactory),
                resolverId = typeName,
                engineExecutionContext = context,
            )
        )
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

            val eo = result.engineObject
            return when (eo) {
                is NodeReference -> throw ViaductTenantUsageException(
                    "NodeReference returned from node resolver. Use a GRT builder instead of Context.nodeFor to construct your node object."
                )

                is EngineObjectData -> eo
                else -> throw IllegalStateException("engineObject has unknown type ${eo.javaClass.name}")
            }
        }
    }
}
