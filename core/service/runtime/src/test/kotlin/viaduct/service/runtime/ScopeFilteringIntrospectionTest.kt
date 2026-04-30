@file:Suppress("ForbiddenImport")

package viaduct.service.runtime

import io.mockk.mockk
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.service.api.ExecutionInput
import viaduct.service.api.SchemaId
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.FlagManager.Flag

/**
 * Tests that introspection results are properly filtered by scope.
 *
 * Verifies that fields belonging to scopes not registered in the SchemaId are excluded
 * from introspection queries, preventing information leakage across scope boundaries.
 */
@ExperimentalCoroutinesApi
class ScopeFilteringIntrospectionTest {
    private lateinit var subject: StandardViaduct
    private lateinit var schemaId: SchemaId.Scoped
    private lateinit var schemaConfiguration: SchemaConfiguration

    private val flagManager = object : FlagManager {
        override fun isEnabled(flag: Flag) = true
    }

    private val sdl =
        """
        extend type Query @scope(to: ["publicScope"]) {
          helloWorld: String @resolver
        }

        extend type Query @scope(to: ["unregisteredScope"]) {
          scopeUnregisteredQuery: String @resolver
        }

        type Foo implements Node @scope(to: ["*"]) { # Ensure Query.node/s get created
          id: ID!
        }
        """

    @BeforeEach
    fun setUp() {
        schemaId = SchemaId.Scoped("public", setOf("publicScope"))
        schemaConfiguration = SchemaConfiguration.fromSdl(
            sdl,
            scopes = setOf(schemaId.toScopeConfig())
        )
        subject = StandardViaduct.Builder()
            .withFlagManager(flagManager)
            .withNoTenantAPIBootstrapper()
            .withDataFetcherExceptionHandler(mockk())
            .withSchemaConfiguration(schemaConfiguration)
            .build()
    }

    @Test
    fun `introspection results exclude fields from unregistered scopes`() =
        runBlocking {
            // Schema defines fields in both "publicScope" and "unregisteredScope"
            // Only "publicScope" is registered in the SchemaId
            // Verify that introspection only returns fields from "publicScope"
            val query = """
                query IntrospectionQuery {
                      __schema {
                        queryType {
                          name
                        }
                        mutationType {
                          name
                        }
                        subscriptionType {
                          name
                        }
                        types {
                          ...FullType
                        }
                        directives {
                          name
                          description
                          locations
                          args {
                            ...InputValue
                          }
                        }
                      }
                    }
                    fragment FullType on __Type {
                      kind
                      name
                      description
                      fields(includeDeprecated: true) {
                        name
                        description
                        args {
                          ...InputValue
                        }
                        type {
                          ...TypeRef
                        }
                        isDeprecated
                        deprecationReason
                      }
                      inputFields {
                        ...InputValue
                      }
                      interfaces {
                        ...TypeRef
                      }
                      enumValues(includeDeprecated: true) {
                        name
                        description
                        isDeprecated
                        deprecationReason
                      }
                      possibleTypes {
                        ...TypeRef
                      }
                    }
                    fragment InputValue on __InputValue {
                      name
                      description
                      type {
                        ...TypeRef
                      }
                      defaultValue
                    }
                    fragment TypeRef on __Type {
                      kind
                      name
                      ofType {
                        kind
                        name
                        ofType {
                          kind
                          name
                          ofType {
                            kind
                            name
                          }
                        }
                      }
                    }
            """.trimIndent()
            val executionInput = ExecutionInput.create(operationText = query, requestContext = object {})

            val result = subject.executeAsync(executionInput, schemaId).await()

            // Extract Query type field names from introspection result
            val queries = (result.toSpecification()["data"] as Map<*, *>).let { data ->
                (data["__schema"] as Map<*, *>).let { schema ->
                    (schema["types"] as List<*>).let { types ->
                        @Suppress("UNCHECKED_CAST")
                        val queryType = types.find { (it as? Map<String, Any?>)?.get("name") == "Query" }
                        (queryType as Map<*, *>).let { queries ->
                            (queries["fields"] as List<*>).let { fields ->
                                fields.map { field -> (field as Map<*, *>)["name"] }
                            }
                        }
                    }
                }
            }
            // Verify: "helloWorld" (publicScope) is present, "scopeUnregisteredQuery" (unregisteredScope) is absent
            // "node" and "nodes" are from universal scope ("*") and always present
            assertEquals(listOf("helloWorld", "node", "nodes"), queries)
        }
}
