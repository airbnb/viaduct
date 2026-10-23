package viaduct.engine.runtime.execution

import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.fetchAs
import viaduct.graphql.test.assertJson

interface SelectiveNodeExecutionContract : QueryExecutionContract {
    @Test
    fun `simple selective node`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo implements Node { id: ID!, x:Int, y:Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext { it.createNodeReference("id", schema.schema.getObjectType("Foo")!!) }
            }
            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    createEngineObjectData(
                        objectType,
                        buildMap {
                            if (sels!!.containsField("Foo", "x")) {
                                put("x", 2)
                            }
                        }
                    )
                }
            }
            field("Foo" to "y") {
                resolver {
                    objectSelections("x")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("x") * 3 }
                }
            }
        }.toEngineTestModule().runContractTest {
            runQuery("{ foo { y } }").assertJson("{data: {foo: {y: 6}}}")
        }
    }

    @Test
    fun `list of selective nodes`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foos: [Foo] }
                type Foo implements Node { id: ID!, x:Int, y:Int }
            """.trimIndent()
        ) {
            field("Query" to "foos") {
                valueFromContext {
                    listOf(
                        it.createNodeReference("1", schema.schema.getObjectType("Foo")!!),
                        it.createNodeReference("2", schema.schema.getObjectType("Foo")!!),
                    )
                }
            }
            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { id, sels, _ ->
                    createEngineObjectData(
                        objectType,
                        buildMap {
                            if (sels!!.containsField("Foo", "x")) {
                                put("x", id.toInt() * 2)
                            }
                        }
                    )
                }
            }
            field("Foo" to "y") {
                resolver {
                    objectSelections("x")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("x") * 3 }
                }
            }
        }.toEngineTestModule().runContractTest {
            runQuery("{ foos { y } }").assertJson("{data: {foos: [{y:6}, {y:12}]}}")
        }
    }

    @Test
    fun `nested list of selective nodes`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foos: [[Foo]] }
                type Foo implements Node { id: ID!, x:Int, y:Int }
            """.trimIndent()
        ) {
            field("Query" to "foos") {
                valueFromContext {
                    listOf(
                        listOf(
                            it.createNodeReference("1", schema.schema.getObjectType("Foo")!!),
                            it.createNodeReference("2", schema.schema.getObjectType("Foo")!!),
                        ),
                        listOf(
                            it.createNodeReference("3", schema.schema.getObjectType("Foo")!!),
                            it.createNodeReference("4", schema.schema.getObjectType("Foo")!!),
                        )
                    )
                }
            }
            type("Foo") {
                nodeUnbatchedExecutor(selective = true) { id, sels, _ ->
                    createEngineObjectData(
                        objectType,
                        buildMap {
                            if (sels!!.containsField("Foo", "x")) {
                                put("x", id.toInt() * 2)
                            }
                        }
                    )
                }
            }
            field("Foo" to "y") {
                resolver {
                    objectSelections("x")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("x") * 3 }
                }
            }
        }.toEngineTestModule().runContractTest {
            runQuery("{ foos { y } }")
                .assertJson("{data: {foos: [[{y:6}, {y:12}], [{y:18}, {y:24}]]}}")
        }
    }

    @Test
    fun `same node resolves different fields`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo { barA: Bar, barB:Bar, x:Int  }
                type Bar implements Node { id: ID!, x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                valueFromContext {
                    createEngineObjectData(schema.schema.getObjectType("Foo")!!, emptyMap())
                }
            }

            field("Foo" to "barA") {
                valueFromContext { ctx ->
                    ctx.createNodeReference("bar", schema.schema.getObjectType("Bar")!!)
                }
            }
            field("Foo" to "barB") {
                valueFromContext { ctx ->
                    ctx.createNodeReference("bar", schema.schema.getObjectType("Bar")!!)
                }
            }
            field("Foo" to "x") {
                resolver {
                    objectSelections("barA { x } barB { y }")
                    fn { _, obj, _, _, _ ->
                        val barA = obj.fetchAs<EngineObjectData>("barA")
                        val barB = obj.fetchAs<EngineObjectData>("barB")
                        barA.fetchAs<Int>("x") * barB.fetchAs<Int>("y")
                    }
                }
            }

            type("Bar") {
                nodeUnbatchedExecutor(selective = true) { _, sels, _ ->
                    val values = buildMap {
                        if (sels!!.containsField("Bar", "x")) {
                            put("x", 2)
                        }
                        if (sels.containsField("Bar", "y")) {
                            put("y", 3)
                        }
                    }

                    createEngineObjectData(objectType, values)
                }
            }
        }.toEngineTestModule().runContractTest(withoutDefaultQueryNodeResolvers = true) {
            withTimeout("{ foo { x } }")
                .assertJson("{ data: { foo: { x: 6 } } }")
        }
    }
}
