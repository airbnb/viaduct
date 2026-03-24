package viaduct.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.service.api.SchemaId
import viaduct.service.api.spi.TenantCodeInjector

internal class BasicViaductFactoryTest {
    @Test
    fun `should create builder with tenant info`() {
        val tenantInfo = TenantRegistrationInfo("com.test")

        val builder = BasicViaductFactory.builderWithTenantInfo(tenantInfo)

        assertNotNull(builder)
    }

    @Nested
    inner class DataClassTests {
        @Test
        fun `TenantRegistrationInfo should have correct defaults`() {
            val tenantInfo = TenantRegistrationInfo("com.airbnb.test")

            assertEquals("com.airbnb.test", tenantInfo.tenantPackagePrefix)
            assertEquals(TenantCodeInjector.Naive, tenantInfo.tenantCodeInjector)
        }

        @Test
        fun `SchemaRegistrationInfo should have correct defaults`() {
            val schemaInfo = SchemaRegistrationInfo()

            assertEquals(0, schemaInfo.scopes.size)
            assertNull(schemaInfo.grtPackagePrefix)
            assertNull(schemaInfo.grtResourcesIncluded)
        }

        @Test
        fun `SchemaScopeInfo should have correct defaults`() {
            val scopeInfo = SchemaScopeInfo()

            assertEquals("", scopeInfo.schemaId)
            assertNull(scopeInfo.scopesToApply)
        }

        @Test
        fun `SchemaScopeInfo should handle custom values correctly`() {
            val scopes = setOf("admin", "user")

            val scopeInfo = SchemaScopeInfo("test-schema", scopes)

            assertEquals("test-schema", scopeInfo.schemaId)
            assertEquals(scopes, scopeInfo.scopesToApply)
        }

        @Test
        fun `TenantRegistrationInfo equality should work correctly`() {
            val info1 = TenantRegistrationInfo("com.airbnb.test")
            val info2 = TenantRegistrationInfo("com.airbnb.test")
            val info3 = TenantRegistrationInfo("com.airbnb.different")

            assertEquals(info1, info2)
            assertNotEquals(info1, info3)
            assertEquals(info1, info2)
        }

        @Test
        fun `SchemaRegistrationInfo equality should work correctly`() {
            val info1 = SchemaRegistrationInfo()
            val info2 = SchemaRegistrationInfo()
            val info3 = SchemaRegistrationInfo(grtPackagePrefix = "different")

            assertEquals(info1, info2)
            assertNotEquals(info1, info3)
            assertEquals(info1, info2)
        }

        @Test
        fun `SchemaScopeInfo equality should work correctly`() {
            val scope1 = SchemaScopeInfo("test", setOf("a", "b"))
            val scope2 = SchemaScopeInfo("test", setOf("a", "b"))
            val scope3 = SchemaScopeInfo("different", setOf("a", "b"))

            assertEquals(scope1, scope2)
            assertNotEquals(scope1, scope3)
            assertEquals(scope1, scope2)
        }
    }

    @Nested
    inner class SchemaConfigurationTests {
        @Test
        fun `should handle multiple scoped schemas configuration`() {
            val schemaInfo = SchemaRegistrationInfo(
                scopes = listOf(
                    SchemaScopeInfo("public", setOf("public")),
                    SchemaScopeInfo("internal", setOf("internal", "admin")),
                    SchemaScopeInfo("full", null)
                )
            )

            assertEquals(3, schemaInfo.scopes.size)
            assertEquals("public", schemaInfo.scopes[0].schemaId)
            assertEquals(setOf("public"), schemaInfo.scopes[0].scopesToApply)
            assertEquals("internal", schemaInfo.scopes[1].schemaId)
            assertEquals(setOf("internal", "admin"), schemaInfo.scopes[1].scopesToApply)
            assertEquals("full", schemaInfo.scopes[2].schemaId)
            assertNull(schemaInfo.scopes[2].scopesToApply)
        }

        @Test
        fun `should handle empty scopes list configuration`() {
            val schemaInfo = SchemaRegistrationInfo(scopes = emptyList())

            assertTrue(schemaInfo.scopes.isEmpty())
        }

        @Test
        fun `should handle null and empty scope sets correctly in configuration`() {
            val schemaInfo = SchemaRegistrationInfo(
                scopes = listOf(
                    SchemaScopeInfo("null-scope", null),
                    SchemaScopeInfo("empty-scope", emptySet())
                )
            )

            assertEquals(2, schemaInfo.scopes.size)
            assertNull(schemaInfo.scopes[0].scopesToApply)
            assertTrue(schemaInfo.scopes[1].scopesToApply?.isEmpty() == true)
        }

        @Test
        fun `should handle custom GRT package prefix and resource patterns`() {
            val schemaInfo = SchemaRegistrationInfo(
                grtPackagePrefix = "com.airbnb.custom",
                grtResourcesIncluded = ".*\\.gql"
            )

            assertEquals("com.airbnb.custom", schemaInfo.grtPackagePrefix)
            assertEquals(".*\\.gql", schemaInfo.grtResourcesIncluded)
        }
    }

