package viaduct.engine.runtime.execution

import graphql.schema.GraphQLObjectType
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import viaduct.arbitrary.graphql.asViaductSchema
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.NodeEngineObjectDataImpl
import viaduct.engine.runtime.mat.KeyTree
import viaduct.engine.runtime.mat.KeyTreeBuilder
import viaduct.engine.runtime.mat.KeyTreeFilter
import viaduct.engine.runtime.mat.build

class EngineObjectDataToKeyTreeTest {
    @Nested
    inner class NullData {
        @Test
        fun `null keeps selections`() {
            Fixture("type Foo { y:Int }") {
                val selections = tree {
                    field("Foo", key("y"))
                }

                assertTree(selections, null, selections)
            }
        }

        @Test
        fun `null filters children`() {
            Fixture("type Foo { bar:Bar } type Bar { x:Int }") {
                val selections = tree {
                    field("Foo", key("bar")) {
                        field("Bar", key("x"))
                    }
                }
                val expected = tree {
                    field("Foo", key("bar"))
                }

                assertTree(
                    expected,
                    null,
                    selections,
                    { _, _, topLevel -> topLevel },
                )
            }
        }
    }

    @Nested
    inner class Fields {
        @Test
        fun `empty object is empty`() {
            Fixture("type Foo { y:Int }") {
                val selections = tree {
                    field("Foo", key("y"))
                }

                assertTree(KeyTree.empty, data(foo), selections)
            }
        }

        @Test
        fun `returned field is added`() {
            Fixture("type Foo { y:Int }") {
                val expected = tree {
                    field("Foo", key("y"))
                }

                assertTree(expected, data(foo, "y" to 1))
            }
        }

        @Test
        fun `scalar is not read`() {
            Fixture("type Foo { y:Int }") {
                val selections = tree {
                    field("Foo", key("y"))
                }

                assertTree(
                    selections,
                    dataWithoutReadableValues(foo, "y"),
                    selections,
                )
            }
        }

        @Test
        fun `unknown field is ignored`() {
            Fixture("type Foo { y:Int }") {
                assertTree(KeyTree.empty, data(foo, "z" to 1))
            }
        }

        @Test
        fun `response alias is ignored`() {
            Fixture("type Foo { y:Int }") {
                val selections = tree {
                    field("Foo", key("y", alias = "a"))
                }

                assertTree(KeyTree.empty, data(foo, "a" to 1), selections)
            }
        }

        @Test
        fun `alias and arguments are kept`() {
            Fixture("type Foo { x(n:Int):Int }") {
                val selections = tree {
                    field("Foo", key("x", alias = "a", arguments = mapOf("n" to 1)))
                }

                assertTree(selections, data(foo, "x" to 1), selections)
            }
        }

        @Test
        fun `matching arguments keep aliases`() {
            Fixture("type Foo { x(n:Int):Int }") {
                val selections = tree {
                    field("Foo", key("x", alias = "a", arguments = mapOf("n" to 1)))
                    field("Foo", key("x", alias = "b", arguments = mapOf("n" to 1)))
                }

                assertTree(selections, data(foo, "x" to 1), selections)
            }
        }

        @Test
        fun `different arguments drop field`() {
            Fixture("type Foo { x(n:Int):Int }") {
                val selections = tree {
                    field("Foo", key("x", alias = "a", arguments = mapOf("n" to 1)))
                    field("Foo", key("x", alias = "b", arguments = mapOf("n" to 2)))
                }

                assertTree(KeyTree.empty, data(foo, "x" to 1), selections)
            }
        }

        @Test
        fun `unselected arguments drop field`() {
            Fixture("type Foo { x(n:Int):Int }") {
                assertTree(KeyTree.empty, data(foo, "x" to 1))
            }
        }

        @Test
        fun `blocked field is dropped`() {
            Fixture("type Foo { y:Int }") {
                assertTree(
                    KeyTree.empty,
                    data(foo, "y" to 1),
                    filter = KeyTreeFilter.DropAll,
                )
            }
        }
    }

