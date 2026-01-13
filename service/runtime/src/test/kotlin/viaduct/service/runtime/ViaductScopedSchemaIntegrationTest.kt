@file:Suppress("ForbiddenImport")

package viaduct.service.runtime

import com.google.inject.ProvisionException
import graphql.ExecutionResult
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import viaduct.engine.api.ViaductSchema
import viaduct.graphql.scopes.errors.SchemaScopeValidationError
import viaduct.graphql.utils.DefaultSchemaProvider
import viaduct.service.api.ExecutionInput
import viaduct.service.api.SchemaId
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.FlagManager.Flag

/**
 * Integration tests for Viaduct scoped schema functionality.
 *
 * These tests validate the complete scoped schema lifecycle:
 * - Schema registration via different configuration methods (SDL, resources, ViaductSchema)
 * - Query execution against scoped schemas
 * - Scope isolation and field-level access control
 * - Error handling for missing schemas and invalid scope access
 * - Sync/async execution consistency
 * - Build-time scope validation
 */
@ExperimentalCoroutinesApi
class ViaductScopedSchemaIntegrationTest {
    private lateinit var subject: StandardViaduct

    private val flagManager = object : FlagManager {
        override fun isEnabled(flag: Flag) = true
    }

    enum class SchemaConfigMethod {
        FROM_SDL,
        FROM_RESOURCES,
        FROM_SCHEMA
    }

    @Test
    fun `Execute scoped query successfully`() =
        runBlocking {
            val sdl = """
                extend type Query @scope(to: ["viaduct-public"]) {
                    field: Int
                }
            """.trimIndent()

            val schemaId = SchemaId.Scoped("public", setOf("viaduct-public"))
            val config = SchemaConfiguration.fromSdl(
                sdl,
                scopes = setOf(schemaId.toScopeConfig())
            )

            subject = StandardViaduct.Builder()
                .withFlagManager(flagManager)
                .withNoTenantAPIBootstrapper()
                .withSchemaConfiguration(config)
                .build()

            val query = """
                query TestQuery {
                    field
                }
            """.trimIndent()
            val executionInput = ExecutionInput.create(query, requestContext = object {})

            val syncResult = subject.execute(executionInput, schemaId)
            val asyncResult = subject.executeAsync(executionInput, schemaId).await()

            val expected = ExecutionResult.newExecutionResult()
                .data(mapOf("field" to null))
                .build()

            assertEquals(expected.toSpecification(), syncResult.toSpecification())
            assertEquals(expected.toSpecification(), asyncResult.toSpecification())
        }

    @ParameterizedTest
    @EnumSource(SchemaConfigMethod::class)
    fun `Execute scoped query with different configuration methods`(method: SchemaConfigMethod) =
        runBlocking {
            val sdl = """
                extend type Query @scope(to: ["viaduct-public"]) {
                    field: Int
                }
            """.trimIndent()

            val schemaId = SchemaId.Scoped("public", setOf("viaduct-public"))

            val config = when (method) {
                SchemaConfigMethod.FROM_SDL -> SchemaConfiguration.fromSdl(
                    sdl,
                    scopes = setOf(schemaId.toScopeConfig())
                )
                SchemaConfigMethod.FROM_RESOURCES -> SchemaConfiguration.fromResources(
                    scopes = setOf(schemaId.toScopeConfig())
                )
                SchemaConfigMethod.FROM_SCHEMA -> {
                    val schema = ViaductSchema(
                        SchemaGenerator().makeExecutableSchema(
                            SchemaParser().parse(sdl).apply {
                                DefaultSchemaProvider.addDefaults(this)
                            },
                            RuntimeWiring.MOCKED_WIRING
                        )
                    )
                    SchemaConfiguration.fromSchema(
                        schema,
                        scopes = setOf(schemaId.toScopeConfig())
                    )
                }
            }

            subject = StandardViaduct.Builder()
                .withFlagManager(flagManager)
                .withNoTenantAPIBootstrapper()
                .withSchemaConfiguration(config)
                .build()

            val query = """
                query TestQuery {
                    field
                }
            """.trimIndent()
            val executionInput = ExecutionInput.create(query, requestContext = object {})

            val syncResult = subject.execute(executionInput, schemaId)
            val asyncResult = subject.executeAsync(executionInput, schemaId).await()

            val expected = ExecutionResult.newExecutionResult()
                .data(mapOf("field" to null))
                .build()

            assertEquals(expected.toSpecification(), syncResult.toSpecification())
            assertEquals(expected.toSpecification(), asyncResult.toSpecification())
        }

