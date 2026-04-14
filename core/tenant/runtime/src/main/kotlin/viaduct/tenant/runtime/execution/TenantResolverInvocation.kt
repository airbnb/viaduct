package viaduct.tenant.runtime.execution

import java.lang.reflect.InvocationTargetException
import kotlin.reflect.KFunction
import kotlin.reflect.full.callSuspend
import viaduct.errors.handleTenantErrorsSuspend

internal suspend fun <T> callResolverAndHandleTenantErrors(
    resolverName: String,
    resolverFunction: KFunction<*>,
    vararg args: Any?,
): T =
    handleTenantErrorsSuspend(resolverName) {
        try {
            @Suppress("UNCHECKED_CAST")
            resolverFunction.callSuspend(*args) as T
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }
