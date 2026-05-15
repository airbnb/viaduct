package viaduct.tenant.runtime.execution.filtertest

import kotlin.reflect.KClass
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.api.TenantModule
import viaduct.api.resolver.Resolver
import viaduct.graphql.test.assertEquals
import viaduct.service.api.SchemaId
import viaduct.service.runtime.SchemaConfiguration
import viaduct.tenant.runtime.bootstrap.TenantPackageFinder
import viaduct.tenant.runtime.bootstrap.TenantPackageInfo
import viaduct.tenant.runtime.execution.filtertest.resolverbases.QueryResolvers

class TenantPackageFilteringFeatureAppTest : TenantPackageFilteringContractTest() {
    override val validateResolverCompleteness = false

    @Resolver
    class Tenant1Scope1ValueResolver : QueryResolvers.Scope1Value() {
        override suspend fun resolve(ctx: Context): TestScope1Object {
            return TestScope1Object.Builder(ctx)
                .strValue("scope 1 test value")
                .build()
        }
    }

    private lateinit var schemaId1: SchemaId
    private lateinit var schemaId2: SchemaId

    @BeforeEach
    @Suppress("DEPRECATION")
    fun registerSchemas() {
        val config = SchemaConfiguration.fromSdl(
            sdl(),
            scopes = setOf(
                SchemaConfiguration.ScopeConfig("SCHEMA_ID_1", setOf("SCOPE1")),
                SchemaConfiguration.ScopeConfig("SCHEMA_ID_2", setOf("SCOPE2")),
            )
        )
        schemaId1 = SchemaId("SCHEMA_ID_1")
        schemaId2 = SchemaId("SCHEMA_ID_2")
        withViaductBuilder {
            withSchemaConfiguration(config)
        }
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
        val config2 = SchemaConfiguration.fromSdl(
            sdl(),
            scopes = setOf(
                SchemaConfiguration.ScopeConfig("SCOPE1_ONLY", setOf("SCOPE1")),
                SchemaConfiguration.ScopeConfig("SCOPE2_ONLY", setOf("SCOPE2"))
            )
        )
        val scope1Only = SchemaId("SCOPE1_ONLY")
        val scope2Only = SchemaId("SCOPE2_ONLY")
        withViaductBuilder {
            withSchemaConfiguration(config2)
        }

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
            schemaId = scope1Only
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
            schemaId = scope2Only
        ).assertEquals {
            "data" to {
                "scope2Value" to null
            }
        }
    }
}

class TestTenantPackageFinder(classes: Iterable<KClass<out TenantModule>>) : TenantPackageFinder {
    private val packageInfos = classes.map { TenantPackageInfo(packageName = it.java.packageName) }.toSet()

    override fun tenantPackages() = packageInfos
}

class Tenant1Module : TenantModule {
    override val metadata = mapOf("name" to "Tenant1")
}

class Tenant2Module : TenantModule {
    override val metadata = mapOf("name" to "Tenant2")
}
