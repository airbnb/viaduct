package viaduct.graphql.schema.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.graphql.schema.ViaductSchema

typealias NSE = NoSuchElementException

/** A set of unit tests for ViaductSchema implementations, with emphasis on
 *  catching error cases (non-error cases are tested pretty well in the "full"
 *  tests).
 */
interface ViaductSchemaContract {
    companion object {
        private fun ViaductSchema.withType(
            type: String,
            block: (ViaductSchema.TypeDef) -> Unit
        ) = block(this.types[type] ?: throw IllegalArgumentException("Unknown type $type"))

        private fun ViaductSchema.withExtensions(
            type: String,
            block: (Iterable<ViaductSchema.Extension<*, *>>) -> Unit
        ) = block(
            (this.types[type] as? ViaductSchema.HasExtensions<*, *>)?.extensions
                ?: throw IllegalArgumentException("Unknown on non-extensions type $type")
        )

        private fun ViaductSchema.withField(
            type: String,
            field: String,
            block: (ViaductSchema.Field) -> Unit
        ) = block((this.types[type]!! as ViaductSchema.Record).field(field)!!)

        private fun ViaductSchema.withArg(
            type: String,
            field: String,
            arg: String,
            block: (ViaductSchema.FieldArg) -> Unit
        ) = block((this.types[type]!! as ViaductSchema.Record).field(field)!!.args.find { it.name == arg }!!)

        private fun ViaductSchema.withEnumValue(
            enum: String,
            value: String,
            block: (ViaductSchema.EnumValue) -> Unit
        ) = block((this.types[enum]!! as ViaductSchema.Enum).value(value)!!)

        private fun assertToStingContains(
            msg: String,
            def: ViaductSchema.Def,
            vararg expected: String
        ) {
            val actual = def.toString()
            for (e in expected) {
                assertTrue(actual.contains(e), "$msg: $actual")
            }
            assertTrue(actual.contains(def.name), "$msg: $actual")
        }
    }

    fun makeSchema(schema: String): ViaductSchema

    @Test
    fun `Effective default funs should throw on fields of output types`() {
        makeSchema(
            """
            type Query {
                foo: String
            }
            interface F {
                foo: String
            }
            """.trimIndent()
        ).apply {
            withField("Query", "foo") {
                assertFalse(it.hasEffectiveDefault, "Query")
                assertThrows<NSE>("Query") { it.effectiveDefaultValue }
            }
            withField("F", "foo") {
                assertFalse(it.hasEffectiveDefault, "F")
                assertThrows<NSE>("F") { it.effectiveDefaultValue }
            }
        }
    }

    @Test
    fun `Effective default funs should throw on has-defaults with no default`() {
        makeSchema(
            """
            type Query {
                foo(bar: String!): String
            }
            input I {
                foo: String!
            }
            """.trimIndent()
        ).apply {
            withArg("Query", "foo", "bar") {
                assertFalse(it.hasEffectiveDefault, "Query")
                assertThrows<NSE>("Query") { it.effectiveDefaultValue }
            }
            withField("I", "foo") {
                assertFalse(it.hasEffectiveDefault, "I")
                assertThrows<NSE>("I") { it.effectiveDefaultValue }
            }
        }
    }

    @Test
    fun `Effective default funs shouldn't throw on has-defaults not from output types`() {
        makeSchema(
            """
            type Query {
                foo(a: String, b: Int! = 1): String
            }
            input I {
                a: String
                b: Int! = 1
            }
            """.trimIndent()
        ).apply {
            withArg("Query", "foo", "a") {
                assertTrue(it.hasEffectiveDefault, "Query.a")
                assertNull(it.effectiveDefaultValue, "Query.a")
            }
            withArg("Query", "foo", "b") {
                assertTrue(it.hasEffectiveDefault, "Query.b")
                assertNotNull(it.effectiveDefaultValue, "Query.b")
            }
            withField("I", "a") {
                assertTrue(it.hasEffectiveDefault, "I.a")
                assertNull(it.effectiveDefaultValue, "I.a")
            }
            withField("I", "b") {
                assertTrue(it.hasEffectiveDefault, "I.b")
                assertNotNull(it.effectiveDefaultValue, "I.b")
            }
        }
    }

    @Test
    fun `Test the fields getter that takes a path`() {
        makeSchema(
            """
            type Query {
                a: A
            }
            interface A {
                b: B
            }
            interface B {
                c: C
            }
            type C {
                d: String
            }
            """.trimIndent()
        ).apply {
            val query = this.types["Query"]!! as ViaductSchema.Object
            assertThrows<IllegalArgumentException>("empty") { query.field(listOf()) }
            assertThrows<IllegalArgumentException>("missing") { query.field(listOf("foo")) }
            assertThrows<IllegalArgumentException>("scalar") { query.field(listOf("a", "b", "c", "d", "e")) }
            withField("A", "b") { assertSame(it, query.field(listOf("a", "b"))) }
            withField("B", "c") { assertSame(it, query.field(listOf("a", "b", "c"))) }
            withField("C", "d") { assertSame(it, query.field(listOf("a", "b", "c", "d"))) }
        }
    }

