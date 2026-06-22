@file:Suppress("ForbiddenImport")

package viaduct.service.api.spi

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class TenantModuleInjectorFactoryTest {
    @Test
    fun `default finalize is a no-op`() =
        runBlocking {
            val bootstrapper = object : TenantModuleInjectorFactory {
                override suspend fun bootstrap(
                    tenantName: String,
                    tenantBootstrapClass: Class<*>?,
                ): CodeInjector = CodeInjector.Naive
            }

            assertSame(CodeInjector.Naive, bootstrapper.bootstrap("tenant", null))
            assertEquals(Unit, bootstrapper.finalize())
        }

    @Test
    fun `shared code injector bootstrapper returns the same injector for every tenant`() =
        runBlocking {
            val sharedInjector = object : CodeInjector {
                override fun <T> getProvider(clazz: Class<T>) = throw UnsupportedOperationException("not needed for bootstrapper tests")
            }

            val bootstrapper = SharedTenantModuleInjectorFactory(sharedInjector)

            assertSame(sharedInjector, bootstrapper.bootstrap("tenant-a", null))
            assertSame(sharedInjector, bootstrapper.bootstrap("tenant-b", String::class.java))
            assertEquals(Unit, bootstrapper.finalize())
        }

    @Test
    fun `naive tenant module injector factory always returns the naive injector`() =
        runBlocking {
            assertSame(CodeInjector.Naive, NaiveTenantModuleInjectorFactory.bootstrap("tenant-a", null))
            assertSame(CodeInjector.Naive, NaiveTenantModuleInjectorFactory.bootstrap("tenant-b", String::class.java))
            assertEquals(Unit, NaiveTenantModuleInjectorFactory.finalize())
        }
}