    @Nested
    inner class ApplySchemaRegistryTests {
        @Test
        fun `should apply schema registry with single full schema`() {
            val schemaInfo = SchemaRegistrationInfo(
                scopes = listOf(SchemaScopeInfo("main", null)),
                grtPackagePrefix = "com.test",
                grtResourcesIncluded = ".*\\.graphqls"
            )

            val builder = BasicViaductFactory.applySchemaRegistry(schemaInfo)

            assertNotNull(builder)
        }

        @Test
        fun `should apply schema registry with single scoped schema`() {
            val schemaInfo = SchemaRegistrationInfo(
                scopes = listOf(SchemaScopeInfo("scoped", setOf("scope1", "scope2"))),
                grtPackagePrefix = "com.test.scoped",
                grtResourcesIncluded = ".*test.*\\.graphqls"
            )

            val builder = BasicViaductFactory.applySchemaRegistry(schemaInfo)

            assertNotNull(builder)
        }

        @Test
        fun `should apply schema registry with multiple mixed schemas`() {
            val schemaInfo = SchemaRegistrationInfo(
                scopes = listOf(
                    SchemaScopeInfo("full", null),
                    SchemaScopeInfo("public", setOf("public")),
                    SchemaScopeInfo("admin", setOf("admin", "internal"))
                ),
                grtPackagePrefix = "com.mixed",
                grtResourcesIncluded = ".*\\.gql"
            )

            val builder = BasicViaductFactory.applySchemaRegistry(schemaInfo)

            assertNotNull(builder)
        }

        @Test
        fun `should apply schema registry with empty scopes list`() {
            val schemaInfo = SchemaRegistrationInfo(
                scopes = emptyList(),
                grtPackagePrefix = "com.empty",
                grtResourcesIncluded = ".*\\.graphqls"
            )

            val builder = BasicViaductFactory.applySchemaRegistry(schemaInfo)

            assertNotNull(builder)
        }

        @Test
        fun `should apply schema registry with empty scope set`() {
            val schemaInfo = SchemaRegistrationInfo(
                scopes = listOf(SchemaScopeInfo("empty-scope", emptySet())),
                grtPackagePrefix = "com.test",
                grtResourcesIncluded = ".*\\.graphqls"
            )

            val builder = BasicViaductFactory.applySchemaRegistry(schemaInfo)

            assertNotNull(builder)
        }

        @Test
        fun `should apply schema registry with null GRT package prefix and resources`() {
            val schemaInfo = SchemaRegistrationInfo(
                scopes = listOf(SchemaScopeInfo("test", setOf("scope1"))),
                grtPackagePrefix = null,
                grtResourcesIncluded = null
            )

            val builder = BasicViaductFactory.applySchemaRegistry(schemaInfo)

            assertNotNull(builder)
        }
    }

    @Nested
    inner class CreateTests {
        @Test
        fun `create should attempt to build Viaduct with valid configuration`() {
            val tenantInfo = TenantRegistrationInfo("com.test")
            val schemaInfo = SchemaRegistrationInfo()

            // This will throw because no GRT resources exist on classpath
            // but it exercises the create() code path
            assertThrows<Exception> {
                BasicViaductFactory.create(schemaInfo, tenantInfo)
            }
        }

        @Test
        fun `create should use default schema registration info when not provided`() {
            val tenantInfo = TenantRegistrationInfo("com.test")

            assertThrows<Exception> {
                BasicViaductFactory.create(tenantRegistrationInfo = tenantInfo)
            }
        }
    }

    @Nested
    inner class CreateForTestingTests {
        @Test
        fun `createForTesting should attempt to build Viaduct with valid configuration`() {
            val tenantInfo = TenantRegistrationInfo("com.test")

            assertThrows<Exception> {
                BasicViaductFactory.createForTesting(
                    scopes = listOf(SchemaScopeInfo("test", setOf("scope1"))),
                    tenantRegistrationInfo = tenantInfo,
                    grtPackagePrefix = "com.test",
                    grtResourcesIncluded = ".*\\.graphqls"
                )
            }
        }

        @Test
        fun `createForTesting should use default parameters when not provided`() {
            val tenantInfo = TenantRegistrationInfo("com.test")

            assertThrows<Exception> {
                BasicViaductFactory.createForTesting(tenantRegistrationInfo = tenantInfo)
            }
        }
    }

    @Nested
    inner class ToSchemaScopeInfoTests {
        @Test
        fun `should convert SchemaId Scoped with scopes to SchemaScopeInfo`() {
            val schemaId = SchemaId.Scoped("test-id", setOf("scope1", "scope2"))

            val scopeInfo = schemaId.toSchemaScopeInfo()

            assertEquals("test-id", scopeInfo.schemaId)
            assertEquals(setOf("scope1", "scope2"), scopeInfo.scopesToApply)
        }

        @Test
        fun `should convert SchemaId Scoped with empty scopes to SchemaScopeInfo with null scopesToApply`() {
            val schemaId = SchemaId.Scoped("empty-scope-id", emptySet())

            val scopeInfo = schemaId.toSchemaScopeInfo()

            assertEquals("empty-scope-id", scopeInfo.schemaId)
            assertNull(scopeInfo.scopesToApply)
        }

        @Test
        fun `should convert SchemaId Scoped with single scope to SchemaScopeInfo`() {
            val schemaId = SchemaId.Scoped("single-scope", setOf("admin"))

            val scopeInfo = schemaId.toSchemaScopeInfo()

            assertEquals("single-scope", scopeInfo.schemaId)
            assertEquals(setOf("admin"), scopeInfo.scopesToApply)
        }
    }
}