    @Nested
    inner class Objects {
        @Test
        fun `returned object is added`() {
            Fixture("type Foo { bar:Bar } type Bar { y:Int }") {
                val expected = tree {
                    field("Foo", key("bar")) {
                        field("Bar", key("y"))
                    }
                }
                val source = data(
                    foo,
                    "bar" to data(bar, "y" to 1),
                )

                assertTree(expected, source)
            }
        }

        @Test
        fun `missing object field is dropped`() {
            Fixture("type Foo { bar:Bar } type Bar { x:Int y:Int }") {
                val selections = tree {
                    field("Foo", key("bar")) {
                        field("Bar", key("x"))
                        field("Bar", key("y"))
                    }
                }
                val expected = tree {
                    field("Foo", key("bar")) {
                        field("Bar", key("x"))
                    }
                }
                val source = data(
                    foo,
                    "bar" to data(bar, "x" to 1),
                )

                assertTree(expected, source, selections)
            }
        }

        @Test
        fun `null object keeps selections`() {
            Fixture("type Foo { bar:Bar } type Bar { x:Int }") {
                val selections = tree {
                    field("Foo", key("bar")) {
                        field("Bar", key("x"))
                    }
                }

                assertTree(selections, data(foo, "bar" to null), selections)
            }
        }

        @Test
        fun `other value keeps parent`() {
            Fixture("type Foo { bar:Bar } type Bar { x:Int }") {
                val selections = tree {
                    field("Foo", key("bar")) {
                        field("Bar", key("x"))
                    }
                }
                val expected = tree {
                    field("Foo", key("bar"))
                }

                assertTree(expected, data(foo, "bar" to 1), selections)
            }
        }

        @Test
        fun `blocked object is not read`() {
            Fixture("type Foo { bar:Bar } type Bar { x:Int }") {
                assertTree(
                    KeyTree.empty,
                    dataWithoutReadableValues(foo, "bar"),
                    filter = KeyTreeFilter.DropAll,
                )
            }
        }

        @Test
        fun `aliases share fields`() {
            Fixture("type Foo { bar:Bar } type Bar { x:Int y:Int }") {
                val selections = tree {
                    field("Foo", key("bar", alias = "a")) {
                        field("Bar", key("x"))
                    }
                    field("Foo", key("bar", alias = "b")) {
                        field("Bar", key("y"))
                    }
                }
                val expected = tree {
                    field("Foo", key("bar", alias = "a")) {
                        field("Bar", key("x"))
                        field("Bar", key("y"))
                    }
                    field("Foo", key("bar", alias = "b")) {
                        field("Bar", key("x"))
                        field("Bar", key("y"))
                    }
                }
                val source = data(
                    foo,
                    "bar" to data(bar, "x" to 1, "y" to 2),
                )

                assertTree(expected, source, selections)
            }
        }

        @Test
        fun `blocked alias is dropped`() {
            Fixture("type Foo { bar:Bar } type Bar { x:Int y:Int }") {
                val selections = tree {
                    field("Foo", key("bar", alias = "a")) {
                        field("Bar", key("x"))
                    }
                    field("Foo", key("bar", alias = "b")) {
                        field("Bar", key("y"))
                    }
                }
                val expected = tree {
                    field("Foo", key("bar", alias = "a")) {
                        field("Bar", key("x"))
                    }
                }
                val source = data(
                    foo,
                    "bar" to data(bar, "x" to 1),
                )

                assertTree(
                    expected,
                    source,
                    selections,
                    { _, key, _ -> key.alias != "b" },
                )
            }
        }

        @Test
        fun `nested field uses nested level`() {
            Fixture("type Foo { bar:Bar } type Bar { x:Int }") {
                val selections = tree {
                    field("Foo", key("bar")) {
                        field("Bar", key("x"))
                    }
                }
                val source = data(
                    foo,
                    "bar" to data(bar, "x" to 1),
                )
                val filter = KeyTreeFilter { _, key, topLevel ->
                    if (topLevel) key.name == "bar" else key.name == "x"
                }

                assertTree(selections, source, selections, filter)
            }
        }

        @Test
        fun `unknown object keeps parent`() {
            Fixture("type Foo { y:Int bar:Foo }") {
                val otherSchema = "type Bar { y:Int }".asViaductSchema
                val otherBar = checkNotNull(otherSchema.schema.getObjectType("Bar"))
                val selections = tree {
                    field("Foo", key("bar")) {
                        field("Foo", key("y"))
                    }
                }
                val expected = tree {
                    field("Foo", key("bar"))
                }
                val source = data(
                    foo,
                    "bar" to data(otherBar, "y" to 1),
                )

                assertTree(expected, source, selections)
            }
        }
    }

