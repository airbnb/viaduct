package viaduct.engine.runtime

import graphql.execution.instrumentation.Instrumentation
import graphql.schema.DataFetchingEnvironment
import java.util.function.Supplier
import viaduct.engine.api.Caller
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.ViaductSchema
import viaduct.service.api.spi.ErrorReporter

/**
 * Extension properties and functions for accessing [EngineExecutionContextImpl]
 * internals from [EngineExecutionContext] interface references.
 * These encapsulate the cast so callers don't need to know about the impl.
 */
object EngineExecutionContextExtensions {
    /**
     * Casts [viaduct.engine.api.EngineExecutionContext] to [EngineExecutionContextImpl] with a clear error message.
     *
     * Use this instead of bare `as` casts throughout the extensions to provide
     * consistent error handling if an unexpected implementation is encountered.
     */
    internal fun EngineExecutionContext.asImpl(): EngineExecutionContextImpl {
        return (this as? InternalEngineExecutionContext)?.impl
            ?: error("Expected InternalEngineExecutionContext but got ${this::class.qualifiedName}")
    }

    val EngineExecutionContext.matResolutionEnabled: Boolean
        get() = asImpl().matResolutionEnabled

    val EngineExecutionContext.resolverOutputMissingFieldReporter: ErrorReporter
        get() = asImpl().resolverOutputMissingFieldReporter

    val EngineExecutionContext.resolverOutputMissingFieldErrorsEnabled: Boolean
        get() = asImpl().resolverOutputMissingFieldErrorsEnabled

    val EngineExecutionContext.fieldRssOriginFilteringKillSwitchEnabled: Boolean
        get() = asImpl().fieldRssOriginFilteringKillSwitchEnabled

    val EngineExecutionContext.dispatcherRegistry: DispatcherRegistry
        get() = asImpl().dispatcherRegistry

    val EngineExecutionContext.resolverInstrumentation: Instrumentation
        get() = asImpl().resolverInstrumentation

    val EngineExecutionContext.isResolverSelective: IsResolverSelective
        get() = asImpl().isResolverSelective

    var EngineExecutionContext.dataFetchingEnvironment: DataFetchingEnvironment?
        get() = asImpl().dataFetchingEnvironment
        set(value) {
            asImpl().dataFetchingEnvironment = value
        }

    internal val EngineExecutionContext.fieldScopeSupplier: Supplier<out EngineExecutionContext.FieldExecutionScope>
        get() = asImpl().fieldScopeSupplier

    /**
     * Internal setter for [EngineExecutionContext.executionHandle].
     *
     * The interface exposes executionHandle as read-only to prevent code outside the engine runtime from
     * arbitrarily re-assigning it. This extension provides write access for the runtime module.
     */
    internal fun EngineExecutionContext.setExecutionHandle(handle: EngineExecutionContext.ExecutionHandle?) {
        asImpl()._executionHandle = handle
    }

    /**
     * Extension to access [EngineExecutionContextImpl.copy] from interface references.
     *
     * Creates a copy of the EEC with optional overrides for the field scope, the DFE, the Mat batch
     * depth, and the current resolver. The copy automatically preserves the
     * [EngineExecutionContext.executionHandle].
     */
    internal fun EngineExecutionContext.copy(
        activeSchema: ViaductSchema = this.activeSchema,
        fieldScopeSupplier: Supplier<out EngineExecutionContext.FieldExecutionScope> = asImpl().fieldScopeSupplier,
        dataFetchingEnvironment: DataFetchingEnvironment? = asImpl().dataFetchingEnvironment,
        matBatchDepth: Int? = null,
        currentResolver: Caller? = asImpl().currentResolver,
    ): EngineExecutionContextImpl {
        return asImpl().copy(
            activeSchema = activeSchema,
            fieldScopeSupplier = fieldScopeSupplier,
            dataFetchingEnvironment = dataFetchingEnvironment,
            matBatchDepth = matBatchDepth,
            currentResolver = currentResolver,
        )
    }

    /**
     * Returns a new [EngineExecutionContextImpl.FieldExecutionScopeImpl] with [attribution] replaced,
     * preserving all other field scope state from this context.
     */
    internal fun EngineExecutionContext.fieldScopeWithAttribution(attribution: ExecutionAttribution): EngineExecutionContextImpl.FieldExecutionScopeImpl =
        EngineExecutionContextImpl.FieldExecutionScopeImpl(
            fragments = fieldScope.fragments,
            variables = fieldScope.variables,
            resolutionPolicy = fieldScope.resolutionPolicy,
            attribution = attribution,
            caller = fieldScope.caller,
        )

    /**
     * Returns true iff field coordinate has a tenant-defined resolver function.
     */
    fun EngineExecutionContext.hasResolver(
        typeName: String,
        fieldName: String
    ): Boolean {
        return asImpl().hasResolver(typeName, fieldName)
    }
}
