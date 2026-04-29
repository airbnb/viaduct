package viaduct.tenant.runtime.execution

import javax.inject.Provider
import kotlin.reflect.KFunction
import viaduct.api.NodeResolverBase
import viaduct.api.internal.ObjectBase
import viaduct.api.internal.ReflectionLoader
import viaduct.apiannotations.InTenantCode
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.NodeReference
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.ResolverType
import viaduct.engine.api.TenantModuleMetadata
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.errors.FrameworkException
import viaduct.errors.TenantException
import viaduct.errors.TenantResolverException
import viaduct.errors.TenantUsageException
import viaduct.errors.handleTenantErrorsSuspend
import viaduct.errors.resultOfSuspend
import viaduct.tenant.runtime.context.factory.NodeExecutionContextFactory

class NodeUnbatchedResolverExecutorImpl(
    val resolver: Provider<out @JvmSuppressWildcards NodeResolverBase<*>>,
    private val resolveFunction: KFunction<*>,
    override val typeName: String,
    private val reflectionLoader: ReflectionLoader,
    private val factory: NodeExecutionContextFactory,
    private val resolverName: String,
    override val isSelective: Boolean,
    private val tenantMetadata: TenantModuleMetadata? = null,
) : NodeResolverExecutor {
    override val metadata = ResolverMetadata.forModern(resolverName, ResolverType.NODE, tenantMetadata)
    override val isBatching = false

    override suspend fun resolve(
        selectors: List<NodeResolverExecutor.Selector>,
        context: EngineExecutionContext
    ): Map<NodeResolverExecutor.Selector, Result<EngineObjectData>> {
        // Only handle single selector case because this is an unbatched resolver
        require(selectors.size == 1) { "Unbatched resolver should only receive single selector, got {}".format(selectors.size) }
        val selector = selectors.first()
        val result = resultOfSuspend {
            resolve(selector.id, selector.selections, context)
        }
        return mapOf(selector to result)
    }

    private suspend fun resolve(
        id: String,
        selections: EngineSelectionSet,
        context: EngineExecutionContext
    ): EngineObjectData {
        val ctx = factory(context, selections, context.requestContext, id)
        val resolver = resolver.get()
        val result: Any? = handleTenantErrorsSuspend(typeName) {
            callResolver(resolveFunction, resolver, ctx)
        }
        try {
            return unwrapNodeResolverResult(result)
        } catch (e: Exception) {
            if (e is TenantException) {
                throw TenantResolverException(e, typeName)
            }
            throw e
        }
    }

    companion object {
        @InTenantCode
        internal fun unwrapNodeResolverResult(result: Any?): EngineObjectData {
            if (result !is ObjectBase) {
                throw TenantUsageException("Unexpected result type that is not a GRT for a node object: $result")
            }

            return when (val eo = result.engineObject) {
                is NodeReference -> throw TenantUsageException(
                    "NodeReference returned from node resolver. Use a GRT builder instead of Context.nodeRef to construct your node object."
                )

                is EngineObjectData -> eo
                else -> throw FrameworkException("engineObject has unknown type ${eo.javaClass.name}")
            }
        }
    }
}
