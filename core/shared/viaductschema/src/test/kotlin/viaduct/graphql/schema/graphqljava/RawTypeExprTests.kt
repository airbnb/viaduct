package viaduct.graphql.schema.graphqljava

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.SchemaWithData
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.unparseWrappers

class RawTypeExprTests {
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

    private val schema = gjSchemaRawFromRegistry(readTypes(TEST_SCHEMA))

    private fun tex(f: String): String {
        val coords = f.split('.')
        val tname: String = coords[0]
        val fname: String = coords[1]
        val tdef = schema.types[tname] as SchemaWithData.Record
        val ftype = tdef.field(fname)!!.type
        return ftype.unparseWrappers()
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

    private fun fieldType(f: String): ViaductSchema.TypeExpr<*> {
        val coords = f.split('.')
        val tname: String = coords[0]
        val fname: String = coords[1]
        val tdef = schema.types[tname] as SchemaWithData.Record
        return tdef.field(fname)!!.type
    }

    @Test
    fun unwrapList() {
        // Non-list types should return null
        fieldType("T1.f1").unwrapList() shouldBe null
        fieldType("T2.a0").unwrapList() shouldBe null
        fieldType("T2.a1").unwrapList() shouldBe null

        // Single list unwrapped to base type
        fieldType("T2.b0").unwrapList()?.unparseWrappers() shouldBe "?"
        fieldType("T2.b1").unwrapList()?.unparseWrappers() shouldBe "?"
        fieldType("T2.b2").unwrapList()?.unparseWrappers() shouldBe "!"
        fieldType("T2.b3").unwrapList()?.unparseWrappers() shouldBe "!"

        // Double list unwrapped to single list
        fieldType("T2.c0").unwrapList()?.unparseWrappers() shouldBe "??"
        fieldType("T2.c1").unwrapList()?.unparseWrappers() shouldBe "??"
        fieldType("T2.c2").unwrapList()?.unparseWrappers() shouldBe "!?"
        fieldType("T2.c3").unwrapList()?.unparseWrappers() shouldBe "!?"
        fieldType("T2.c4").unwrapList()?.unparseWrappers() shouldBe "?!"
        fieldType("T2.c5").unwrapList()?.unparseWrappers() shouldBe "?!"
        fieldType("T2.c6").unwrapList()?.unparseWrappers() shouldBe "!!"
        fieldType("T2.c7").unwrapList()?.unparseWrappers() shouldBe "!!"

        // Triple list unwrapped to double list
        fieldType("T2.d00").unwrapList()?.unparseWrappers() shouldBe "???"
        fieldType("T2.d01").unwrapList()?.unparseWrappers() shouldBe "???"
        fieldType("T2.d02").unwrapList()?.unparseWrappers() shouldBe "!??"
        fieldType("T2.d03").unwrapList()?.unparseWrappers() shouldBe "!??"
        fieldType("T2.d04").unwrapList()?.unparseWrappers() shouldBe "?!?"
        fieldType("T2.d05").unwrapList()?.unparseWrappers() shouldBe "?!?"
        fieldType("T2.d06").unwrapList()?.unparseWrappers() shouldBe "!!?"
        fieldType("T2.d07").unwrapList()?.unparseWrappers() shouldBe "!!?"
        fieldType("T2.d08").unwrapList()?.unparseWrappers() shouldBe "??!"
        fieldType("T2.d09").unwrapList()?.unparseWrappers() shouldBe "??!"
        fieldType("T2.d10").unwrapList()?.unparseWrappers() shouldBe "!?!"
        fieldType("T2.d11").unwrapList()?.unparseWrappers() shouldBe "!?!"
        fieldType("T2.d12").unwrapList()?.unparseWrappers() shouldBe "?!!"
        fieldType("T2.d13").unwrapList()?.unparseWrappers() shouldBe "?!!"
        fieldType("T2.d14").unwrapList()?.unparseWrappers() shouldBe "!!!"
        fieldType("T2.d15").unwrapList()?.unparseWrappers() shouldBe "!!!"
    }
}
