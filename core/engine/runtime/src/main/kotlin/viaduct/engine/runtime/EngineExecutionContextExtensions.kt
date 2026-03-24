package viaduct.engine.runtime

import graphql.execution.instrumentation.Instrumentation
import graphql.schema.DataFetchingEnvironment
import java.util.function.Supplier
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.ViaductSchema

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
    private fun EngineExecutionContext.asImpl(): EngineExecutionContextImpl {
        return this as? EngineExecutionContextImpl
            ?: error("Expected EngineExecutionContextImpl but got ${this::class.qualifiedName}")
    }

    val EngineExecutionContext.executeAccessChecksInModstrat: Boolean
        get() = asImpl().executeAccessChecksInModstrat

    val EngineExecutionContext.dispatcherRegistry: DispatcherRegistry
        get() = asImpl().dispatcherRegistry

    val EngineExecutionContext.resolverInstrumentation: Instrumentation
        get() = asImpl().resolverInstrumentation

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
     * Creates a copy of the EEC with optional overrides for field scope and/or DFE.
     * The copy automatically preserves the [EngineExecutionContext.executionHandle].
     */
    internal fun EngineExecutionContext.copy(
        activeSchema: ViaductSchema = this.activeSchema,
        fieldScopeSupplier: Supplier<out EngineExecutionContext.FieldExecutionScope> = asImpl().fieldScopeSupplier,
        dataFetchingEnvironment: DataFetchingEnvironment? = asImpl().dataFetchingEnvironment,
    ): EngineExecutionContextImpl {
        return asImpl().copy(
            activeSchema = activeSchema,
            fieldScopeSupplier = fieldScopeSupplier,
            dataFetchingEnvironment = dataFetchingEnvironment,
        )
    }

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
