package viaduct.java.runtime.bridge

import graphql.schema.GraphQLInputObjectType
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.ViaductSchema
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
    private val delegate = JavaEngineContextDelegate(engineExecutionContext, classFinder)

    override fun getArguments(): Arguments = arguments ?: Arguments.None

    override fun getRequestContext(): Any? = requestContext

    override fun <T : NodeCompositeOutput> globalIDFor(
        type: Type<T>,
        internalID: String
    ): GlobalID<T> = delegate.globalIDFor(type, internalID)

    override fun <T : NodeCompositeOutput> serialize(globalID: GlobalID<T>): String = delegate.serialize(globalID)

    // ── InternalContext implementation (delegated to JavaEngineContextDelegate) ──

    override fun getSchema(): ViaductSchema = delegate.getSchema()

    override fun getArgumentsInputType(
        name: String,
        containingTypeName: String,
        fieldName: String,
    ): GraphQLInputObjectType = delegate.getArgumentsInputType(name, containingTypeName, fieldName)

    override fun getGlobalIDCodec(): GlobalIDCodec = delegate.getGlobalIDCodec()

    override fun getClassFinder(): ResolverClassFinder = delegate.getClassFinder()

    override fun <T : NodeCompositeOutput> deserializeGlobalID(serialized: String): GlobalID<T> = delegate.deserializeGlobalID(serialized)
}