    @Nested
    inner class Lists {
        @Test
        fun `empty list keeps selections`() {
            Fixture("type Foo { bars:[Bar] } type Bar { x:Int }") {
                val selections = barListSelections()

                assertTree(selections, data(foo, "bars" to emptyList<Any?>()), selections)
            }
        }

        @Test
        fun `null list values are ignored`() {
            Fixture("type Foo { bars:[Bar] } type Bar { x:Int }") {
                val selections = barListSelections()

                assertTree(selections, data(foo, "bars" to listOf(null)), selections)
            }
        }

        @Test
        fun `other list values are ignored`() {
            Fixture("type Foo { bars:[Bar] } type Bar { x:Int }") {
                val selections = barListSelections()

                assertTree(selections, data(foo, "bars" to listOf(1)), selections)
            }
        }

        @Test
        fun `common fields are kept`() {
            Fixture("type Foo { bars:[Bar] } type Bar { x:Int y:Int }") {
                val selections = tree {
                    field("Foo", key("bars")) {
                        field("Bar", key("x"))
                        field("Bar", key("y"))
                    }
                }
                val expected = tree {
                    field("Foo", key("bars")) {
                        field("Bar", key("x"))
                    }
                }
                val source = data(
                    foo,
                    "bars" to listOf(
                        data(bar, "x" to 1, "y" to 2),
                        data(bar, "x" to 3),
                    ),
                )

                assertTree(expected, source, selections)
            }
        }

        @Test
        fun `different fields keep parent`() {
            Fixture("type Foo { bars:[Bar] } type Bar { x:Int y:Int }") {
                val selections = tree {
                    field("Foo", key("bars")) {
                        field("Bar", key("x"))
                        field("Bar", key("y"))
                    }
                }
                val expected = tree {
                    field("Foo", key("bars"))
                }
                val source = data(
                    foo,
                    "bars" to listOf(
                        data(bar, "x" to 1),
                        data(bar, "y" to 2),
                    ),
                )

                assertTree(expected, source, selections)
            }
        }

        @Test
        fun `common objects keep field`() {
            Fixture("type Foo { y:Int bars:[Bar] } type Bar { foo:Foo }") {
                val expected = tree {
                    field("Foo", key("bars")) {
                        field("Bar", key("foo"))
                    }
                }
                val source = data(
                    foo,
                    "bars" to listOf(
                        data(bar, "foo" to data(foo, "y" to 1)),
                        data(bar, "foo" to data(foo)),
                    ),
                )

                assertTree(expected, source)
            }
        }

        @Test
        fun `types are checked separately`() {
            Fixture("type Foo { y:Int items:[Item] } type Bar { x:Int } union Item = Foo | Bar") {
                val selections = tree {
                    field("Foo", key("items")) {
                        field("Foo", key("y"))
                        field("Bar", key("x"))
                    }
                }
                val source = data(
                    foo,
                    "items" to listOf(
                        data(foo, "y" to 1),
                        data(bar, "x" to 2),
                    ),
                )

                assertTree(selections, source, selections)
            }
        }

        @Test
        fun `missing type keeps selections`() {
            Fixture("type Foo { y:Int items:[Item] } type Bar { x:Int } union Item = Foo | Bar") {
                val selections = tree {
                    field("Foo", key("items")) {
                        field("Foo", key("y"))
                        field("Bar", key("x"))
                    }
                }
                val expected = tree {
                    field("Foo", key("items")) {
                        field("Foo", key("y"))
                    }
                }
                val source = data(
                    foo,
                    "items" to listOf(data(bar)),
                )

                assertTree(expected, source, selections)
            }
        }

        @Test
        fun `new type adds fields`() {
            Fixture("type Foo { items:[Item] } type Bar { y:Int } union Item = Foo | Bar") {
                val expected = tree {
                    field("Foo", key("items")) {
                        field("Bar", key("y"))
                    }
                }
                val source = data(
                    foo,
                    "items" to listOf(data(bar, "y" to 1)),
                )

                assertTree(expected, source)
            }
        }

        @Test
        fun `nested lists are flattened`() {
            Fixture("type Foo { groups:[[Bar]] } type Bar { x:Int y:Int }") {
                val expected = tree {
                    field("Foo", key("groups"))
                }
                val source = data(
                    foo,
                    "groups" to listOf(
                        listOf(data(bar, "x" to 1)),
                        listOf(data(bar, "y" to 2)),
                    ),
                )

                assertTree(expected, source)
            }
        }

        @Test
        fun `unknown list type is ignored`() {
            Fixture("type Foo { y:Int items:[Foo] }") {
                val otherSchema = "type Bar { y:Int }".asViaductSchema
                val otherBar = checkNotNull(otherSchema.schema.getObjectType("Bar"))
                val selections = tree {
                    field("Foo", key("items")) {
                        field("Foo", key("y"))
                    }
                }
                val source = data(
                    foo,
                    "items" to listOf(data(otherBar, "y" to 1)),
                )

                assertTree(selections, source, selections)
            }
        }

        @Test
        fun `list field uses nested level`() {
            Fixture("type Foo { bars:[Bar] } type Bar { x:Int }") {
                val selections = barListSelections()
                val filter = KeyTreeFilter { _, key, topLevel ->
                    if (topLevel) key.name == "bars" else key.name == "x"
                }

                assertTree(
                    selections,
                    data(foo, "bars" to emptyList<Any?>()),
                    selections,
                    filter,
                )
            }
        }

        @Test
        fun `nodes limit common fields`() {
            Fixture("type Foo { bars:[Bar] } type Bar { id:ID! x:Int }") {
                val selections = tree {
                    field("Foo", key("bars")) {
                        field("Bar", key("id"))
                        field("Bar", key("x"))
                    }
                }
                val expected = tree {
                    field("Foo", key("bars")) {
                        field("Bar", key("id"))
                    }
                }
                val source = data(
                    foo,
                    "bars" to listOf(
                        data(bar, "id" to "1", "x" to 1),
                        node(bar),
                    ),
                )

                assertTree(expected, source, selections)
            }
        }
    }

