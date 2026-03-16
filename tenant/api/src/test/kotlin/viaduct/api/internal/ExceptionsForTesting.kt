package viaduct.api.internal

import viaduct.errors.FrameworkException
import viaduct.errors.TenantException

object ExceptionsForTesting {
    private class TestTenantException(m: String) : TenantException, Exception(m)

    fun throwFrameworkException(m: String): Nothing = throw FrameworkException(m)

    fun throwTenantException(m: String): Nothing = throw TestTenantException(m)
}