    @Test
    fun `isOverride is computed correctly`() {
        makeSchema(
            """
            type Query { foo: String }
            interface A { a: String }
            interface B implements A { a: String! b: String }
            interface C implements A&B { a: String! b: String c: Int }
            """.trimIndent()
        ).apply {
            withField("A", "a") {
                assertFalse(it.isOverride, "A.a")
            }
            withField("B", "a") {
                assertTrue(it.isOverride, "B.a")
            }
            withField("B", "b") {
                assertFalse(it.isOverride, "B.b")
            }
            withField("C", "a") {
                assertTrue(it.isOverride, "C.a")
            }
            withField("C", "b") {
                assertTrue(it.isOverride, "C.b")
            }
            withField("C", "c") {
                assertFalse(it.isOverride, "C.c")
            }
        }
    }

    @Test
    fun `Descriptions are descriptive`() {
        makeSchema(
            """
            type Query { foo(a: Int): String }
            enum E { A }
            input I { a: Int }
            interface A { a(b: [String]): Int }
            scalar S
            union U = Query
            """.trimIndent()
        ).apply {
            withType("Query") { assertToStingContains("Query", it, "Object") }
            withField("Query", "foo") { assertToStingContains("Query.foo", it, "Field", "String") }
            withArg("Query", "foo", "a") { assertToStingContains("Query.foo.a", it, "Arg", "Int") }
            withType("E") { assertToStingContains("E", it, "Enum") }
            (this.types["E"]!! as ViaductSchema.Enum).value("A")!!.let { assertToStingContains("E.A", it, "EnumValue") }
            withType("I") { assertToStingContains("I", it, "Input") }
            withField("I", "a") { assertToStingContains("I.a", it, "Field", "Int") }
            withType("A") { assertToStingContains("A", it, "Interface") }
            withField("A", "a") { assertToStingContains("A.a", it, "Field", "Int") }
            withArg("A", "a", "b") { assertToStingContains("I.a.b", it, "Arg", "String") }
            withType("S") { assertToStingContains("S", it, "Scalar") }
            withType("U") { assertToStingContains("U", it, "Union") }
        }
    }

    @Test
    fun `asTypeExpr works`() {
        makeSchema(
            """
            enum E { A }
            input I { a: Int }
            interface A { a(b: [String]): Int }
            union U = Query
            type Query {
              e0: E
              e1: E!
              e2: [E]
              i0(i:I): String
              a0: A
              s0: String
              s1: String!
              s2: [String]
              q0: Query
              u0: U
            }
            """.trimIndent()
        ).apply {
            withField("Query", "e0") {
                assertTrue(it.type.isSimple, "e0")
                assertFalse(it.type.isList, "[e0]")
                assertEquals(0, it.type.listDepth, "[e0]d")
                assertTrue(it.type.isNullable, "e0?")
            }
            withField("Query", "e1") {
                assertTrue(it.type.isSimple, "e1")
                assertFalse(it.type.isList, "[e1]")
                assertEquals(0, it.type.listDepth, "[e1]d")
                assertFalse(it.type.isNullable, "e1?")
            }
            withField("Query", "e2") {
                assertFalse(it.type.isSimple, "e2")
                assertTrue(it.type.isList, "[e2]")
                assertEquals(1, it.type.listDepth, "[e2]d")
                assertTrue(it.type.isNullable, "e2?")
            }
            withArg("Query", "i0", "i") {
                assertFalse(it.type.isSimple, "i0")
                assertFalse(it.type.isList, "[i0]")
                assertEquals(0, it.type.listDepth, "[i0]d")
                assertTrue(it.type.isNullable, "i0?")
            }
            withField("Query", "a0") {
                assertFalse(it.type.isSimple, "a0")
                assertFalse(it.type.isList, "[a0]")
                assertEquals(0, it.type.listDepth, "[a0]d")
                assertTrue(it.type.isNullable, "a0?")
            }
            withField("Query", "s0") {
                assertTrue(it.type.isSimple, "s0")
                assertFalse(it.type.isList, "[s0]")
                assertEquals(0, it.type.listDepth, "[s0]d")
                assertTrue(it.type.isNullable, "s0?")
            }
            withField("Query", "s1") {
                assertTrue(it.type.isSimple, "s1")
                assertFalse(it.type.isList, "[s1]")
                assertEquals(0, it.type.listDepth, "[s1]d")
                assertFalse(it.type.isNullable, "s1?")
            }
            withField("Query", "s2") {
                assertFalse(it.type.isSimple, "s2")
                assertTrue(it.type.isList, "[s2]")
                assertEquals(1, it.type.listDepth, "[s2]d")
                assertTrue(it.type.isNullable, "s2?")
            }
            withField("Query", "q0") {
                assertFalse(it.type.isSimple, "q0")
                assertFalse(it.type.isList, "[q0]")
                assertEquals(0, it.type.listDepth, "[q0]d")
                assertTrue(it.type.isNullable, "q0?")
            }
        }
    }

