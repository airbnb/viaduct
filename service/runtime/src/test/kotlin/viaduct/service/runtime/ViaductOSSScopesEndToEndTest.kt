@file:Suppress("ForbiddenImport")

package viaduct.service.runtime

import graphql.ExecutionResult
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import viaduct.engine.api.ViaductSchema
import viaduct.service.api.ExecutionInput
import viaduct.service.api.SchemaId
import viaduct.service.api.spi.Flag
import viaduct.service.api.spi.FlagManager

/**
 * End-to-end tests for the Viaduct OSS interface.
 *
 * As we expand the OSS interface to include more of the Viaduct Modern surface area, these test will expand to cover
 * the end-to-end constract of the Viaduct OSS framework.
 */
@ExperimentalCoroutinesApi
class ViaductOSSScopesEndToEndTest {
    private lateinit var subject: StandardViaduct
    private lateinit var schemaConfiguration: SchemaConfiguration

    val flagManager = object : FlagManager {
        override fun isEnabled(flag: Flag) = true
    }

    val wiring = RuntimeWiring.MOCKED_WIRING // TODO: Replace with injected OSS/modern-only wiring

    @Test
    fun `Verify Builder using withFullSchemaFromSdl and registerSchema`() =
        runBlocking {
            val sdl = """
                extend type Query @scope(to: ["viaduct-public"]) { field: Int }
            """

            val schemaId = SchemaId.Scoped("public", setOf("viaduct-public"))
            schemaConfiguration = SchemaConfiguration.fromSdl(
                sdl,
                scopes = setOf(schemaId.toScopeConfig())
            )
            subject = StandardViaduct.Builder()
                .withFlagManager(flagManager)
                .withNoTenantAPIBootstrapper()
                .withSchemaConfiguration(schemaConfiguration)
                .build()

            val query = """
                    query TestQuery {
                        field
                    }
            """.trimIndent()
            val executionInput = ExecutionInput.create(query)

            val actual = subject.execute(executionInput, schemaId)
            val actualAsynced = subject.executeAsync(executionInput, schemaId).await()
            // Having an intermittent bug in synchronicity.  This is a workaround to ensure the execution

            val expected = ExecutionResult.newExecutionResult()
                .data(mapOf("field" to null))
                .build()

            assertEquals(expected.toSpecification(), actual.toSpecification())
            assertEquals(expected.toSpecification(), actualAsynced.toSpecification())
            // By the transitive property the following should never fail, but we're including it here out of paranoia
            assertEquals(actual.toSpecification(), actualAsynced.toSpecification())
        }

    @Test
    fun `Verify Builder using withFullSchemaFromFiles and registerSchema`() =
        runBlocking {
            val schemaId = SchemaId.Scoped("public", setOf("viaduct-public"))
            schemaConfiguration = SchemaConfiguration.fromResources(
                scopes = setOf(schemaId.toScopeConfig())
            )
            subject = StandardViaduct.Builder()
                .withFlagManager(flagManager)
                .withNoTenantAPIBootstrapper()
                .withSchemaConfiguration(schemaConfiguration)
                .build()

            val query = """
                    query TestQuery {
                        field
                    }
            """.trimIndent()
            val executionInput = ExecutionInput.create(operationText = query, requestContext = object {})

            val actual = subject.execute(executionInput, schemaId)
            val actualAsynced = subject.executeAsync(executionInput, schemaId).await()
            val expected = ExecutionResult.newExecutionResult()
                .data(mapOf("field" to null))
                .build()

            assertEquals(expected.toSpecification(), actual.toSpecification())
            assertEquals(expected.toSpecification(), actualAsynced.toSpecification())
            // By the transitive property the following should never fail, but we're including it here out of paranoia
            assertEquals(actual.toSpecification(), actualAsynced.toSpecification())
        }

    @Test
    fun `Verify Builder using registerSchemaFromSdl and registerScopedSchema`() =
        runBlocking {
            val sdl = """
            extend type Query @scope(to: ["viaduct-public"]) { field: Int }
        """

            val schemaId = SchemaId.Scoped("public", setOf("viaduct-public"))
            schemaConfiguration =
                SchemaConfiguration.fromSdl(
                    sdl,
                    scopes = setOf(schemaId.toScopeConfig())
                )
            subject = StandardViaduct.Builder()
                .withFlagManager(flagManager)
                .withNoTenantAPIBootstrapper()
                .withSchemaConfiguration(schemaConfiguration)
                .build()

            val query = """
                query TestQuery {
                    field
                }
            """.trimIndent()
            val executionInput = ExecutionInput.create(operationText = query, requestContext = object {})

            val actual = subject.execute(executionInput, schemaId)
            val actualAsynced = subject.executeAsync(executionInput, schemaId).await()
            // Having an intermittent bug in synchronicity. This is a workaround to ensure the execution

            val expected = ExecutionResult.newExecutionResult()
                .data(mapOf("field" to null))
                .build()

            assertEquals(expected.toSpecification(), actual.toSpecification())
            assertEquals(expected.toSpecification(), actualAsynced.toSpecification())
            // By the transitive property the following should never fail, but we're including it here out of paranoia
            assertEquals(actual.toSpecification(), actualAsynced.toSpecification())
        }

