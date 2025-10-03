package viaduct.engine.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.getAs
import viaduct.engine.api.mocks.mkEngineObjectData
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.graphql.test.assertEquals

@ExperimentalCoroutinesApi
class FieldPolicyCheckTest {
    companion object {
        val SDL = """
            extend type Query {
                canAccessTypeByReference(id: String!): CanAccessPerson!
                canNotAccessTypeByReference(id: String!): CanNotAccessPerson!
                canNotAccessType: CanNotAccessPerson!
                canAccessField: String
                canNotAccessField: String
            }

            type CanAccessPerson implements Node {
                id: ID!
                name: String!
                ssn: String!
            }

            type CanNotAccessPerson implements Node {
                id: ID!
                name: String!
                ssn: String!
            }
        """.trimIndent()

        private val schema = MockSchema.mk(SDL)
        private val canAccessPersonType = schema.schema.getObjectType("CanAccessPerson")
        private val canNotAccessPersonType = schema.schema.getObjectType("CanNotAccessPerson")
    }

    @Test
    fun `type returns if policy check passes on referenced node`() {
        val internalId = "person1"
        var canAccessPersonCheckerWasCalled = false

        MockTenantModuleBootstrapper(SDL) {
            field("Query" to "canAccessTypeByReference") {
                resolver {
                    fn { args, _, _, _, ctx ->
                        val id = args.getAs<String>("id")
                        ctx.createNodeEngineObjectData(id, canAccessPersonType)
                    }
                }
            }

            type("CanAccessPerson") {
                nodeUnbatchedExecutor { id, _, _ ->
                    mkEngineObjectData(
                        canAccessPersonType,
                        mapOf(
                            "id" to id,
                            "name" to "john",
                            "ssn" to "social security number"
                        )
                    )
                }
                checker {
                    fn { _, _ ->
                        canAccessPersonCheckerWasCalled = true
                        // Access granted - no exception thrown
                    }
                }
            }
        }.runFeatureTest {
            viaduct.runQuery("query { canAccessTypeByReference(id: \"${internalId}\") { id name ssn } }")
                .assertEquals {
                    "data" to {
                        "canAccessTypeByReference" to {
                            "id" to internalId
                            "name" to "john"
                            "ssn" to "social security number"
                        }
                    }
                }
        }

        assertTrue(canAccessPersonCheckerWasCalled)
    }

    @Test
    fun `throws if type is not accessible on referenced node`() {
        MockTenantModuleBootstrapper(SDL) {
            field("Query" to "canNotAccessTypeByReference") {
                resolver {
                    fn { args, _, _, _, ctx ->
                        val id = args.getAs<String>("id")
                        ctx.createNodeEngineObjectData(id, canNotAccessPersonType)
                    }
                }
            }

            type("CanNotAccessPerson") {
                nodeUnbatchedExecutor { id, _, _ ->
                    mkEngineObjectData(
                        canNotAccessPersonType,
                        mapOf(
                            "id" to id,
                            "name" to "should not resolve",
                            "ssn" to "should not resolve"
                        )
                    )
                }
                checker {
                    fn { _, _ -> throw RuntimeException("This field is not accessible") }
                }
            }
        }.runFeatureTest {
            val result = viaduct.runQuery("query { canNotAccessTypeByReference(id: \"person1\") { id name ssn } }")
            assertNull(result.getData())
            assertEquals(1, result.errors.size)
            result.errors.first().let { err ->
                assertEquals(listOf("canNotAccessTypeByReference"), err.path)
                assertTrue(err.message.contains("This field is not accessible"))
            }
        }
    }

    @Test
    fun `field should fail even if node passes`() {
        var canAccessPersonCheckerWasCalled = false

        MockTenantModuleBootstrapper(SDL) {
            field("Query" to "canNotAccessType") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createNodeEngineObjectData("someId", canAccessPersonType)
                    }
                }
                checker {
                    fn { _, _ -> throw RuntimeException("This field is not accessible") }
                }
            }

            type("CanAccessPerson") {
                nodeUnbatchedExecutor { id, _, _ ->
                    mkEngineObjectData(
                        canAccessPersonType,
                        mapOf(
                            "id" to id,
                            "name" to "john",
                            "ssn" to "social security number"
                        )
                    )
                }
                checker {
                    fn { _, _ ->
                        canAccessPersonCheckerWasCalled = true
                        // Access granted - no exception thrown
                    }
                }
            }
        }.runFeatureTest {
            val result = viaduct.runQuery("query { canNotAccessType { name ssn } }")
            assertNull(result.getData())
            assertEquals(1, result.errors.size)
            result.errors.first().let { err ->
                assertEquals(listOf("canNotAccessType"), err.path)
                assertTrue(err.message.contains("This field is not accessible"))
            }
        }

        // TODO: should this be true?  Will it change?
        // The type checker should NOT be called because the field checker fails first
        assertFalse(canAccessPersonCheckerWasCalled)
    }

    @Test
    fun `field returns if policy check passes`() {
        var canAccessFieldCheckerWasCalled = false

        MockTenantModuleBootstrapper(SDL) {
            field("Query" to "canAccessField") {
                resolver {
                    fn { _, _, _, _, _ -> "can see field" }
                }
                checker {
                    fn { _, _ ->
                        canAccessFieldCheckerWasCalled = true
                        // Access granted - no exception thrown
                    }
                }
            }
        }.runFeatureTest {
            viaduct.runQuery("query { canAccessField }")
                .assertEquals {
                    "data" to { "canAccessField" to "can see field" }
                }
        }

        assertTrue(canAccessFieldCheckerWasCalled)
    }

    @Test
    fun `field does not return if policy check fails`() {
        MockTenantModuleBootstrapper(SDL) {
            field("Query" to "canNotAccessField") {
                resolver {
                    fn { _, _, _, _, _ -> "should not resolve" }
                }
                checker {
                    fn { _, _ -> throw RuntimeException("This field is not accessible") }
                }
            }
        }.runFeatureTest {
            val result = viaduct.runQuery("query { canNotAccessField }")
            assertEquals(mapOf("canNotAccessField" to null), result.toSpecification()["data"])
            assertEquals(1, result.errors.size)
            val error = result.errors[0]
            assertTrue(error.message.contains("This field is not accessible"))
            assertEquals(listOf("canNotAccessField"), error.path)
        }
    }
}
