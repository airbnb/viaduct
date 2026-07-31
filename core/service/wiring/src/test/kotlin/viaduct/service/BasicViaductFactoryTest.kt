package viaduct.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.service.api.SchemaId
import viaduct.service.api.spi.NaiveTenantModuleInjectorFactory
import viaduct.service.runtime.SchemaConfiguration

internal class BasicViaductFactoryTest {
    @Test
    fun `create should attempt to build Viaduct from classpath registry`() {
        assertThrows<Exception> {
            BasicViaductFactory.create()
        }
    }

    @Nested
    inner class SchemaScopeInfoTests {
        @Test
        fun `scoped schema should expose scoped SchemaId`() {
            val scopeInfo = SchemaScopeInfo.Scoped("test-schema", setOf("admin", "user"))

            val scoped = scopeInfo.schemaId as SchemaId.Scoped
            assertEquals("test-schema", scoped.id)
            assertEquals(setOf("admin", "user"), scoped.scopeIds)
        }

        @Test
        fun `base schema should expose canonical base SchemaId`() {
            val scopeInfo = SchemaScopeInfo.Base

            assertEquals(SchemaId.Base, scopeInfo.schemaId)
        }

        @Test
        fun `should reject blank id`() {
            assertThrows<IllegalArgumentException> {
                SchemaScopeInfo.Scoped("   ", setOf("public"))
            }
        }

        @Test
        fun `scoped schema should reject empty scopes`() {
            assertThrows<IllegalArgumentException> {
                SchemaScopeInfo.Scoped("scoped-schema", emptySet())
            }
        }

        @Test
        fun `toScopeConfig should preserve scoped intent`() {
            val scopeInfo = SchemaScopeInfo.Scoped("public", setOf("scope1", "scope2"))

            val scopeConfig = scopeInfo.toScopeConfig() as SchemaConfiguration.ScopeConfig.Scoped

            assertEquals("public", scopeConfig.id)
            assertEquals(setOf("scope1", "scope2"), scopeConfig.scopeIds)
        }

        @Test
        fun `toScopeConfig should preserve base intent`() {
            val scopeInfo = SchemaScopeInfo.Base

            val scopeConfig = scopeInfo.toScopeConfig()

            assertEquals(SchemaConfiguration.ScopeConfig.Base, scopeConfig)
        }
    }

    @Nested
    inner class CreateTests {
        @Test
        fun `create should accept a custom tenant module injector factory`() {
            assertThrows<Exception> {
                BasicViaductFactory.create(
                    tenantModuleInjectorFactory = NaiveTenantModuleInjectorFactory,
                )
            }
        }

        @Test
        fun `create should accept scoped schemas`() {
            assertThrows<Exception> {
                BasicViaductFactory.create(
                    scopedSchemas = listOf(
                        SchemaScopeInfo.Scoped("scoped", setOf("scope1", "scope2"))
                    ),
                )
            }
        }

        @Test
        fun `create should accept multiple mixed scopes`() {
            assertThrows<Exception> {
                BasicViaductFactory.create(
                    scopedSchemas = listOf(
                        SchemaScopeInfo.Base,
                        SchemaScopeInfo.Scoped("public", setOf("public")),
                        SchemaScopeInfo.Scoped("admin", setOf("admin", "internal"))
                    ),
                )
            }
        }

        @Test
        fun `create should accept empty scoped schemas list`() {
            assertThrows<Exception> {
                BasicViaductFactory.create(
                    scopedSchemas = emptyList(),
                )
            }
        }
    }
}
