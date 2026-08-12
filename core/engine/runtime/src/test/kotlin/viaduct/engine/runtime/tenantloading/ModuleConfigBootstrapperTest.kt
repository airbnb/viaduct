package viaduct.engine.runtime.tenantloading

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.bootstrap.executionregistry.ModuleConfigSource
import viaduct.service.api.spi.InputStreamSource

class ModuleConfigBootstrapperTest {
    private val executorFactoryFqn = "viaduct.engine.runtime.tenantloading.TestExecutorFactory"

    private fun source(
        tenantName: String,
        name: String,
        apiName: String = "kotlin",
        bootstrapClass: Class<*>? = null,
    ): ModuleConfigSource =
        ModuleConfigSource.from(
            InputStreamSource.fromString(
                """
                {
                  "version": "1",
                  "tenantName": "$tenantName",
                  "apiName": "$apiName",
                  "executorFactory": "$executorFactoryFqn",
                  ${bootstrapClass?.let { "\"bootstrapClass\": \"${it.name}\"," } ?: ""}
                  "nodes": [],
                  "fields": []
                }
                """.trimIndent(),
                name = name,
            ),
        )

    @Test
    fun `a tenant with multiple sources is bootstrapped exactly once`() =
        runTest {
            val recording = RecordingTenantModuleInjectorFactory()
            // A single tenant contributes two configs from different tenant APIs (same tenantName).
            val bootstrappers = ModuleConfigBootstrapper(tenantModuleInjectorFactory = recording).bootstrap(
                listOf(
                    source(tenantName = "alpha", name = "com.example.alpha", apiName = "kotlin"),
                    source(tenantName = "alpha", name = "com.example.alpha.other", apiName = "other"),
                ),
            )

            // bootstrap() honors the once-per-tenant SPI contract despite two sources...
            assertEquals(listOf("alpha" to null), recording.calls)
            // ...but still produces one module bootstrapper per source.
            assertEquals(2, bootstrappers.size)
        }

    @Test
    fun `distinct tenants are each bootstrapped once`() =
        runTest {
            val recording = RecordingTenantModuleInjectorFactory()
            ModuleConfigBootstrapper(tenantModuleInjectorFactory = recording).bootstrap(
                listOf(
                    source(tenantName = "alpha", name = "com.example.alpha"),
                    source(tenantName = "beta", name = "com.example.beta"),
                    source(tenantName = "alpha", name = "com.example.alpha.other", apiName = "other"),
                ),
            )

            assertEquals(listOf("alpha" to null, "beta" to null), recording.calls)
        }

    @Test
    fun `the tenant bootstrap class is carried through from the source that declares it`() =
        runTest {
            val recording = RecordingTenantModuleInjectorFactory()
            ModuleConfigBootstrapper(tenantModuleInjectorFactory = recording).bootstrap(
                listOf(
                    source(tenantName = "alpha", name = "com.example.alpha"),
                    source(
                        tenantName = "alpha",
                        name = "com.example.alpha.other",
                        apiName = "other",
                        bootstrapClass = TestBootstrapClass::class.java,
                    ),
                ),
            )

            assertEquals(listOf("alpha" to TestBootstrapClass::class.java), recording.calls)
        }

    @Test
    fun `two sources claiming the same config key throw`() {
        val recording = RecordingTenantModuleInjectorFactory()

        // The bootstrapper is the choke point every source producer converges on, so it rejects
        // duplicate <tenantName, apiName> keys regardless of which producer built the list.
        val ex = assertThrows<IllegalArgumentException> {
            runTest {
                ModuleConfigBootstrapper(tenantModuleInjectorFactory = recording).bootstrap(
                    listOf(
                        source(tenantName = "alpha", name = "com.example.alpha"),
                        source(tenantName = "alpha", name = "com.example.alpha.duplicate"),
                    ),
                )
            }
        }
        assertEquals(true, ex.message!!.contains("<alpha, kotlin>"), ex.message)
    }

    @Test
    fun `conflicting bootstrap classes for one tenant throw`() {
        val recording = RecordingTenantModuleInjectorFactory()

        assertThrows<IllegalArgumentException> {
            runTest {
                ModuleConfigBootstrapper(tenantModuleInjectorFactory = recording).bootstrap(
                    listOf(
                        source(
                            tenantName = "alpha",
                            name = "com.example.alpha",
                            bootstrapClass = TestBootstrapClass::class.java,
                        ),
                        source(
                            tenantName = "alpha",
                            name = "com.example.alpha.other",
                            apiName = "other",
                            bootstrapClass = OtherTestBootstrapClass::class.java,
                        ),
                    ),
                )
            }
        }
    }
}

/** Second marker class used to exercise the conflicting-bootstrap-class guard. */
class OtherTestBootstrapClass
