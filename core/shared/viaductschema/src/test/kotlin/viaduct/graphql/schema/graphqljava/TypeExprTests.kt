package viaduct.graphql.schema.graphqljava

import io.kotest.assertions.throwables.shouldThrowExactly
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.SchemaWithData
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.parseWrappers
import viaduct.graphql.schema.unparseWrappers

class TypeExprTests {
    private val TEST_SCHEMA = """
        type T1 { f1: Int }
        type T2 {
            a0: Int
            a1: String!

            b0: [Int]
            b1: [Int]!
            b2: [Int!]
            b3: [Int!]!

            c0: [[T1]]
            c1: [[T1]]!
            c2: [[T1]!]
            c3: [[T1]!]!
            c4: [[T1!]]
            c5: [[T1!]]!
            c6: [[T1!]!]
            c7: [[T1!]!]!

            d00: [[[T1]]]
            d01: [[[T1]]]!
            d02: [[[T1]]!]
            d03: [[[T1]]!]!
            d04: [[[T1]!]]
            d05: [[[T1]!]]!
            d06: [[[T1]!]!]
            d07: [[[T1]!]!]!
            d08: [[[T1!]]]
            d09: [[[T1!]]]!
            d10: [[[T1!]]!]
            d11: [[[T1!]]!]!
            d12: [[[T1!]!]]
            d13: [[[T1!]!]]!
            d14: [[[T1!]!]!]
            d15: [[[T1!]!]!]!
        }

        type Query { foo: T2 }
    """

    private val schema = gjSchemaFromRegistry(readTypes(TEST_SCHEMA))

    private fun tex(f: String): String = field(f).unparseWrappers()

    private fun type(name: String): ViaductSchema.TypeExpr<*> = schema.types[name]!!.asTypeExpr()

    private fun field(f: String): ViaductSchema.TypeExpr<*> {
        val coords = f.split('.')
        val tname: String = coords[0]
        val fname: String = coords[1]
        val tdef = schema.types[tname] as SchemaWithData.Record
        return tdef.field(fname)!!.type
    }

    /**
     * This function checks that if we apply the function tex to the f
     * we will get the g result and give clue of the f.
     *
     * @param f the value to apply tex into
     * @param g the value the result will come out too
     *
     * This function asserts, there is no return.
     */
    private fun assertThatWrapper(
        f: String,
        g: String
    ) = withClue(f) {
        tex(f) shouldBe g
    }

    /**
     * assert that a TypeExpr has nullableAtDepth values that match the indexed
     * expected values
     */
    private fun ViaductSchema.TypeExpr<*>.assertNullableAtDepth(vararg expected: Boolean) {
        assertThrows(IllegalArgumentException::class.java) { this.nullableAtDepth(-1) }
        expected.forEachIndexed { i, expect ->
            withClue("${this.unparseWrappers()}@$i") {
                nullableAtDepth(i) shouldBe expect
            }
        }
        shouldThrowExactly<IllegalArgumentException> {
            this.nullableAtDepth(expected.size)
        }
    }

    @Test
    fun nullableAtDepth() {
        // no lists
        type("T2").assertNullableAtDepth(true)
        field("T2.a0").assertNullableAtDepth(true)
        field("T2.a1").assertNullableAtDepth(false)

        // single list
        field("T2.b0").assertNullableAtDepth(true, true)
        field("T2.b1").assertNullableAtDepth(false, true)
        field("T2.b2").assertNullableAtDepth(true, false)
        field("T2.b3").assertNullableAtDepth(false, false)

        // double list
        field("T2.c0").assertNullableAtDepth(true, true, true)
        field("T2.c1").assertNullableAtDepth(false, true, true)
        field("T2.c2").assertNullableAtDepth(true, false, true)
        field("T2.c3").assertNullableAtDepth(false, false, true)
        field("T2.c4").assertNullableAtDepth(true, true, false)
        field("T2.c5").assertNullableAtDepth(false, true, false)
        field("T2.c6").assertNullableAtDepth(true, false, false)
        field("T2.c7").assertNullableAtDepth(false, false, false)

        // triple list
        field("T2.d00").assertNullableAtDepth(true, true, true, true)
        field("T2.d01").assertNullableAtDepth(false, true, true, true)
        field("T2.d02").assertNullableAtDepth(true, false, true, true)
        field("T2.d03").assertNullableAtDepth(false, false, true, true)
        field("T2.d04").assertNullableAtDepth(true, true, false, true)
        field("T2.d05").assertNullableAtDepth(false, true, false, true)
        field("T2.d06").assertNullableAtDepth(true, false, false, true)
        field("T2.d07").assertNullableAtDepth(false, false, false, true)
        field("T2.d08").assertNullableAtDepth(true, true, true, false)
        field("T2.d09").assertNullableAtDepth(false, true, true, false)
        field("T2.d10").assertNullableAtDepth(true, false, true, false)
        field("T2.d11").assertNullableAtDepth(false, false, true, false)
        field("T2.d12").assertNullableAtDepth(true, true, false, false)
        field("T2.d13").assertNullableAtDepth(false, true, false, false)
        field("T2.d14").assertNullableAtDepth(true, false, false, false)
        field("T2.d15").assertNullableAtDepth(false, false, false, false)
    }