    @Test
    fun `Verify Builder using withFullSchemaFromSdl and registerScopedSchema`() =
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
            """
            val schemaId = SchemaId.Scoped("SCHEMA_ID", setOf("SCOPE1"))
            schemaConfiguration = SchemaConfiguration.fromSdl(
                sdl,
                scopes = setOf(schemaId.toScopeConfig())
            )
            subject = StandardViaduct.Builder()
                .withFlagManager(flagManager)
                .withNoTenantAPIBootstrapper()
                .withSchemaConfiguration(schemaConfiguration)
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
            val executionInput = ExecutionInput.create(operationText = query, requestContext = object {})
            val actualAsynced = subject.executeAsync(executionInput, schemaId).await()
            val actual = subject.execute(executionInput)
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
            assertEquals(expected.toSpecification(), actual.toSpecification())
            assertEquals(expected.toSpecification(), actualAsynced.toSpecification())
            // By the transitive property the following should never fail, but we're including it here out of paranoia
            assertEquals(actual.toSpecification(), actualAsynced.toSpecification())
        }

    @Test
    fun `Verify Builder using registerFullSchema`() =
        runBlocking {
            val schemaId = SchemaId.Scoped("public", setOf("viaduct-public"))
            schemaConfiguration = SchemaConfiguration.fromResources(
                scopes = setOf(schemaId.toScopeConfig())
            )
            subject = StandardViaduct.Builder()
                .withFlagManager(flagManager)
                .withNoTenantAPIBootstrapper()
                .withSchemaConfiguration(schemaConfiguration)
                .build()

            val query = """
            query TestQuery {
                field
            }
            """.trimIndent()
            val executionInput = ExecutionInput.create(operationText = query, requestContext = object {})

            val actual = subject.execute(executionInput, schemaId)
            val actualAsynced = subject.executeAsync(executionInput, schemaId).await()
            // Having an intermittent bug in synchronicity. This is a workaround to ensure the execution

            val expected = ExecutionResult.newExecutionResult()
                .data(mapOf("field" to null))
                .build()

            assertEquals(expected.toSpecification(), actual.toSpecification())
            assertEquals(expected.toSpecification(), actualAsynced.toSpecification())
            // By the transitive property the following should never fail, but we're including it here out of paranoia
            assertEquals(actual.toSpecification(), actualAsynced.toSpecification())
        }

    @Test
    fun `Verify Builder using withScopedFuture`() =
        runBlocking {
            val sdl = """
        directive @scope(to: [String!]!) repeatable on OBJECT | INPUT_OBJECT | ENUM | INTERFACE | UNION

        schema { query: Foo }
        type Foo @scope(to: ["viaduct-public"]) { field: Int }
    """
            val schema = ViaductSchema(SchemaGenerator().makeExecutableSchema(SchemaParser().parse(sdl), RuntimeWiring.MOCKED_WIRING))

            val schemaId = SchemaId.Scoped("public", setOf("viaduct-public"))
            schemaConfiguration = SchemaConfiguration.fromSchema(
                schema,
                scopes = setOf(schemaId.toScopeConfig())
            )
            subject = StandardViaduct.Builder()
                .withFlagManager(flagManager)
                .withNoTenantAPIBootstrapper()
                .withSchemaConfiguration(schemaConfiguration)
                .build()

            val query = """
                query TestQuery {
                    field
                }
            """.trimIndent()
            val executionInput = ExecutionInput.create(operationText = query, requestContext = object {})

            val actual = subject.execute(executionInput, schemaId)
            val actualAsynced = subject.executeAsync(executionInput, schemaId).await()
            // Having an intermittent bug in synchronicity. This is a workaround to ensure the execution

            val expected = ExecutionResult.newExecutionResult()
                .data(mapOf("field" to null))
                .build()

            assertEquals(expected.toSpecification(), actual.toSpecification())
            assertEquals(expected.toSpecification(), actualAsynced.toSpecification())
            // By the transitive property the following should never fail, but we're including it here out of paranoia
            assertEquals(actual.toSpecification(), actualAsynced.toSpecification())
        }
}
