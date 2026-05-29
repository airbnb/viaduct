@file:Suppress("ForbiddenImport")

package viaduct.arbitrary.graphql

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.runtime.select.EngineSelectionSetFactoryImpl

class EngineDataExerciserTest {
    private class Fixture(sdl: String, fn: Fixture.() -> Unit = {}) {
        val schema = sdl.asViaductSchema
        val factory = EngineSelectionSetFactoryImpl(schema)

        fun mkEngineObjectData(
            typeName: String,
            vararg selections: Pair<String, Any?>
        ) = ResolvedEngineObjectData(schema.schema.getObjectType(typeName)!!, mapOf(*selections))

        fun mkSelectionSet(
            typeName: String,
            selections: String
        ) = factory.engineSelectionSet(typeName, selections, emptyMap())

        class RecordingEngineObjectData(private val underlying: EngineObjectData) : EngineObjectData {
            val fetched = mutableSetOf<String>()
            override val type get() = underlying.type

            override suspend fun fetch(selection: String): Any? {
                fetched += selection
                return underlying.fetch(selection)
            }

            override suspend fun fetchOrNull(selection: String) = underlying.fetchOrNull(selection)

            override suspend fun fetchSelections() = underlying.fetchSelections()
        }

        fun mkTrackingObjectData(underlying: EngineObjectData) = RecordingEngineObjectData(underlying)

        init {
            fn(this)
        }
    }

    private val fixture = Fixture(
        """
        extend type Query { root: Root }
        type Root { x: Int, y: String, child: Child, items: [Child] }
        type Child { value: Int, grandchild: Grandchild }
        type Grandchild { value: Int }
        """
    )

    @Test
    fun `empty selections`(): Unit =
        runBlocking {
            fixture.apply {
                EngineDataExerciser.exercise(
                    mkEngineObjectData("Root"),
                    mkSelectionSet("Root", "x y")
                )
            }
        }

    @Test
    fun `non-traversable selections are fetched`(): Unit =
        runBlocking {
            fixture.apply {
                val data = mkTrackingObjectData(mkEngineObjectData("Root", "x" to 1, "y" to "hello"))
                EngineDataExerciser.exercise(data, mkSelectionSet("Root", "x y"))
                assertEquals(setOf("x", "y"), data.fetched)
            }
        }

    @Test
    fun `traversable -- null value does not recurse`(): Unit =
        runBlocking {
            fixture.apply {
                EngineDataExerciser.exercise(
                    mkEngineObjectData("Root", "child" to null),
                    mkSelectionSet("Root", "child { value }")
                )
            }
        }

    @Test
    fun `traversable -- EngineObjectData value recurses`(): Unit =
        runBlocking {
            fixture.apply {
                val inner = mkTrackingObjectData(mkEngineObjectData("Child", "value" to 42))
                EngineDataExerciser.exercise(
                    mkEngineObjectData("Root", "child" to inner),
                    mkSelectionSet("Root", "child { value }")
                )
                assertEquals(setOf("value"), inner.fetched)
            }
        }

    @Test
    fun `traversable -- list iterates and recurses`(): Unit =
        runBlocking {
            fixture.apply {
                val item1 = mkTrackingObjectData(mkEngineObjectData("Child", "value" to 1))
                val item2 = mkTrackingObjectData(mkEngineObjectData("Child", "value" to 2))
                EngineDataExerciser.exercise(
                    mkEngineObjectData("Root", "items" to setOf(item1, item2)),
                    mkSelectionSet("Root", "items { value }")
                )
                assertEquals(setOf("value"), item1.fetched)
                assertEquals(setOf("value"), item2.fetched)
            }
        }

    @Test
    fun `traversable -- empty list`(): Unit =
        runBlocking {
            fixture.apply {
                EngineDataExerciser.exercise(
                    mkEngineObjectData("Root", "items" to emptyList<Any>()),
                    mkSelectionSet("Root", "items { value }")
                )
            }
        }

    @Test
    fun `traversable -- list with nulls skips nulls`(): Unit =
        runBlocking {
            fixture.apply {
                val item = mkTrackingObjectData(mkEngineObjectData("Child", "value" to 1))
                EngineDataExerciser.exercise(
                    mkEngineObjectData("Root", "items" to setOf(null, item, null)),
                    mkSelectionSet("Root", "items { value }")
                )
                assertEquals(setOf("value"), item.fetched)
            }
        }

    @Test
    fun `traversable -- unexpected value throws`() {
        assertThrows<IllegalArgumentException> {
            runBlocking {
                fixture.apply {
                    EngineDataExerciser.exercise(
                        mkEngineObjectData("Root", "child" to "a string"),
                        mkSelectionSet("Root", "child { value }")
                    )
                }
            }
        }
    }

    @Test
    fun `traversable -- abstract mergeable selections`() {
        val sdl = """
              interface I { id:ID!, child:I }
              type Foo implements I { id:ID!, child:I, obj:Obj }
              type Obj { value:Int }
        """.trimIndent()
        Fixture(sdl) {
            val obj = mkTrackingObjectData(mkEngineObjectData("Obj", "value" to 3))
            val child = mkTrackingObjectData(mkEngineObjectData("Foo", "id" to 2, "obj" to obj))
            val parent = mkTrackingObjectData(mkEngineObjectData("Foo", "id" to 1, "child" to child))

            runBlocking {
                EngineDataExerciser.exercise(
                    parent,
                    mkSelectionSet(
                        "I",
                        """
                              ... on Foo {
                                child {
                                  ... on Foo {
                                    obj { value }
                                  }
                                }
                              }
                              ... on I {
                                id
                                child {
                                  id
                                }
                              }
                        """.trimIndent()
                    )
                )
            }

            assertEquals(setOf("id", "child"), parent.fetched.toSet())
            assertEquals(setOf("id", "obj"), child.fetched.toSet())
            assertEquals(setOf("value"), obj.fetched.toSet())
        }
    }

    @Test
    fun `mixed traversable and non-traversable`(): Unit =
        runBlocking {
            fixture.apply {
                val inner = mkTrackingObjectData(mkEngineObjectData("Child", "value" to 42))
                val root = mkTrackingObjectData(mkEngineObjectData("Root", "x" to 99, "child" to inner))
                EngineDataExerciser.exercise(root, mkSelectionSet("Root", "x child { value }"))
                assertEquals(setOf("x", "child"), root.fetched)
                assertEquals(setOf("value"), inner.fetched)
            }
        }

    @Test
    fun `multi-level nesting`(): Unit =
        runBlocking {
            fixture.apply {
                val grandchild = mkTrackingObjectData(mkEngineObjectData("Grandchild", "value" to 42))
                val child = mkTrackingObjectData(mkEngineObjectData("Child", "grandchild" to grandchild))
                EngineDataExerciser.exercise(
                    mkEngineObjectData("Root", "child" to child),
                    mkSelectionSet("Root", "child { grandchild { value } }")
                )
                assertEquals(setOf("grandchild"), child.fetched)
                assertEquals(setOf("value"), grandchild.fetched)
            }
        }
}
