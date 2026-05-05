package viaduct.engine.runtime.execution

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.engine.api.mocks.MockLegacyTenantModuleBootstrapper
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.runFeatureTest

/**
 * Test suite for the resolveSelectionSetSync and resolveSelectionSet APIs.
 *
 * resolveSelectionSetSync returns an [EngineObjectData.Sync] with all fields eagerly resolved.
 * resolveSelectionSet returns an async [EngineObjectData] (ProxyEngineObjectData) whose
 * fields are resolved lazily in the background.
 *
 * Both methods share the same validation, query plan construction, and field resolution
 * logic -- they differ only in how the result is wrapped.
 *
 * Tests are organized into:
 * - resolveSelectionSetSync tests -- verifying EngineObjectData.Sync return type
 * - resolveSelectionSet (async) tests -- verifying async EngineObjectData return type
 * - Parity tests -- verifying both flavors produce equivalent results
 */
class ResolveSelectionSetTest {
    // ==================== resolveSelectionSetSync ====================

    @Test
    fun `resolveSelectionSetSync returns EngineObjectData Sync with scalar fields`() {
        MockLegacyTenantModuleBootstrapper(
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
        MockLegacyTenantModuleBootstrapper(
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
        MockLegacyTenantModuleBootstrapper(
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
        MockLegacyTenantModuleBootstrapper(
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

    // ==================== resolveSelectionSet (async) ====================

    @Test
    fun `resolveSelectionSet returns async EngineObjectData with scalar fields`() {
        MockLegacyTenantModuleBootstrapper(
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
            fieldWithValue("Query" to "name", "Charlie")
            fieldWithValue("Query" to "age", 25)

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
                        val data = ctx.resolveSelectionSet(selectionSet)
                        // Async version returns EngineObjectData, not EngineObjectData.Sync
                        val name = data.fetch("name")
                        val age = data.fetch("age")
                        "name=$name, age=$age"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ container { result } }")
                .assertJson("""{"data": {"container": {"result": "name=Charlie, age=25"}}}""")
        }
    }

    @Test
    fun `resolveSelectionSet supports fetchOrNull and fetchSelections`() {
        MockLegacyTenantModuleBootstrapper(
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
            fieldWithValue("Query" to "x", 10)
            fieldWithValue("Query" to "y", "hello")

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
                        val data = ctx.resolveSelectionSet(selectionSet)
                        val x = data.fetchOrNull("x")
                        val selections = data.fetchSelections().toList()
                        "x=$x, count=${selections.size}"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ container { result } }")
                .assertJson("""{"data": {"container": {"result": "x=10, count=2"}}}""")
        }
    }

    @Test
    fun `resolveSelectionSet works with mutation operation type`() {
        MockLegacyTenantModuleBootstrapper(
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
            fieldWithValue("Mutation" to "doSomething", "async-done")

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
                        val data = ctx.resolveSelectionSet(selectionSet, ResolveSelectionSetOptions.MUTATION)
                        "mutationResult=${data.fetch("doSomething")}"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ container { result } }")
                .assertJson("""{"data": {"container": {"result": "mutationResult=async-done"}}}""")
        }
    }

    // ==================== Parity tests ====================

    @Test
    fun `both flavors produce the same scalar values`() {
        MockLegacyTenantModuleBootstrapper(
            """
            extend type Query {
                value: Int
                container: Container
            }
            type Container {
                syncResult: String
                asyncResult: String
            }
            """.trimIndent()
        ) {
            fieldWithValue("Query" to "value", 42)

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

            field("Container" to "syncResult") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        val selectionSet = ctx.engineSelectionSetFactory
                            .engineSelectionSet("Query", "value", emptyMap())
                        val data = ctx.resolveSelectionSetSync(selectionSet)
                        assertIs<EngineObjectData.Sync>(data)
                        data.get("value").toString()
                    }
                }
            }

            field("Container" to "asyncResult") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        val selectionSet = ctx.engineSelectionSetFactory
                            .engineSelectionSet("Query", "value", emptyMap())
                        val data = ctx.resolveSelectionSet(selectionSet)
                        data.fetch("value").toString()
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ container { syncResult asyncResult } }")
                .assertJson("""{"data": {"container": {"syncResult": "42", "asyncResult": "42"}}}""")
        }
    }

    @Test
    fun `both flavors produce the same nested object values`() {
        MockLegacyTenantModuleBootstrapper(
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
                syncResult: String
                asyncResult: String
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

            field("Container" to "syncResult") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        val selectionSet = ctx.engineSelectionSetFactory
                            .engineSelectionSet("Query", "person { name age }", emptyMap())
                        val data = ctx.resolveSelectionSetSync(selectionSet)
                        assertIs<EngineObjectData.Sync>(data)
                        val person = data.get("person") as EngineObjectData.Sync
                        "name=${person.get("name")}, age=${person.get("age")}"
                    }
                }
            }

            field("Container" to "asyncResult") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        val selectionSet = ctx.engineSelectionSetFactory
                            .engineSelectionSet("Query", "person { name age }", emptyMap())
                        val data = ctx.resolveSelectionSet(selectionSet)
                        val person = data.fetch("person") as EngineObjectData
                        "name=${person.fetch("name")}, age=${person.fetch("age")}"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ container { syncResult asyncResult } }")
                .assertJson("""{"data": {"container": {"syncResult": "name=Dave, age=40", "asyncResult": "name=Dave, age=40"}}}""")
        }
    }
}
