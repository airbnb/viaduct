package viaduct.engine.runtime.execution

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.runFeatureTest

/**
 * Test suite for the [viaduct.engine.api.EngineExecutionContext.resolveSelectionSetSync] API.
 *
 * resolveSelectionSetSync returns an [EngineObjectData.Sync] with all fields eagerly resolved
 * before the method returns.
 */
class ResolveSelectionSetTest {
    // ==================== resolveSelectionSetSync ====================

    @Test
    fun `resolveSelectionSetSync returns EngineObjectData Sync with scalar fields`() {
        EngineTestModule(
            """
            extend type Query {
                name: String
                age: Int
                container: Container
            }
            type Container {
                result: String
            }
            """.trimIndent()
        ) {
            fieldWithValue("Query" to "name", "Alice")
            fieldWithValue("Query" to "age", 30)

            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Container"),
                            mapOf()
                        )
                    }
                }
            }

            field("Container" to "result") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        val selectionSet = ctx.engineSelectionSetFactory
                            .engineSelectionSet("Query", "name age", emptyMap())
                        val data = ctx.resolveSelectionSetSync(selectionSet)
                        assertIs<EngineObjectData.Sync>(data)
                        "name=${data.get("name")}, age=${data.get("age")}"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ container { result } }")
                .assertJson("""{"data": {"container": {"result": "name=Alice, age=30"}}}""")
        }
    }

    @Test
    fun `resolveSelectionSetSync data supports getOrNull`() {
        EngineTestModule(
            """
            extend type Query {
                name: String
                container: Container
            }
            type Container {
                result: String
            }
            """.trimIndent()
        ) {
            fieldWithValue("Query" to "name", "Bob")

            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Container"),
                            mapOf()
                        )
                    }
                }
            }

            field("Container" to "result") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        val selectionSet = ctx.engineSelectionSetFactory
                            .engineSelectionSet("Query", "name", emptyMap())
                        val data = ctx.resolveSelectionSetSync(selectionSet)
                        assertIs<EngineObjectData.Sync>(data)
                        val name = data.getOrNull("name")
                        assertNotNull(name)
                        "name=$name"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ container { result } }")
                .assertJson("""{"data": {"container": {"result": "name=Bob"}}}""")
        }
    }

    @Test
    fun `resolveSelectionSetSync data supports getSelections`() {
        EngineTestModule(
            """
            extend type Query {
                x: Int
                y: String
                container: Container
            }
            type Container {
                result: String
            }
            """.trimIndent()
        ) {
            fieldWithValue("Query" to "x", 1)
            fieldWithValue("Query" to "y", "two")

            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Container"),
                            mapOf()
                        )
                    }
                }
            }

            field("Container" to "result") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        val selectionSet = ctx.engineSelectionSetFactory
                            .engineSelectionSet("Query", "x y", emptyMap())
                        val data = ctx.resolveSelectionSetSync(selectionSet)
                        assertIs<EngineObjectData.Sync>(data)
                        val selections = data.getSelections().toList()
                        assertEquals(2, selections.size)
                        assertTrue(selections.contains("x"))
                        assertTrue(selections.contains("y"))
                        "selections=${selections.sorted().joinToString(",")}"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ container { result } }")
                .assertJson("""{"data": {"container": {"result": "selections=x,y"}}}""")
        }
    }

    @Test
    fun `resolveSelectionSetSync works with mutation operation type`() {
        EngineTestModule(
            """
            extend type Query {
                container: Container
            }
            extend type Mutation {
                doSomething: String
            }
            type Container {
                result: String
            }
            """.trimIndent()
        ) {
            fieldWithValue("Mutation" to "doSomething", "done")

            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Container"),
                            mapOf()
                        )
                    }
                }
            }

            field("Container" to "result") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        val selectionSet = ctx.engineSelectionSetFactory
                            .engineSelectionSet("Mutation", "doSomething", emptyMap())
                        val data = ctx.resolveSelectionSetSync(selectionSet, ResolveSelectionSetOptions.MUTATION)
                        assertIs<EngineObjectData.Sync>(data)
                        "mutationResult=${data.get("doSomething")}"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ container { result } }")
                .assertJson("""{"data": {"container": {"result": "mutationResult=done"}}}""")
        }
    }

    @Test
    fun `resolveSelectionSetSync returns EngineObjectData Sync for nested objects`() {
        EngineTestModule(
            """
            extend type Query {
                person: Person
                container: Container
            }
            type Person {
                name: String
                age: Int
            }
            type Container {
                result: String
            }
            """.trimIndent()
        ) {
            field("Query" to "person") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Person"),
                            mapOf("name" to "Dave", "age" to 40)
                        )
                    }
                }
            }

            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Container"),
                            mapOf()
                        )
                    }
                }
            }

            field("Container" to "result") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        val selectionSet = ctx.engineSelectionSetFactory
                            .engineSelectionSet("Query", "person { name age }", emptyMap())
                        val data = ctx.resolveSelectionSetSync(selectionSet)
                        assertIs<EngineObjectData.Sync>(data)
                        val person = data.get("person")
                        assertIs<EngineObjectData.Sync>(person)
                        "name=${person.get("name")}, age=${person.get("age")}"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ container { result } }")
                .assertJson("""{"data": {"container": {"result": "name=Dave, age=40"}}}""")
        }
    }
}
