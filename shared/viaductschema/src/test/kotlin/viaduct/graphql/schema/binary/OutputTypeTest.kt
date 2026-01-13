package viaduct.graphql.schema.binary

import org.junit.jupiter.api.Test

/**
 * Test encoding and decoding of composite output types, i.e.,
 * interfaces, objects, and unions.
 */
class OutputTypeTest {
    @Test
    fun `Simple object type def`() {
        val sdl = "type Foo { i: Int }"
        assertRoundTrip(sdl)
    }

    @Test
    fun `Union type with multiple object types`() {
        val sdl = """
            type Cat { meow: String }
            type Dog { bark: String }
            union Pet = Cat | Dog
            type Query { pet: Pet }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Union type with single object type`() {
        val sdl = """
            type User { name: String }
            union SearchResult = User
            type Query { search: SearchResult }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Multiple union types`() {
        val sdl = """
            type Cat { meow: String }
            type Dog { bark: String }
            type Bird { chirp: String }
            union Pet = Cat | Dog
            union Animal = Cat | Dog | Bird
            type Query { pet: Pet, animal: Animal }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Interface implemented by multiple objects`() {
        val sdl = """
            interface Node { id: ID! }
            type User implements Node { id: ID!, name: String }
            type Post implements Node { id: ID!, title: String }
            type Query { node(id: ID!): Node }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Interface with no implementors`() {
        val sdl = """
            interface Node { id: ID! }
            type Query { node: Node }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Interface implementing another interface`() {
        val sdl = """
            interface Node { id: ID! }
            interface NamedNode implements Node { id: ID!, name: String }
            type User implements NamedNode & Node { id: ID!, name: String, email: String }
            type Query { user: User }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Object implementing multiple interfaces`() {
        val sdl = """
            interface Node { id: ID! }
            interface Timestamped { createdAt: String }
            type User implements Node & Timestamped { id: ID!, createdAt: String, name: String }
            type Query { user: User }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Interface with fields that have arguments`() {
        val sdl = """
            interface Node {
                id: ID!
                metadata(key: String!): String
            }
            type User implements Node {
                id: ID!
                metadata(key: String!): String
                name: String
            }
            type Query { user: User }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Complex hierarchy with interfaces unions and objects`() {
        val sdl = """
            interface Node { id: ID! }
            interface Named { name: String }
            type User implements Node & Named { id: ID!, name: String, email: String }
            type Post implements Node { id: ID!, title: String }
            union SearchResult = User | Post
            type Query { search: SearchResult, node: Node }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Field arguments with numeric default values`() {
        val sdl = """
            scalar Long
            scalar Byte
            scalar Short

            type Query {
                field(
                    intArg: Int = 42
                    longArg: Long = 10000
                    byteArg: Byte = 127
                    shortArg: Short = 32000
                ): String
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }
}
