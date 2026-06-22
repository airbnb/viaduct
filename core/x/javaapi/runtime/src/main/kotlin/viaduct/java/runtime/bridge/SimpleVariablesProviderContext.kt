package viaduct.java.runtime.bridge

import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.ViaductSchema
import viaduct.errors.FrameworkException
import viaduct.errors.TenantUsageException
import viaduct.java.api.context.VariablesProviderContext
import viaduct.java.api.globalid.GlobalID
import viaduct.java.api.internal.InternalContext
import viaduct.java.api.internal.ResolverClassFinder
import viaduct.java.api.reflect.Type
import viaduct.java.api.types.Arguments
import viaduct.java.api.types.NodeCompositeOutput
import viaduct.service.api.spi.GlobalIDCodec

/**
 * Minimal implementation of [VariablesProviderContext] for Java [viaduct.java.api.variables.VariablesProvider]
 * implementations.
 *
 * Bridges the engine's untyped per-invocation data (argument map) to the Java API's typed
 * [VariablesProviderContext] interface. Also implements [InternalContext] so the cast from
 * `InternalContext.from(ctx)` succeeds when tenant code passes this context to generated builders.
 *
 * @param requestContext The request context from the engine
 * @param arguments The typed Arguments instance, or null when the field has no arguments
 * @param engineExecutionContext The engine execution context, used by [globalIDFor] / [serialize]
 * @param classFinder Resolves GRT classes by type name; may be null outside a live execution context
 */
@Suppress("UNCHECKED_CAST")
class SimpleVariablesProviderContext(
    private val requestContext: Any?,
    private val arguments: Arguments? = null,
    private val engineExecutionContext: EngineExecutionContext? = null,
    private val classFinder: ResolverClassFinder? = null,
) : VariablesProviderContext<Arguments>, InternalContext {
    override fun getArguments(): Arguments = arguments ?: Arguments.NoArguments

    override fun getRequestContext(): Any? = requestContext

    override fun <T : NodeCompositeOutput> globalIDFor(
        type: Type<T>,
        internalID: String
    ): GlobalID<T> {
        val codec = engineExecutionContext?.globalIDCodec
            ?: throw FrameworkException("globalIDFor requires engineExecutionContext.")
        return codec.createGlobalID(type, internalID)
    }

    override fun <T : NodeCompositeOutput> serialize(globalID: GlobalID<T>): String {
        val codec = engineExecutionContext?.globalIDCodec
            ?: throw FrameworkException("serialize requires engineExecutionContext.")
        return codec.serializeGlobalID(globalID)
    }

    // ── InternalContext implementation ──

    override fun getSchema(): ViaductSchema {
        return engineExecutionContext?.fullSchema
            ?: throw FrameworkException("getSchema() requires engineExecutionContext.")
    }

    override fun getGlobalIDCodec(): GlobalIDCodec {
        return engineExecutionContext?.globalIDCodec
            ?: throw FrameworkException("getGlobalIDCodec() requires engineExecutionContext.")
    }

    override fun getClassFinder(): ResolverClassFinder {
        return classFinder
            ?: throw FrameworkException("getClassFinder() requires classFinder.")
    }

    override fun <T : NodeCompositeOutput> deserializeGlobalID(serialized: String): GlobalID<T> {
        val codec = engineExecutionContext?.globalIDCodec
            ?: throw FrameworkException("deserializeGlobalID requires engineExecutionContext.")
        val (typeName, internalId) = try {
            codec.deserialize(serialized)
        } catch (e: IllegalArgumentException) {
            throw TenantUsageException("Invalid GlobalID: \"$serialized\"", e)
        }
        return GlobalIDImpl(type = typeFromName(typeName), internalId = internalId)
    }
}
