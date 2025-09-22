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
import viaduct.service.api.spi.Flag
import viaduct.service.api.spi.FlagManager

/**
 * End-to-end scope validation tests.
 */
@ExperimentalCoroutinesApi
class IntrospectionEndToEndTest {
    private lateinit var subject: StandardViaduct
    private lateinit var viaductSchemaRegistryBuilder: ViaductSchemaRegistryBuilder

    val flagManager = object : FlagManager {
        override fun isEnabled(flag: Flag) = true
    }

    val sdl =
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
        viaductSchemaRegistryBuilder = ViaductSchemaRegistryBuilder().withFullSchemaFromSdl(sdl).registerScopedSchema("public", setOf("publicScope"))
        subject = StandardViaduct.Builder()
            .withFlagManager(flagManager)
            .withNoTenantAPIBootstrapper()
            .withDataFetcherExceptionHandler(mockk())
            .withSchemaRegistryBuilder(viaductSchemaRegistryBuilder)
            .build()
    }

    @Test
    fun `Unregistered scopes queries should not be part of introspection result`() =
        runBlocking {
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
            val executionInput = ExecutionInput(query, "public", object {})

            val result = subject.executeAsync(executionInput).await()

            // Get query names from the introspection result
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
            assertEquals(listOf("helloWorld", "node", "nodes"), queries)
        }

    @Test
    fun `Mixed introspection and query execution is not allowed`() =
        runBlocking {
            val query = """
                query IntrospectionQuery {
                      helloWorld
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
            val executionInput = ExecutionInput(query, "public", object {})

            val result = subject.executeAsync(executionInput).await()
            assertEquals(listOf("Introspective queries cannot select non-introspective fields."), result.errors.map { it.message })
        }

    @Test
    fun `Mixed introspection in fragment and query in execution is not allowed`() =
        runBlocking {
            val query = """
                query IntrospectionQuery {
                  helloWorld
                  __schema {
                    ...SchemaInfo
                  }
                }

                fragment SchemaInfo on __Schema {
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
            val executionInput = ExecutionInput(query, "public", object {})

            val result = subject.executeAsync(executionInput).await()
            assertEquals(listOf("Introspective queries cannot select non-introspective fields."), result.errors.map { it.message })
        }

    @Test
    fun `Mixed both introspection and query in fragments in execution is not allowed`() =
        runBlocking {
            val query = """
                query IntrospectionQuery {
                  ...HelloWorld
                  __schema {
                    ...SchemaInfo
                  }
                }

                fragment HelloWorld on Query {
                  helloWorld
                }

                fragment SchemaInfo on __Schema {
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
            val executionInput = ExecutionInput(query, "public", object {})

            val result = subject.executeAsync(executionInput).await()
            assertEquals(listOf("Introspective queries cannot select non-introspective fields."), result.errors.map { it.message })
        }

    @Test
    fun `Mixed introspection and query in fragment in execution is not allowed`() =
        runBlocking {
            val query = """
                query IntrospectionQuery {
                  ...HelloWorld
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

                fragment HelloWorld on Query {
                  helloWorld
                }
            """.trimIndent()
            val executionInput = ExecutionInput(query, "public", object {})

            val result = subject.executeAsync(executionInput).await()
            assertEquals(listOf("Introspective queries cannot select non-introspective fields."), result.errors.map { it.message })
        }
}