    @Test
    fun `Execute scoped query with complex types`() =
        runBlocking {
            val sdl = """
                type Foo @scope(to: ["SCOPE1"]) {
                    field: Int
                }

                interface Bar @scope(to: ["SCOPE1"]) {
                    interfaceField: String
                }

                union MultiType @scope(to: ["SCOPE1"]) = Foo

                enum Status @scope(to: ["SCOPE1"]) {
                    ACTIVE
                    INACTIVE
                }

                input InputData @scope(to: ["SCOPE1"]) {
                    inputField: String
                }

                extend type Query @scope(to: ["SCOPE1"]) {
                    testQuery: Foo
                    interfaceTest: Bar
                    unionTest: MultiType
                    enumTest: Status
                }

                extend type Query @scope(to: ["SCOPE1"]) {
                    extensionTest: String
                }
            """.trimIndent()

            val schemaId = SchemaId.Scoped("SCHEMA_ID", setOf("SCOPE1"))
            val config = SchemaConfiguration.fromSdl(
                sdl,
                scopes = setOf(schemaId.toScopeConfig())
            )

            subject = StandardViaduct.Builder()
                .withFlagManager(flagManager)
                .withNoTenantAPIBootstrapper()
                .withSchemaConfiguration(config)
                .build()

            val query = """
                query TestQuery {
                    testQuery {
                        field
                    }
                    interfaceTest {
                        interfaceField
                    }
                    unionTest {
                        ... on Foo {
                            field
                        }
                    }
                    enumTest
                    extensionTest
                }
            """.trimIndent()
            val executionInput = ExecutionInput.create(query, requestContext = object {})

            val syncResult = subject.execute(executionInput, schemaId)
            val asyncResult = subject.executeAsync(executionInput, schemaId).await()

            val expected = ExecutionResult.newExecutionResult()
                .data(
                    mapOf(
                        "testQuery" to null,
                        "interfaceTest" to null,
                        "unionTest" to null,
                        "enumTest" to null,
                        "extensionTest" to null
                    )
                )
                .build()

            assertEquals(expected.toSpecification(), syncResult.toSpecification())
            assertEquals(expected.toSpecification(), asyncResult.toSpecification())
        }

    @Test
    fun `Scoped schema excludes fields from other scopes`() =
        runBlocking {
            val sdl = """
                type TestScope1Object @scope(to: ["SCOPE1"]) {
                    strValue: String!
                }

                extend type Query @scope(to: ["SCOPE1"]) {
                    scope1Value: TestScope1Object
                }

                type TestScope2Object @scope(to: ["SCOPE2"]) {
                    strValue: String!
                }

                extend type Query @scope(to: ["SCOPE2"]) {
                    scope2Value: TestScope2Object
                }
            """.trimIndent()

            val schemaId1 = SchemaId.Scoped("SCOPE1_ONLY", setOf("SCOPE1"))
            val schemaId2 = SchemaId.Scoped("SCOPE2_ONLY", setOf("SCOPE2"))

            subject = StandardViaduct.Builder()
                .withFlagManager(flagManager)
                .withNoTenantAPIBootstrapper()
                .withSchemaConfiguration(
                    SchemaConfiguration.fromSdl(
                        sdl,
                        scopes = setOf(
                            schemaId1.toScopeConfig(),
                            schemaId2.toScopeConfig()
                        )
                    )
                )
                .build()

            val query1 = """
                query {
                    scope2Value {
                        strValue
                    }
                }
            """.trimIndent()
            val executionInput1 = ExecutionInput.create(query1, requestContext = object {})
            val result1 = subject.executeAsync(executionInput1, schemaId1).await()

            assertEquals(1, result1.errors.size)
            assert(result1.errors[0].message.contains("Field 'scope2Value' in type 'Query' is undefined")) {
                "Expected validation error for scope2Value in SCOPE1_ONLY schema, but got: ${result1.errors[0].message}"
            }
            assertNull(result1.getData<Any>(), "Data should be null when validation fails")

            val query2 = """
                query {
                    scope1Value {
                        strValue
                    }
                }
            """.trimIndent()
            val executionInput2 = ExecutionInput.create(query2, requestContext = object {})
            val result2 = subject.executeAsync(executionInput2, schemaId2).await()

            assertEquals(1, result2.errors.size)
            assert(result2.errors[0].message.contains("Field 'scope1Value' in type 'Query' is undefined")) {
                "Expected validation error for scope1Value in SCOPE2_ONLY schema, but got: ${result2.errors[0].message}"
            }
            assertNull(result2.getData<Any>(), "Data should be null when validation fails")
        }

