package viaduct.tenant.codegen.bytecode.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import kotlin.reflect.KClass
import viaduct.api.reflect.Type
import viaduct.api.types.GRT
import viaduct.codegen.utils.JavaName
import viaduct.schema.base.ViaductInputType

enum class EmptyEnum

class EmptyInput : ViaductInputType {
    override fun equals(other: Any?): Boolean {
        return (other != null && other is EmptyInput)
    }

    override fun hashCode(): Int = 1749018273

    fun copy() = this
}

enum class SimpleEnum : GRT, viaduct.api.types.Enum {
    A,
    B,
    C;

    object Reflection : Type<SimpleEnum> {
        override val name: String = "SimpleEnum"
        override val kcls: KClass<out SimpleEnum> = SimpleEnum::class
    }
}

data class SimpleInput(
    @get:JsonProperty("a")
    var a: String? = null,
    @get:JsonProperty("b")
    var b: Int? = null
) : ViaductInputType

data class LargerInput(
    @get:JsonProperty("a")
    var a: String? = null,
    @get:JsonProperty("b")
    var b: Double,
    @get:JsonProperty("c")
    var c: String? = null,
    @get:JsonProperty("d")
    var d: Int? = null,
    @get:JsonProperty("e")
    var e: String? = null,
    @get:JsonProperty("m")
    var m: SimpleEnum,
    @get:JsonProperty("n")
    var n: SimpleInput? = null,
    @get:JsonProperty("o")
    var o: List<SimpleInput?>?,
    @get:JsonProperty("p")
    var p: List<Int>?
) : ViaductInputType

class ExerciserTests {
    companion object {
        // ExerciserTests::class.java.package.name
        val PKG = JavaName("viaduct.tenant.codegen.bytecode.exercise")

        // fun exercise(schema: String) =
        //     InvariantChecker().also {
        //         Exerciser(it, ClassResolver.fromSystemClassLoader(PKG))
        //             .exerciseGeneratedCode(mkSchema(schema))
        //     }

        // fun shardedExercise(
        //     schema: String,
        //     shard: Int,
        //     shardCount: Int
        // ): List<String> {
        //     val checker = InvariantChecker()
        //     val result = mutableListOf<ViaductSchema.TypeDef>()
        //     Exerciser(checker, ClassResolver.fromSystemClassLoader(PKG), shard, shardCount, result)
        //         .exerciseGeneratedCode(mkSchema(schema))
        //     return result.map { it.name }
        // }

        // TODO v2 exercise tests
        // suspend fun exerciseV2(schema: String): InvariantChecker {
        //
        //     val graphQLSchema = mkGraphQLSchema(schema)
        //     val viaductSchema = GJSchema.fromSchema(graphQLSchema)
        //     return InvariantChecker().also {
        //         Exerciser(
        //             it, ClassResolver.fromSystemClassLoader(PKG), schema = viaductSchema,
        //             graphQLSchema
        //         )
        //             .exerciseGeneratedCodeV2()
        //     }
        // }
    }

    // @Test
    // fun testEmptyEnum() {
    //     exercise("enum EmptyEnum").assertEmpty("\n")
    // }
    //
    // @Test
    // fun testEmptyInput() {
    //     exercise("input EmptyInput").assertEmpty("\n")
    // }
    //
    // @Test
    // fun testEnum() {
    //     exercise("enum SimpleEnum { A B C }").assertEmpty("\n")
    // }

    // TODO v2 tests
    // @Test
    // fun testEnumV2() =
    //     runBlockingTest {
    //         exerciseV2("enum SimpleEnum { A B C }").assertEmpty("\n")
    //     }

    // @Test
    // fun testSimpleInput() {
    //     exercise("input SimpleInput { a: String b: Int }").assertEmpty("\n")
    // }
    //
    // @Test
    // fun testBiggerInput() {
    //     exercise(
    //         """
    //         input LargerInput {
    //             a: String
    //             b: Float!
    //             c: ID
    //             d: Int
    //             e: String
    //             m: SimpleEnum!
    //             n: SimpleInput
    //             o: [SimpleInput]
    //             p: [Int!]
    //         }
    //         input SimpleInput { a: String b: Int }
    //         enum SimpleEnum { A B C }
    //         """
    //     ).assertEmpty("\n")
    // }

    // @Test
    // fun shardingPreconditions() {
    //     val schema = "input SimpleInput { a: String b: Int }"
    //     val iae = IllegalArgumentException::class.java
    //     assertThrows(iae) { shardedExercise(schema, -1, 4) }
    //     assertThrows(iae) { shardedExercise(schema, 1, -1) }
    //     assertThrows(iae) { shardedExercise(schema, 4, 3) }
    // }

    // @Test
    // fun shardingWorks() {
    //     val schema =
    //         """
    //         input Type1 { a: String } # Hashes to 3
    //         input Type2 { b: String } # Hashes to 0
    //         input Type3 { c: String } # Hashes to 1
    //         input Type4 { d: String } # Hashes to 2
    //         """
    //
    //     val t = setOf("Type1", "Type2", "Type3", "Type4")
    //     assertEquals(listOf("Type1"), shardedExercise(schema, 3, 4).filter { t.contains(it) })
    //     assertEquals(listOf("Type2"), shardedExercise(schema, 0, 4).filter { t.contains(it) })
    //     assertEquals(listOf("Type3"), shardedExercise(schema, 1, 4).filter { t.contains(it) })
    //     assertEquals(listOf("Type4"), shardedExercise(schema, 2, 4).filter { t.contains(it) })
    // }
}
