package viaduct.java.runtime.bridge

import viaduct.engine.api.ViaductSchema
import viaduct.java.api.internal.InternalContext
import viaduct.java.api.internal.ResolverClassFinder
import viaduct.service.api.spi.GlobalIDCodec

/**
 * Runtime implementation of the Java [InternalContext].
 *
 * Java mirror of Kotlin's [viaduct.tenant.runtime.internal.InternalContextImpl]: an API-layer
 * interface whose implementation lives in the runtime layer. Built once per resolve from the
 * engine's [viaduct.engine.api.EngineExecutionContext] (schema + codec) and the bridge's
 * [ResolverClassFinder], then attached to top-level GRTs and propagated to nested GRTs via their
 * constructors.
 */
internal class InternalContextImpl(
    private val schema: ViaductSchema,
    private val globalIDCodec: GlobalIDCodec,
    private val classFinder: ResolverClassFinder,
) : InternalContext {
    override fun getSchema(): ViaductSchema = schema

    override fun getGlobalIDCodec(): GlobalIDCodec = globalIDCodec

    override fun getClassFinder(): ResolverClassFinder = classFinder
}