    @Test
    fun `oneOf directive application`() {
        fun mkSchema(sdl: String) =
            makeSchema(
                """
                schema { query: Query }
                type Query { placeholder: Int }
                $sdl
                """.trimIndent()
            )

        // simple
        mkSchema("input Input @oneOf { a: Int }")
            .also {
                assertTrue(it.types["Input"]!!.hasAppliedDirective("oneOf"))
            }

        // nested
        mkSchema(
            """
            input Outer @oneOf { a: Inner }
            input Inner @oneOf { a: Int }
            """.trimIndent()
        ).also { schema ->
            listOf("Outer", "Inner").forEach {
                schema.withType(it) {
                    assertTrue(it.hasAppliedDirective("oneOf"), it.name)
                }
            }
        }

        // recursive
        mkSchema("input Input @oneOf { a: Input }")
            .also {
                it.withType("Input") {
                    assertTrue(it.hasAppliedDirective("oneOf"), "Input")
                }
            }
    }

    @Test
    fun `test extension lists are properly constructed`() {
        makeSchema(
            """
            directive @d1 on UNION
            type Query { f1: String }
            extend type Query { f2: Int }
            interface A { f1: String }
            extend interface A { f2: Int }
            enum E { V1 }
            extend enum E { V2 }
            input I { f1: String }
            extend input I { f2: Int }
            type T1 { f1: String }
            type T2 { f2: Int }
            union U = T1
            extend union U = T2
            extend union U @d1
            """.trimIndent()
        ).apply {
            withExtensions("Query") {
                assertEquals(2, it.count())
                assertTrue(it.first().isBase)
                assertFalse(it.last().isBase)
            }
            withExtensions("A") {
                assertEquals(2, it.count())
                assertTrue(it.first().isBase)
                assertFalse(it.last().isBase)
            }
            withExtensions("E") {
                assertEquals(2, it.count())
                assertTrue(it.first().isBase)
                assertFalse(it.last().isBase)
            }
            withExtensions("I") {
                assertEquals(2, it.count())
                assertTrue(it.first().isBase)
                assertFalse(it.last().isBase)
            }
            withExtensions("T1") {
                assertEquals(1, it.count())
                assertTrue(it.first().isBase)
            }
            withExtensions("U") {
                assertEquals(3, it.count())
                assertTrue(it.first().isBase)
                assertFalse(it.elementAt(1).isBase)
                assertFalse(it.last().isBase)
            }
        }
    }

