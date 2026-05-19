package viaduct.java.runtime.bridge

import viaduct.engine.api.EngineExecutionContext
import viaduct.errors.FrameworkException
import viaduct.java.api.context.VariablesProviderContext
import viaduct.java.api.types.Arguments

/**
 * Minimal implementation of [VariablesProviderContext] for Java [viaduct.java.api.variables.VariablesProvider]
 * implementations.
 *
 * Bridges the engine's untyped per-invocation data (argument map) to the Java API's typed
 * [VariablesProviderContext] interface.
 *
 * @param requestContext The request context from the engine
 * @param arguments The typed Arguments instance, or null when the field has no arguments
 * @param engineExecutionContext The engine execution context, used by [globalIDFor] / [serialize]
 */
@Suppress("UNCHECKED_CAST")
class SimpleVariablesProviderContext(
    private val requestContext: Any?,
    private val arguments: Arguments? = null,
    private val engineExecutionContext: EngineExecutionContext? = null,
) : VariablesProviderContext<Arguments> {
    override fun getArguments(): Arguments = arguments ?: Arguments.NoArguments

    override fun getRequestContext(): Any? = requestContext

    override fun <T : viaduct.java.api.types.NodeCompositeOutput> globalIDFor(
        type: viaduct.java.api.reflect.Type<T>,
        internalID: String
    ): viaduct.java.api.globalid.GlobalID<T> {
        val codec = engineExecutionContext?.globalIDCodec
            ?: throw FrameworkException("globalIDFor requires engineExecutionContext.")
        return codec.createGlobalID(type, internalID)
    }

    override fun <T : viaduct.java.api.types.NodeCompositeOutput> serialize(globalID: viaduct.java.api.globalid.GlobalID<T>): String {
        val codec = engineExecutionContext?.globalIDCodec
            ?: throw FrameworkException("serialize requires engineExecutionContext.")
        return codec.serializeGlobalID(globalID)
    }
}