    @Test
    fun noLists() {
        assertThatWrapper("T1.f1", "?")
        assertThatWrapper("T2.a0", "?")
        assertThatWrapper("T2.a1", "!")
    }

    @Test
    fun listsDepth1() {
        assertThatWrapper("T2.b0", "??")
        assertThatWrapper("T2.b1", "!?")
        assertThatWrapper("T2.b2", "?!")
        assertThatWrapper("T2.b3", "!!")
    }

    @Test
    fun listsDepth2() {
        assertThatWrapper("T2.c0", "???")
        assertThatWrapper("T2.c1", "!??")
        assertThatWrapper("T2.c2", "?!?")
        assertThatWrapper("T2.c3", "!!?")
        assertThatWrapper("T2.c4", "??!")
        assertThatWrapper("T2.c5", "!?!")
        assertThatWrapper("T2.c6", "?!!")
        assertThatWrapper("T2.c7", "!!!")
    }

    @Test
    fun listsDepth3() {
        assertThatWrapper("T2.d00", "????")
        assertThatWrapper("T2.d01", "!???")
        assertThatWrapper("T2.d02", "?!??")
        assertThatWrapper("T2.d03", "!!??")
        assertThatWrapper("T2.d04", "??!?")
        assertThatWrapper("T2.d05", "!?!?")
        assertThatWrapper("T2.d06", "?!!?")
        assertThatWrapper("T2.d07", "!!!?")
        assertThatWrapper("T2.d08", "???!")
        assertThatWrapper("T2.d09", "!??!")
        assertThatWrapper("T2.d10", "?!?!")
        assertThatWrapper("T2.d11", "!!?!")
        assertThatWrapper("T2.d12", "??!!")
        assertThatWrapper("T2.d13", "!?!!")
        assertThatWrapper("T2.d14", "?!!!")
        assertThatWrapper("T2.d15", "!!!!")
    }

    @Test
    fun `parseWrapper error cases`() {
        // Other test cases cover the happy path, so we just test error cases here.
        assertThrows(IllegalArgumentException::class.java) { parseWrappers("") }
        assertThrows(IllegalArgumentException::class.java) { parseWrappers("*") }
        assertThrows(IllegalArgumentException::class.java) { parseWrappers("?*") }
        assertThrows(IllegalArgumentException::class.java) { parseWrappers("*!") }
        assertThrows(IllegalArgumentException::class.java) { parseWrappers("?!*?") }
    }

    @Test
    fun unwrapList() {
        // Non-list types should return null
        type("T2").unwrapList() shouldBe null
        field("T2.a0").unwrapList() shouldBe null
        field("T2.a1").unwrapList() shouldBe null

        // Single list unwrapped to base type
        field("T2.b0").unwrapList()?.unparseWrappers() shouldBe "?"
        field("T2.b1").unwrapList()?.unparseWrappers() shouldBe "?"
        field("T2.b2").unwrapList()?.unparseWrappers() shouldBe "!"
        field("T2.b3").unwrapList()?.unparseWrappers() shouldBe "!"

        // Double list unwrapped to single list
        field("T2.c0").unwrapList()?.unparseWrappers() shouldBe "??"
        field("T2.c1").unwrapList()?.unparseWrappers() shouldBe "??"
        field("T2.c2").unwrapList()?.unparseWrappers() shouldBe "!?"
        field("T2.c3").unwrapList()?.unparseWrappers() shouldBe "!?"
        field("T2.c4").unwrapList()?.unparseWrappers() shouldBe "?!"
        field("T2.c5").unwrapList()?.unparseWrappers() shouldBe "?!"
        field("T2.c6").unwrapList()?.unparseWrappers() shouldBe "!!"
        field("T2.c7").unwrapList()?.unparseWrappers() shouldBe "!!"

        // Triple list unwrapped to double list
        field("T2.d00").unwrapList()?.unparseWrappers() shouldBe "???"
        field("T2.d01").unwrapList()?.unparseWrappers() shouldBe "???"
        field("T2.d02").unwrapList()?.unparseWrappers() shouldBe "!??"
        field("T2.d03").unwrapList()?.unparseWrappers() shouldBe "!??"
        field("T2.d04").unwrapList()?.unparseWrappers() shouldBe "?!?"
        field("T2.d05").unwrapList()?.unparseWrappers() shouldBe "?!?"
        field("T2.d06").unwrapList()?.unparseWrappers() shouldBe "!!?"
        field("T2.d07").unwrapList()?.unparseWrappers() shouldBe "!!?"
        field("T2.d08").unwrapList()?.unparseWrappers() shouldBe "??!"
        field("T2.d09").unwrapList()?.unparseWrappers() shouldBe "??!"
        field("T2.d10").unwrapList()?.unparseWrappers() shouldBe "!?!"
        field("T2.d11").unwrapList()?.unparseWrappers() shouldBe "!?!"
        field("T2.d12").unwrapList()?.unparseWrappers() shouldBe "?!!"
        field("T2.d13").unwrapList()?.unparseWrappers() shouldBe "?!!"
        field("T2.d14").unwrapList()?.unparseWrappers() shouldBe "!!!"
        field("T2.d15").unwrapList()?.unparseWrappers() shouldBe "!!!"
    }
}
