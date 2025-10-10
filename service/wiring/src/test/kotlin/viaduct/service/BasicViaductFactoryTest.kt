package viaduct.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
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
            assertNull(schemaInfo.packagePrefix)
            assertNull(schemaInfo.resourcesIncluded)
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
            val info3 = SchemaRegistrationInfo(packagePrefix = "different")

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
        fun `should handle custom package prefix and resource patterns`() {
            val schemaInfo = SchemaRegistrationInfo(
                packagePrefix = "com.airbnb.custom",
                resourcesIncluded = ".*\\.gql"
            )

            assertEquals("com.airbnb.custom", schemaInfo.packagePrefix)
            assertEquals(".*\\.gql", schemaInfo.resourcesIncluded)
        }
    }

    @Nested
    inner class ApplySchemaRegistryTests {
        @Test
        fun `should apply schema registry with single full schema`() {
            val schemaInfo = SchemaRegistrationInfo(
                scopes = listOf(SchemaScopeInfo("main", null)),
                packagePrefix = "com.test",
                resourcesIncluded = ".*\\.graphqls"
            )

            val builder = BasicViaductFactory.applySchemaRegistry(schemaInfo)

            assertNotNull(builder)
        }

        @Test
        fun `should apply schema registry with single scoped schema`() {
            val schemaInfo = SchemaRegistrationInfo(
                scopes = listOf(SchemaScopeInfo("scoped", setOf("scope1", "scope2"))),
                packagePrefix = "com.test.scoped",
                resourcesIncluded = ".*test.*\\.graphqls"
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
                packagePrefix = "com.mixed",
                resourcesIncluded = ".*\\.gql"
            )

            val builder = BasicViaductFactory.applySchemaRegistry(schemaInfo)

            assertNotNull(builder)
        }

        @Test
        fun `should apply schema registry with empty scopes list`() {
            val schemaInfo = SchemaRegistrationInfo(
                scopes = emptyList(),
                packagePrefix = "com.empty",
                resourcesIncluded = ".*\\.graphqls"
            )

            val builder = BasicViaductFactory.applySchemaRegistry(schemaInfo)

            assertNotNull(builder)
        }

        @Test
        fun `should apply schema registry with empty scope set`() {
            val schemaInfo = SchemaRegistrationInfo(
                scopes = listOf(SchemaScopeInfo("empty-scope", emptySet())),
                packagePrefix = "com.test",
                resourcesIncluded = ".*\\.graphqls"
            )

            val builder = BasicViaductFactory.applySchemaRegistry(schemaInfo)

            assertNotNull(builder)
        }

        @Test
        fun `should apply schema registry with null package prefix and resources`() {
            val schemaInfo = SchemaRegistrationInfo(
                scopes = listOf(SchemaScopeInfo("test", setOf("scope1"))),
                packagePrefix = null,
                resourcesIncluded = null
            )

            val builder = BasicViaductFactory.applySchemaRegistry(schemaInfo)

            assertNotNull(builder)
        }
    }
}
