@file:Suppress("ForbiddenImport")

package viaduct.service.runtime

import graphql.ExecutionResult
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import viaduct.engine.api.ViaductSchema
import viaduct.graphql.utils.DefaultSchemaProvider
import viaduct.service.api.ExecutionInput
import viaduct.service.api.spi.Flag
import viaduct.service.api.spi.FlagManager

/**
 * Integration tests for Viaduct schema registration functionality.
 *
 * These tests validate:
 * - Different schema registration APIs (SDL, resources, full schema)
 * - Schema ID routing and error handling
 * - Scoped schema functionality
 * - Multi-schema registration and isolation
 */
@ExperimentalCoroutinesApi
class ViaductSchemaRegistrationIntegrationTest {
    private lateinit var subject: StandardViaduct

    val flagManager = object : FlagManager {
        override fun isEnabled(flag: Flag) = true
    }

    val wiring = RuntimeWiring.MOCKED_WIRING

    // Test equivalent to RegisterSchemaFromResourcesTest - basic scoped schema functionality
    @Test
    fun `Resolve SCOPE1 and SCOPE2 fields with scoped schemas`() =
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
        """

            subject = StandardViaduct.Builder()
                .withFlagManager(flagManager)
                .withNoTenantAPIBootstrapper()
                .withSchemaRegistryConfiguration(
                    SchemaRegistryConfiguration.fromSdl(
                        sdl,
                        scopes =
                            setOf(
                                SchemaRegistryConfiguration.ScopeConfig("SCHEMA_ID_1", setOf("SCOPE1")),
                                SchemaRegistryConfiguration.ScopeConfig("SCHEMA_ID_2", setOf("SCOPE2"))
                            )
                    )
                )
                .build()

            // Test SCOPE1 query with SCHEMA_ID_1
            val query1 = """
            query {
                scope1Value {
                    strValue
                }
            }
            """.trimIndent()
            val executionInput1 = ExecutionInput.create("SCHEMA_ID_1", query1, requestContext = object {})
            val result1 = subject.executeAsync(executionInput1).await()

            val expected1 = ExecutionResult.newExecutionResult()
                .data(mapOf("scope1Value" to null)) // Mocked wiring returns null
                .build()

            assertEquals(expected1.toSpecification(), result1.toSpecification())

            // Test SCOPE2 query with SCHEMA_ID_2
            val query2 = """
            query {
                scope2Value {
                    strValue
                }
            }
            """.trimIndent()
            val executionInput2 = ExecutionInput.create("SCHEMA_ID_2", query2, requestContext = object {})
            val result2 = subject.executeAsync(executionInput2).await()

            val expected2 = ExecutionResult.newExecutionResult()
                .data(mapOf("scope2Value" to null)) // Mocked wiring returns null
                .build()

            assertEquals(expected2.toSpecification(), result2.toSpecification())
        }

    // Test equivalent to RegisterSchemaFromResources1Test/2Test - scoped schema isolation
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
            """

            subject = StandardViaduct.Builder()
                .withFlagManager(flagManager)
                .withNoTenantAPIBootstrapper()
                .withSchemaRegistryConfiguration(
                    SchemaRegistryConfiguration.fromSdl(
                        sdl,
                        scopes =
                            setOf(
                                SchemaRegistryConfiguration.ScopeConfig("SCOPE1_ONLY", setOf("SCOPE1")),
                                SchemaRegistryConfiguration.ScopeConfig("SCOPE2_ONLY", setOf("SCOPE2"))
                            )
                    )
                )
                .build()

            // Try to query SCOPE2 field with SCOPE1_ONLY schema - should fail validation
            val query = """
            query {
                scope2Value {
                    strValue
                }
            }
            """.trimIndent()
            val executionInput = ExecutionInput.create(schemaId = "SCOPE1_ONLY", operationText = query, requestContext = object {})
            val result = subject.executeAsync(executionInput).await()

