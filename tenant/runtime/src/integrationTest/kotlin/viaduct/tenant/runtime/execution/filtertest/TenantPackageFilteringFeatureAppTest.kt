package viaduct.tenant.runtime.execution.filtertest

import kotlin.reflect.KClass
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.api.Resolver
import viaduct.api.TenantModule
import viaduct.graphql.test.assertEquals
import viaduct.service.runtime.SchemaRegistryConfiguration
import viaduct.tenant.runtime.bootstrap.TenantPackageFinder
import viaduct.tenant.runtime.execution.filtertest.resolverbases.QueryResolvers
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

class TenantPackageFilteringFeatureAppTest : FeatureAppTestBase() {
    @Resolver
    class Tenant1Scope1ValueResolver : QueryResolvers.Scope1Value() {
        override suspend fun resolve(ctx: Context): TestScope1Object {
            return TestScope1Object.Builder(ctx)
                .strValue("scope 1 test value")
                .build()
        }
    }

    override var sdl = """
        | #START_SCHEMA
        |   type TestScope1Object @scope(to: ["SCOPE1"]) {
        |       strValue: String!
        |   }
        |   type TestScope2Object @scope(to: ["SCOPE2"]) {
        |     strValue: String!
        |   }
        |
        |   extend type Query @scope(to: ["SCOPE1"]) {
        |     scope1Value: TestScope1Object @resolver
        |   }
        |
        |   extend type Query @scope(to: ["SCOPE2"]) {
        |     scope2Value: TestScope2Object @resolver
        |   }
        | #END_SCHEMA
    """.trimMargin()

    private val schemaId1 = "SCHEMA_ID_1"
    private val schemaId2 = "SCHEMA_ID_2"
    private val scopes1 = setOf("SCOPE1")
    private val scopes2 = setOf("SCOPE2")

    @BeforeEach
    fun registerSchemas() {
        withSchemaRegistryConfiguration(
            SchemaRegistryConfiguration.fromSdl(
                sdl,
                scopes = setOf(
                    SchemaRegistryConfiguration.ScopeConfig(schemaId1, scopes1),
                    SchemaRegistryConfiguration.ScopeConfig(schemaId2, scopes2)
                )
            )
        )
    }

    @Test
    @Suppress("DEPRECATION")
    fun `Tenant package filtering affects resolver availability`() {
        withViaductBuilder {
            withTenantAPIBootstrapperBuilder(
                viaductTenantAPIBootstrapperBuilder.tenantPackageFinder(
                    TestTenantPackageFinder(listOf(Tenant1Module::class))
                )
            )
        }

        execute(
            query = """
                query {
                    scope1Value {
                        strValue
                    }
                }
            """.trimIndent(),
            schemaId = schemaId1
        ).assertEquals {
            "data" to {
                "scope1Value" to {
                    "strValue" to "scope 1 test value"
                }
            }
        }

        execute(
            query = """
                query {
                    scope2Value {
                        strValue
                    }
                }
            """.trimIndent(),
            schemaId = schemaId2
        ).assertEquals {
            "data" to {
                "scope2Value" to null
            }
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun `Validation errors vs missing resolvers due to tenant filtering`() {
        withSchemaRegistryConfiguration(
            SchemaRegistryConfiguration.fromSdl(
                sdl,
                scopes = setOf(
                    SchemaRegistryConfiguration.ScopeConfig("SCOPE1_ONLY", setOf("SCOPE1")),
                    SchemaRegistryConfiguration.ScopeConfig("SCOPE2_ONLY", setOf("SCOPE2"))
                )
            )
        )

        withViaductBuilder {
            withTenantAPIBootstrapperBuilder(
                viaductTenantAPIBootstrapperBuilder.tenantPackageFinder(
                    TestTenantPackageFinder(listOf(Tenant1Module::class))
                )
            )
        }

        execute(
            query = """
                query {
                    scope2Value {
                        strValue
                    }
                }
            """.trimIndent(),
            schemaId = "SCOPE1_ONLY"
        ).assertEquals {
            "errors" to arrayOf(
                {
                    "message" to "Validation error (FieldUndefined@[scope2Value]) : Field 'scope2Value' in type 'Query' is undefined"
                    "locations" to arrayOf(
                        {
                            "line" to 2
                            "column" to 5
                        }
                    )
                    "extensions" to {
                        "classification" to "ValidationError"
                    }
                }
            )
            "data" to null
        }

        execute(
            query = """
                query {
                    scope2Value {
                        strValue
                    }
                }
            """.trimIndent(),
            schemaId = "SCOPE2_ONLY"
        ).assertEquals {
            "data" to {
                "scope2Value" to null
            }
        }
    }
}

class TestTenantPackageFinder(classes: Iterable<KClass<out TenantModule>>) : TenantPackageFinder {
    private val packages = classes.map { it.java.packageName }.toSet()

    override fun tenantPackages() = packages
}

class Tenant1Module : TenantModule {
    override val metadata = mapOf("name" to "Tenant1")
}

class Tenant2Module : TenantModule {
    override val metadata = mapOf("name" to "Tenant2")
}
