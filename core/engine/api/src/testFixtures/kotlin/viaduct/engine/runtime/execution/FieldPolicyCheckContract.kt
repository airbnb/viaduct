package viaduct.engine.runtime.execution

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.getAs

interface FieldPolicyCheckContract : QueryExecutionContract {
    val schemaSDL get() =
        """
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

    @Test
    fun `type returns if policy check passes on referenced node`() {
        val personType = MockSchema.mk(schemaSDL).schema.getObjectType("CanAccessPerson")
        var checkerCalled = false
        val module = EngineTestModule(schemaSDL) {
            field("Query" to "canAccessTypeByReference") {
                resolver {
                    fn { args, _, _, _, ctx ->
                        ctx.createNodeReference(args.getAs<String>("id"), personType)
                    }
                }
            }
            type("CanAccessPerson") {
                nodeUnbatchedExecutor { id, _, _ ->
                    createEngineObjectData(personType, mapOf("id" to id, "name" to "john", "ssn" to "social security number"))
                }
                checker { fn { _, _ -> checkerCalled = true } }
            }
        }
        assertQuery(
            module,
            """query { canAccessTypeByReference(id: "person1") { id name ssn } }""",
            """{"data":{"canAccessTypeByReference":{"id":"person1","name":"john","ssn":"social security number"}}}""",
        )
        assertTrue(checkerCalled)
    }

    @Test
    fun `throws if type is not accessible on referenced node`() {
        val personType = MockSchema.mk(schemaSDL).schema.getObjectType("CanNotAccessPerson")
        val module = EngineTestModule(schemaSDL) {
            field("Query" to "canNotAccessTypeByReference") {
                resolver {
                    fn { args, _, _, _, ctx ->
                        ctx.createNodeReference(args.getAs<String>("id"), personType)
                    }
                }
            }
            type("CanNotAccessPerson") {
                nodeUnbatchedExecutor { id, _, _ ->
                    createEngineObjectData(personType, mapOf("id" to id, "name" to "should not resolve", "ssn" to "should not resolve"))
                }
                checker { fn { _, _ -> throw RuntimeException("This field is not accessible") } }
            }
        }
        val result = executeQuery(module, """query { canNotAccessTypeByReference(id: "person1") { id name ssn } }""")
        assertNull(result.getData<Any?>())
        assertEquals(1, result.errors.size)
        assertEquals(listOf("canNotAccessTypeByReference"), result.errors.single().path)
        assertTrue(result.errors.single().message.contains("This field is not accessible"))
    }

    @Test
    fun `field should fail even if node passes`() {
        val personType = MockSchema.mk(schemaSDL).schema.getObjectType("CanAccessPerson")
        var checkerCalled = false
        val module = EngineTestModule(schemaSDL) {
            field("Query" to "canNotAccessType") {
                resolver { fn { _, _, _, _, ctx -> ctx.createNodeReference("someId", personType) } }
                checker { fn { _, _ -> throw RuntimeException("This field is not accessible") } }
            }
            type("CanAccessPerson") {
                nodeUnbatchedExecutor { id, _, _ ->
                    createEngineObjectData(personType, mapOf("id" to id, "name" to "john", "ssn" to "social security number"))
                }
                checker { fn { _, _ -> checkerCalled = true } }
            }
        }
        val result = executeQuery(module, "query { canNotAccessType { name ssn } }")
        assertNull(result.getData<Any?>())
        assertEquals(1, result.errors.size)
        assertEquals(listOf("canNotAccessType"), result.errors.single().path)
        assertTrue(result.errors.single().message.contains("This field is not accessible"))
        assertFalse(checkerCalled)
    }

    @Test
    fun `field returns if policy check passes`() {
        var checkerCalled = false
        val module = EngineTestModule(schemaSDL) {
            field("Query" to "canAccessField") {
                resolver { fn { _, _, _, _, _ -> "can see field" } }
                checker { fn { _, _ -> checkerCalled = true } }
            }
        }
        val result = executeQuery(module, "query { canAccessField }")
        assertEquals(mapOf("canAccessField" to "can see field"), result.toSpecification()["data"])
        assertTrue(result.errors.isEmpty())
        assertTrue(checkerCalled)
    }

    @Test
    fun `field does not return if policy check fails`() {
        val module = EngineTestModule(schemaSDL) {
            field("Query" to "canNotAccessField") {
                resolver { fn { _, _, _, _, _ -> "should not resolve" } }
                checker { fn { _, _ -> throw RuntimeException("This field is not accessible") } }
            }
        }
        val result = executeQuery(module, "query { canNotAccessField }")
        assertEquals(mapOf("canNotAccessField" to null), result.toSpecification()["data"])
        assertEquals(1, result.errors.size)
        assertEquals(listOf("canNotAccessField"), result.errors.single().path)
        assertTrue(result.errors.single().message.contains("This field is not accessible"))
    }
}