    @Test
    fun `Fails to execute query with unregistered schema ID`() =
        runBlocking {
            val sdl = """
                type TestScope1Object @scope(to: ["SCOPE1"]) {
                    strValue: String!
                }

                extend type Query @scope(to: ["SCOPE1"]) {
                    scope1Value: TestScope1Object
                }
            """.trimIndent()

            subject = StandardViaduct.Builder()
                .withFlagManager(flagManager)
                .withNoTenantAPIBootstrapper()
                .withSchemaConfiguration(
                    SchemaConfiguration.fromSdl(
                        sdl,
                        scopes = setOf(SchemaConfiguration.ScopeConfig("SCHEMA_ID_1", setOf("SCOPE1")))
                    )
                )
                .build()

            val query = """
                query {
                    scope1Value {
                        strValue
                    }
                }
            """.trimIndent()
            val executionInput = ExecutionInput.create(query, requestContext = object {})
            val result = subject.executeAsync(executionInput, SchemaId.None).await()

            assertEquals(1, result.errors.size)
            assertEquals("Schema not found for schemaId=SchemaId(id='NONE')", result.errors[0].message)
            assertNull(result.getData<Any>())
        }

    @Test
    fun `Build fails when Query field lacks scope directive`() {
        val sdl = """
            extend type Query @scope(to: ["publicScope"]) {
                helloWorld: String @resolver
            }

            extend type Query {
                scopeOmittedHelloWorld: String @resolver
            }
        """.trimIndent()

        val exception = assertThrows<SchemaScopeValidationError> {
            try {
                subject = StandardViaduct.Builder()
                    .withFlagManager(flagManager)
                    .withNoTenantAPIBootstrapper()
                    .withDataFetcherExceptionHandler(mockk())
                    .withSchemaConfiguration(
                        SchemaConfiguration.fromSdl(
                            sdl,
                            scopes = setOf(SchemaConfiguration.ScopeConfig("public", setOf("publicScope")))
                        )
                    )
                    .build()
            } catch (e: ProvisionException) {
                throw e.cause ?: e
            }
        }

        assert(exception.message!!.startsWith("No scope directives found from node: 'ObjectTypeExtensionDefinition{name='Query'")) {
            "Expected SchemaScopeValidationError for missing scope directive, but got: ${exception.message}"
        }
    }

    @Test
    fun `Register and query multiple independent scoped schemas`() =
        runBlocking {
            val sdl = """
                type TestScope1Object @scope(to: ["SCOPE1"]) {
                    strValue: String!
                }

                extend type Query @scope(to: ["SCOPE1"]) {
                    scope1Value: TestScope1Object
                }

                type TestScope2Object @scope(to: ["SCOPE2"]) {
                    strValue: String!
                }

                extend type Query @scope(to: ["SCOPE2"]) {
                    scope2Value: TestScope2Object
                }
            """.trimIndent()

            val schemaId1 = SchemaId.Scoped("SCHEMA_ID_1", setOf("SCOPE1"))
            val schemaId2 = SchemaId.Scoped("SCHEMA_ID_2", setOf("SCOPE2"))

            subject = StandardViaduct.Builder()
                .withFlagManager(flagManager)
                .withNoTenantAPIBootstrapper()
                .withSchemaConfiguration(
                    SchemaConfiguration.fromSdl(
                        sdl,
                        scopes = setOf(
                            schemaId1.toScopeConfig(),
                            schemaId2.toScopeConfig()
                        )
                    )
                )
                .build()

            val query1 = """
                query {
                    scope1Value {
                        strValue
                    }
                }
            """.trimIndent()
            val executionInput1 = ExecutionInput.create(query1, requestContext = object {})
            val result1 = subject.executeAsync(executionInput1, schemaId1).await()

            val expected1 = ExecutionResult.newExecutionResult()
                .data(mapOf("scope1Value" to null))
                .build()

            assertEquals(expected1.toSpecification(), result1.toSpecification())

            val query2 = """
                query {
                    scope2Value {
                        strValue
                    }
                }
            """.trimIndent()
            val executionInput2 = ExecutionInput.create(query2, requestContext = object {})
            val result2 = subject.executeAsync(executionInput2, schemaId2).await()

            val expected2 = ExecutionResult.newExecutionResult()
                .data(mapOf("scope2Value" to null))
                .build()

            assertEquals(expected2.toSpecification(), result2.toSpecification())
        }
}