    @Test
    fun `test appliedDirectives returns the right list of directive names`() {
        makeSchema(
            """
            directive @d1 on OBJECT | INPUT_OBJECT | ENUM | INTERFACE | UNION
            directive @d2 on OBJECT | INPUT_OBJECT | ENUM | INTERFACE | UNION
            directive @d3 on FIELD_DEFINITION | INPUT_FIELD_DEFINITION | ENUM_VALUE
            directive @d4 on FIELD_DEFINITION | INPUT_FIELD_DEFINITION | ENUM_VALUE
            directive @d5 on ARGUMENT_DEFINITION
            directive @d6 on ARGUMENT_DEFINITION
            directive @d7 on SCALAR
            directive @d8 on SCALAR
            scalar CustomScalar @d7
            extend scalar CustomScalar @d8
            type Query @d1 {
                f1: String @d3
                f3(arg1: String @d5): Int
            }
            extend type Query @d2 {
                f2: Int @d3 @d4
                f4(arg2: Int @d5 @d6): String
            }
            enum Enum @d1 {
                V1 @d3
            }
            extend enum Enum @d2 {
                V2 @d3 @d4
            }
            input Input @d1 {
                f1: Boolean @d3
            }
            extend input Input @d2 {
                f2: Float @d3 @d4
            }
            interface Interface @d1 {
                f1: Enum @d3
                f3(arg3: Boolean @d5): String
            }
            extend interface Interface @d2 {
                f2: String @d3 @d4
            }
            type Object {
                f1: String
            }
            union Union @d1 = Query
            extend union Union @d2 = Object
            """.trimIndent()
        ).apply {
            listOf("Query", "Enum", "Input", "Interface", "Union").forEach {
                withType(it) {
                    assertEquals(listOf("d1", "d2"), it.appliedDirectives.map { it.name })
                }
            }
            withField("Query", "f1") {
                assertEquals(listOf("d3"), it.appliedDirectives.map { it.name })
            }
            withField("Query", "f2") {
                assertEquals(listOf("d3", "d4"), it.appliedDirectives.map { it.name })
            }
            withEnumValue("Enum", "V1") {
                assertEquals(listOf("d3"), it.appliedDirectives.map { it.name })
            }
            withEnumValue("Enum", "V2") {
                assertEquals(listOf("d3", "d4"), it.appliedDirectives.map { it.name })
            }
            withField("Input", "f1") {
                assertEquals(listOf("d3"), it.appliedDirectives.map { it.name })
            }
            withField("Input", "f2") {
                assertEquals(listOf("d3", "d4"), it.appliedDirectives.map { it.name })
            }
            withField("Interface", "f1") {
                assertEquals(listOf("d3"), it.appliedDirectives.map { it.name })
            }
            withField("Interface", "f2") {
                assertEquals(listOf("d3", "d4"), it.appliedDirectives.map { it.name })
            }
            withArg("Query", "f3", "arg1") {
                assertEquals(listOf("d5"), it.appliedDirectives.map { it.name })
            }
            withArg("Query", "f4", "arg2") {
                assertEquals(listOf("d5", "d6"), it.appliedDirectives.map { it.name })
            }
            withArg("Interface", "f3", "arg3") {
                assertEquals(listOf("d5"), it.appliedDirectives.map { it.name })
            }
            withType("CustomScalar") {
                assertEquals(listOf("d7", "d8"), it.appliedDirectives.map { it.name })
            }
        }
    }

    @Test
    fun `test extensionAppliedDirectives`() {
        fun assertions(
            extensionAppliedDirectives: Iterable<ViaductSchema.AppliedDirective>,
            a1Value: String
        ) {
            assertEquals(1, extensionAppliedDirectives.count())
            val dir = extensionAppliedDirectives.first()
            assertEquals("d1", dir.name)
            assertEquals("StringValue{value='$a1Value'}", dir.arguments["a1"].toString())
        }
        makeSchema(
            """
            directive @d1(a1: String) repeatable on OBJECT | INPUT_OBJECT | ENUM | INTERFACE | UNION
            type Query @d1(a1: "obj1") {
                f1: String
            }
            extend type Query @d1(a1: "obj2") {
                f2: Int
            }
            enum Enum @d1(a1: "enum1") {
                V1
            }
            extend enum Enum @d1(a1: "enum2") {
                V2
            }
            input Input @d1(a1: "input1") {
                f1: Int
            }
            extend input Input @d1(a1: "input2") {
                f2: Boolean
            }
            interface Interface @d1(a1: "interf1") {
                f1: Enum
            }
            extend interface Interface @d1(a1: "interf2") {
                f2: String
            }
            """.trimIndent()
        ).apply {
            withEnumValue("Enum", "V1") { assertions(it.containingExtension.appliedDirectives, "enum1") }
            withEnumValue("Enum", "V2") { assertions(it.containingExtension.appliedDirectives, "enum2") }

            withField("Query", "f1") { assertions(it.containingExtension.appliedDirectives, "obj1") }
            withField("Query", "f2") { assertions(it.containingExtension.appliedDirectives, "obj2") }

            withField("Input", "f1") { assertions(it.containingExtension.appliedDirectives, "input1") }
            withField("Input", "f2") { assertions(it.containingExtension.appliedDirectives, "input2") }

            withField("Interface", "f1") { assertions(it.containingExtension.appliedDirectives, "interf1") }
            withField("Interface", "f2") { assertions(it.containingExtension.appliedDirectives, "interf2") }
        }
    }

    @Test
    fun `test root referential integrity`() {
        makeSchema(
            """
            schema {
               query: Foo
               mutation: Bar
               subscription: Baz
            }
            type Foo { blank: String }
            type Bar { blank: String }
            type Baz { blank: String }
            """.trimIndent()
        ).apply {
            assertSame(this.types["Foo"], this.queryTypeDef)
            assertSame(this.types["Bar"], this.mutationTypeDef)
            assertSame(this.types["Baz"], this.subscriptionTypeDef)
        }
    }

    @Test
    fun `test null roots are null`() {
        makeSchema(
            """
            schema {
               query: Query
            }
            type Query { blank: String }
            """.trimIndent()
        ).apply {
            assertNull(this.mutationTypeDef)
            assertNull(this.subscriptionTypeDef)
        }
    }
}
