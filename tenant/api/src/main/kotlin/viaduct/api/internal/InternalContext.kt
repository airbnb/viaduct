package viaduct.api.internal

import viaduct.api.ViaductFrameworkException
import viaduct.api.context.ExecutionContext
import viaduct.api.globalid.GlobalIDCodec
import viaduct.apiannotations.InternalApi
import viaduct.engine.api.ViaductSchema

/**
 * InternalContext encapsulates contextual dependencies of the viaduct runtime that
 * we don't want to expose to tenants.
 *
 * The runtime contexts created for tenants are expected to implement both [viaduct.api.context.ExecutionContext]
 * and InternalContext, which allows tenant-provided contexts to be safely casted back
 * to an InternalContext.
 */
@InternalApi
interface InternalContext {
    /** the Viaduct schema that underpins GRTs */
    val schema: ViaductSchema

    /**
     * A codec that is used to translate between [viaduct.api.globalid.GlobalID] tenant-space
     * values and [kotlin.String] engine-space values
     */
    val globalIDCodec: GlobalIDCodec

    /** An interface that can serve GRT's and type information for GraphQL types */
    val reflectionLoader: ReflectionLoader
}

/** project this [ExecutionContext] as an [InternalContext] */
@InternalApi
val ExecutionContext.internal: InternalContext
    get() = this as? InternalContext
        ?: throw ViaductFrameworkException("ExecutionContext does not implement InternalContext: $this")
