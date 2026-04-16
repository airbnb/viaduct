package viaduct.tenant.runtime.execution

import java.lang.reflect.InvocationTargetException
import kotlin.reflect.KFunction
import kotlin.reflect.full.callSuspend
import viaduct.apiannotations.InTenantCode

@InTenantCode
internal suspend fun <T> callResolver(
    resolverFunction: KFunction<*>,
    vararg args: Any?,
): T =
    try {
        @Suppress("UNCHECKED_CAST")
        resolverFunction.callSuspend(*args) as T
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }
