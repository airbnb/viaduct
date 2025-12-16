package viaduct.api.internal

import viaduct.api.ViaductFrameworkException
import viaduct.api.ViaductTenantException

object ExceptionsForTesting {
    private class TestViaductTenantException(m: String) : ViaductTenantException, Exception(m)

    fun throwViaductFrameworkException(m: String): Nothing = throw ViaductFrameworkException(m)

    fun throwViaductTenantException(m: String): Nothing = throw TestViaductTenantException(m)
}
