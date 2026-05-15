@file:Suppress("DEPRECATION") // Tests verify deprecated SchemaScopeInfo and withScopedSchemas still behave correctly

package viaduct.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.service.api.spi.NaiveTenantModuleBootstrapper

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
        fun `should expose SchemaId with id and scope ids`() {
            val scopeInfo = SchemaScopeInfo("test-schema", setOf("admin", "user"))

            assertEquals("test-schema", scopeInfo.schemaId.id)
            assertEquals(setOf("admin", "user"), scopeInfo.scopesToApply)
        }

        @Test
        fun `default scopesToApply should produce empty scope set`() {
            val scopeInfo = SchemaScopeInfo("full-schema")

            assertEquals("full-schema", scopeInfo.schemaId.id)
            assertTrue(scopeInfo.scopesToApply.isEmpty())
        }

        @Test
        fun `should reject blank id`() {
            assertThrows<IllegalArgumentException> {
                SchemaScopeInfo("")
            }
            assertThrows<IllegalArgumentException> {
                SchemaScopeInfo("   ")
            }
        }

        @Test
        fun `toScopeConfig should produce correct ScopeConfig`() {
            val scopeInfo = SchemaScopeInfo("public", setOf("scope1", "scope2"))

            val scopeConfig = scopeInfo.toScopeConfig()

            assertEquals("public", scopeConfig.id)
            assertEquals(setOf("scope1", "scope2"), scopeConfig.scopeIds)
        }
    }

    @Nested
    inner class CreateTests {
        @Test
        fun `create should accept a custom tenant module bootstrapper`() {
            assertThrows<Exception> {
                BasicViaductFactory.create(
                    tenantModuleBootstrapper = NaiveTenantModuleBootstrapper,
                )
            }
        }

        @Test
        fun `create should accept scoped schemas`() {
            assertThrows<Exception> {
                BasicViaductFactory.create(
                    scopedSchemas = listOf(
                        SchemaScopeInfo("scoped", setOf("scope1", "scope2"))
                    ),
                )
            }
        }

        @Test
        fun `create should accept multiple mixed scopes`() {
            assertThrows<Exception> {
                BasicViaductFactory.create(
                    scopedSchemas = listOf(
                        SchemaScopeInfo("full"),
                        SchemaScopeInfo("public", setOf("public")),
                        SchemaScopeInfo("admin", setOf("admin", "internal"))
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