    @Nested
    inner class Nodes {
        @Test
        fun `node adds id`() {
            Fixture("type Foo { id:ID! }") {
                val expected = tree {
                    field("Foo", key("id"))
                }

                assertTree(expected, node(foo))
            }
        }

        @Test
        fun `node keeps only id`() {
            Fixture("type Foo { id:ID! y:Int }") {
                val selections = tree {
                    field("Foo", key("id"))
                    field("Foo", key("y"))
                }
                val expected = tree {
                    field("Foo", key("id"))
                }

                assertTree(expected, node(foo), selections)
            }
        }

        @Test
        fun `node keeps id alias`() {
            Fixture("type Foo { id:ID! }") {
                val selections = tree {
                    field("Foo", key("id", alias = "a"))
                }

                assertTree(selections, node(foo), selections)
            }
        }

        @Test
        fun `root id can be blocked`() {
            Fixture("type Foo { id:ID! }") {
                val selections = tree {
                    field("Foo", key("id"))
                }
                val filter = KeyTreeFilter { _, key, topLevel ->
                    !(topLevel && key.name == "id")
                }

                assertTree(KeyTree.empty, node(foo), selections, filter)
            }
        }

        @Test
        fun `nested id is kept`() {
            Fixture("type Foo { bar:Bar } type Bar { id:ID! y:Int }") {
                val selections = tree {
                    field("Foo", key("bar")) {
                        field("Bar", key("id"))
                        field("Bar", key("y"))
                    }
                }
                val expected = tree {
                    field("Foo", key("bar")) {
                        field("Bar", key("id"))
                    }
                }
                val source = data(
                    foo,
                    "bar" to node(bar),
                )
                val filter = KeyTreeFilter { _, key, topLevel ->
                    !(topLevel && key.name == "id")
                }

                assertTree(expected, source, selections, filter)
            }
        }

        @Test
        fun `node without id is empty`() {
            Fixture("type Foo { y:Int }") {
                assertTree(KeyTree.empty, node(foo))
            }
        }

        @Test
        fun `unknown node is empty`() {
            Fixture("type Foo { id:ID! }") {
                val otherSchema = "type Bar { id:ID! }".asViaductSchema
                val otherBar = checkNotNull(otherSchema.schema.getObjectType("Bar"))

                assertTree(KeyTree.empty, node(otherBar))
            }
        }
    }

