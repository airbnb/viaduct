package viaduct.engine.api.mocks

import graphql.schema.GraphQLObjectType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.engine.api.ViaductSchema

class MockEngineObjectDataTest {
    private fun ViaductSchema.obj(name: String): GraphQLObjectType = schema.getObjectType(name)

    @Test
    fun `wrap -- empty`() {
        MockSchema.mk("extend type Query { empty: Int }")
            .apply {
                val eod = MockEngineObjectData.wrap(obj("Query"), emptyMap())
                assertEquals(MockEngineObjectData(obj("Query"), emptyMap()), eod)
            }
    }

    @Test
    fun `wrap -- nested`() {
        MockSchema.mk(
            """
                extend type Query { empty: Int }
                type Foo { x: Int, foo: Foo }
            """.trimIndent()
        ).apply {
            val eod = MockEngineObjectData.wrap(obj("Foo"), mapOf("x" to 1, "foo" to mapOf("x" to 2, "foo" to null)))
            assertEquals(
                MockEngineObjectData(
                    obj("Foo"),
                    mapOf(
                        "x" to 1,
                        "foo" to MockEngineObjectData(
                            obj("Foo"),
                            mapOf("x" to 2, "foo" to null)
                        )
                    )
                ),
                eod
            )
        }
    }

    @Test
    fun `wrap -- list`() {
        MockSchema.mk(
            """
                extend type Query { empty: Int }
                type Foo { xs: [Int], foos: [Foo] }
            """.trimIndent()
        ).apply {
            val eod = MockEngineObjectData.wrap(
                obj("Foo"),
                mapOf(
                    "xs" to emptyList(),
                    "foos" to listOf(
                        mapOf(
                            "xs" to listOf(21, 22),
                            "foos" to emptyList()
                        ),
                        mapOf()
                    )
                )
            )
            assertEquals(
                MockEngineObjectData(
                    obj("Foo"),
                    mapOf(
                        "xs" to emptyList<Int>(),
                        "foos" to listOf(
                            MockEngineObjectData(
                                obj("Foo"),
                                mapOf(
                                    "xs" to listOf(21, 22),
                                    "foos" to emptyList()
                                )
                            ),
                            MockEngineObjectData(obj("Foo"), emptyMap())
                        )
                    )
                ),
                eod
            )
        }
    }
}
