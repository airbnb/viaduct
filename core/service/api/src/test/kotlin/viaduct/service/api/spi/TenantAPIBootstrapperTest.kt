@file:Suppress("ForbiddenImport")

package viaduct.service.api.spi

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.service.api.spi.TenantAPIBootstrapper.Companion.flatten

class TenantAPIBootstrapperTest {
    @Test
    fun `flatten combines multiple bootstrappers into one`() =
        runBlocking {
            val bootstrapper1 = object : TenantAPIBootstrapper<String> {
                override suspend fun tenantModuleBootstrappers() = listOf("a", "b")
            }
            val bootstrapper2 = object : TenantAPIBootstrapper<String> {
                override suspend fun tenantModuleBootstrappers() = listOf("c")
            }

            val flattened = listOf(bootstrapper1, bootstrapper2).flatten()
            val result = flattened.tenantModuleBootstrappers().toList()

            assertEquals(listOf("a", "b", "c"), result)
        }

    @Test
    fun `flatten with empty list returns empty bootstrapper`() =
        runBlocking {
            val flattened = emptyList<TenantAPIBootstrapper<String>>().flatten()
            val result = flattened.tenantModuleBootstrappers().toList()

            assertEquals(emptyList<String>(), result)
        }

    @Test
    fun `flatten with single bootstrapper returns same items`() =
        runBlocking {
            val bootstrapper = object : TenantAPIBootstrapper<Int> {
                override suspend fun tenantModuleBootstrappers() = listOf(1, 2, 3)
            }

            val flattened = listOf(bootstrapper).flatten()
            val result = flattened.tenantModuleBootstrappers().toList()

            assertEquals(listOf(1, 2, 3), result)
        }
}