            // Should get validation error because scope2Value is not available in SCOPE1_ONLY schema
            // The original test validated that scoped schemas properly exclude fields from other scopes
            assertEquals(1, result.errors.size)
            assert(result.errors[0].message.contains("Field 'scope2Value' in type 'Query' is undefined")) {
                "Expected validation error message, but got: ${result.errors[0].message}"
            }
            // Verify this is a validation error (GraphQL validation happens before execution)
            assertNull(result.getData<Any>(), "Data should be null when validation fails")

            // Test the reverse - SCOPE2_ONLY should fail when querying SCOPE1 fields
            val reverseQuery = """
            query {
                scope1Value {
                    strValue
                }
            }
            """.trimIndent()
            val reverseExecutionInput = ExecutionInput.create(schemaId = "SCOPE2_ONLY", operationText = reverseQuery, requestContext = object {})
            val reverseResult = subject.executeAsync(reverseExecutionInput).await()

            assertEquals(1, reverseResult.errors.size)
            assert(reverseResult.errors[0].message.contains("Field 'scope1Value' in type 'Query' is undefined")) {
                "Expected validation error for scope1Value in SCOPE2_ONLY schema, but got: ${reverseResult.errors[0].message}"
            }
            assertNull(reverseResult.getData<Any>(), "Data should be null when validation fails")
        }

    // Test equivalent to RegisterSchemaFromResources3Test - missing schema ID error
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
            """

            subject = StandardViaduct.Builder()
                .withFlagManager(flagManager)
                .withNoTenantAPIBootstrapper()
                .withSchemaRegistryConfiguration(
                    SchemaRegistryConfiguration.fromSdl(
                        sdl,
                        // Note: not registering SCHEMA_ID_2
                        scopes = setOf(SchemaRegistryConfiguration.ScopeConfig("SCHEMA_ID_1", setOf("SCOPE1")))
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
            val executionInput = ExecutionInput.create(schemaId = "SCHEMA_ID_2", operationText = query, requestContext = object {}) // Unregistered schema ID
            val result = subject.executeAsync(executionInput).await()

            // The original test expected this to fail with "Schema not found" error
            assertEquals(1, result.errors.size)
            assertEquals("Schema not found for schemaId=SCHEMA_ID_2", result.errors[0].message)
            assertNull(result.getData<Any>())
        }

    // Test equivalent to RegisterFullSchemaTest - custom full schema registration
    @Test
    fun `Register and query custom full schema`() =
        runBlocking {
            val sdl = """
                type TestScope1Object {
                  strValue: String!
                }

                extend type Query {
                  scope1Value: TestScope1Object
                }

                type TestScope2Object {
                  strValue: String!
                }

                extend type Query {
                  scope2Value: TestScope2Object
                }
            """

            // Create ViaductSchema object first, then use withFullSchema()
            val schema = mkSchema(sdl)

            subject = StandardViaduct.Builder()
                .withFlagManager(flagManager)
                .withNoTenantAPIBootstrapper()
                .withSchemaRegistryConfiguration(
                    SchemaRegistryConfiguration.fromSchema(
                        schema,
                        fullSchemaIds = listOf("FULL_SCHEMA")
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
            val executionInput = ExecutionInput.create("FULL_SCHEMA", query, requestContext = object {})
            val result = subject.executeAsync(executionInput).await()

            val expected = ExecutionResult.newExecutionResult()
                .data(mapOf("scope1Value" to null)) // Mocked wiring returns null
                .build()

            assertEquals(expected.toSpecification(), result.toSpecification())
        }
}

/**
 * Helper function to create a ViaductSchema from SDL string, similar to the original test.
 */
private fun mkSchema(sdl: String): ViaductSchema =
    ViaductSchema(
        SchemaGenerator().makeExecutableSchema(
            SchemaParser().parse(sdl).apply {
                DefaultSchemaProvider.addDefaults(this)
            },
            RuntimeWiring.MOCKED_WIRING
        )
    )