    @Nested
    inner class SchemaTypes {
        @Test
        fun `unknown type is empty`() {
            Fixture("type Foo { y:Int }") {
                val otherSchema = "type Bar { y:Int }".asViaductSchema
                val otherBar = checkNotNull(otherSchema.schema.getObjectType("Bar"))

                assertTree(KeyTree.empty, dataWithoutReadableSelections(otherBar))
            }
        }

        @Test
        fun `matching type name works`() {
            Fixture("type Foo { y:Int }") {
                val otherSchema = "type Foo { y:Int }".asViaductSchema
                val otherFoo = checkNotNull(otherSchema.schema.getObjectType("Foo"))
                val selections = KeyTree.build(otherSchema) {
                    field("Foo", key("y"))
                }
                val expected = tree {
                    field("Foo", key("y"))
                }

                assertTree(expected, data(otherFoo, "y" to 1), selections)
            }
        }
    }

    private class Fixture(
        schemaSDL: String,
        test: Fixture.() -> Unit
    ) {
        private val schema = schemaSDL.asViaductSchema
        val foo: GraphQLObjectType
            get() = objectType("Foo")
        val bar: GraphQLObjectType
            get() = objectType("Bar")

        init {
            test.invoke(this)
        }

        fun barListSelections(): KeyTree =
            tree {
                field("Foo", key("bars")) {
                    field("Bar", key("x"))
                }
            }

        fun tree(build: KeyTreeBuilder.() -> Unit): KeyTree = KeyTree.build(schema, build)

        fun data(
            type: GraphQLObjectType,
            vararg values: Pair<String, Any?>,
        ): EngineObjectData = ResolvedEngineObjectData(type, mapOf(*values))

        fun node(type: GraphQLObjectType): EngineObjectData =
            NodeEngineObjectDataImpl(
                "1",
                type,
                DispatcherRegistry.Empty,
            )

        fun dataWithoutReadableValues(
            type: GraphQLObjectType,
            vararg fields: String,
        ): EngineObjectData =
            object : EngineObjectData {
                override val type: GraphQLObjectType = type

                override suspend fun fetch(selection: String): Any? = error("Unexpected read of $selection")

                override suspend fun fetchOrNull(selection: String): Any? = error("Unexpected read of $selection")

                override suspend fun fetchSelections(): Iterable<String> = fields.asList()
            }

        fun dataWithoutReadableSelections(type: GraphQLObjectType): EngineObjectData =
            object : EngineObjectData {
                override val type: GraphQLObjectType = type

                override suspend fun fetch(selection: String): Any? = error("Unexpected read of $selection")

                override suspend fun fetchOrNull(selection: String): Any? = error("Unexpected read of $selection")

                override suspend fun fetchSelections(): Iterable<String> = error("Unexpected read of selections")
            }

        fun assertTree(
            expected: KeyTree,
            data: EngineObjectData?,
            selections: KeyTree = KeyTree.empty,
            filter: KeyTreeFilter = KeyTreeFilter.KeepAll,
        ) = runTest {
            assertEquals(
                expected,
                data.toKeyTree(
                    schema = schema.schema,
                    selections = selections,
                    filter = filter,
                ),
            )
        }

        private fun objectType(name: String): GraphQLObjectType = checkNotNull(schema.schema.getObjectType(name))
    }
}
