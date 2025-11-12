package viaduct.engine.runtime.tenantloading

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.arbitrary.graphql.asSchema
import viaduct.engine.api.Coordinate
import viaduct.engine.api.FromObjectFieldVariable
import viaduct.engine.api.FromQueryFieldVariable
import viaduct.engine.api.RequiredSelectionSetRegistry
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.mocks.MockRequiredSelectionSetRegistry
import viaduct.engine.api.select.SelectionsParser

class FromFieldVariablesHaveValidPathsTest {
    @Test
    fun `valid -- simple selection`() {
        Fixture("type Query { x:Int, y(a:Int):Int, z:Int}") {
            assertAllValid(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(a:\$var), z",
                    objectVariablePaths = mapOf("var" to "z")
                )
            )
        }
    }

    @Test
    fun `valid -- nested selection`() {
        Fixture("type Query { x:Int, y(a:Int):Int, z:Query, w:Int }") {
            assertAllValid(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(a:\$var), z { w }",
                    objectVariablePaths = mapOf("var" to "z.w")
                )
            )
        }
    }

    @Test
    fun `valid -- path terminates on list of scalar`() {
        Fixture("type Query { x:Int, y(a:[Int]):Int, z:[Int] }") {
            assertAllValid(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(a:\$var), z",
                    objectVariablePaths = mapOf("var" to "z")
                )
            )
        }
    }

    @Test
    fun `invalid -- path traverses through list`() {
        Fixture("type Query { x:Int, y(a:Int):Int, z:[Query], w:Int }") {
            val err = assertOneInvalid<InvalidVariableException>(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(a:\$var), z { w }",
                    objectVariablePaths = mapOf("var" to "z.w")
                )
            )
            assertTrue(err.reason.lowercase().contains("cannot traverse list"))
        }
    }

    @Test
    fun `valid -- path uses aliases`() {
        Fixture("type Query { x:Int, y(a:Int!):Int, z:Int! }") {
            assertAllValid(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(a:\$var), myz:z",
                    objectVariablePaths = mapOf("var" to "myz")
                )
            )
        }
    }

    @Test
    fun `valid -- path uses aliases 1`() {
        Fixture("type Query { x:Int, y(a:Int!):Int, z(w:Int!):Int! }") {
            assertAllValid(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(a:\$var), z1:z(w:1), z2:z(w:2)",
                    objectVariablePaths = mapOf("var" to "z2")
                )
            )
        }
    }

    @Test
    fun `valid -- path uses aliases 2`() {
        Fixture("type Query { x:Int, y(a:Int!):Int, z:Query!, w:Int! }") {
            assertAllValid(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(a:\$var), myz:z { myw: w }",
                    objectVariablePaths = mapOf("var" to "myz.myw")
                )
            )
        }
    }

    @Test
    fun `invalid -- location is input object`() {
        Fixture(
            """
                type Query { x:Int, y(inp:Inp!):Int, z:Int! }
                input Inp { w:Int! }
            """.trimIndent()
        ) {
            val err = assertOneInvalid<InvalidVariableException>(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(inp:\$var), z",
                    objectVariablePaths = mapOf("var" to "z")
                )
            )
            assertTrue(err.reason.lowercase().contains("types not compatible"))
        }
    }

    @Test
    fun `valid -- location is input field`() {
        Fixture(
            """
                type Query { x:Int, y(inp:Inp!):Int, z:Int! }
                input Inp { w:Int! }
            """.trimIndent()
        ) {
            assertAllValid(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(inp:{w:\$var}), z",
                    objectVariablePaths = mapOf("var" to "z")
                )
            )
        }
    }

    @Test
    fun `valid -- path terminates on list of enums`() {
        Fixture("type Query { x:Int, y(a:[E]):Int, z:[E] }, enum E { A }") {
            assertAllValid(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(a:\$var), z",
                    objectVariablePaths = mapOf("var" to "z")
                )
            )
        }
    }

    @Test
    fun `valid -- path terminates on list of lists`() {
        Fixture("type Query { x:Int, y(a:[[Int]]):Int, z:[[Int]] }") {
            assertAllValid(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(a:\$var), z",
                    objectVariablePaths = mapOf("var" to "z")
                )
            )
        }
    }

    @Test
    fun `invalid -- path terminates on object`() {
        Fixture("type Query { x:Int, y(a:Int):Int, z:Obj }, type Obj { x:Int }") {
            val err = assertOneInvalid<InvalidVariableException>(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(a:\$var), z { x }",
                    objectVariablePaths = mapOf("var" to "z")
                )
            )
            assertTrue(err.reason.contains("must terminate"))
        }
    }

    @Test
    fun `invalid -- path terminates on list of object`() {
        Fixture("type Query { x:Int, y(a:[Int]):Int, z:[Obj] }, type Obj { x:Int }") {
            val err = assertOneInvalid<InvalidVariableException>(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(a:\$var), z { x }",
                    objectVariablePaths = mapOf("var" to "z")
                )
            )
            assertTrue(err.reason.contains("must terminate"))
        }
    }

    @Test
    fun `valid -- non-nullable value used nullably`() {
        Fixture("type Query { x:Int, y(a:Int):Int, z:Int! }") {
            assertAllValid(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(a:\$var), z",
                    objectVariablePaths = mapOf("var" to "z")
                )
            )
        }
    }

    @Test
    fun `invalid -- nullable value used non-nullably`() {
        Fixture("type Query { x:Int, y(a:Int!):Int, z:Int }") {
            val err = assertOneInvalid<InvalidVariableException>(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(a:\$var), z",
                    objectVariablePaths = mapOf("var" to "z")
                )
            )
            assertTrue(err.reason.lowercase().contains("types not compatible"))
        }
    }

    @Test
    fun `valid -- nullable value used in non-nullable location with default`() {
        Fixture("type Query { x:Int, y(a:Int! = 0):Int, z:Int }") {
            assertAllValid(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(a:\$var), z",
                    objectVariablePaths = mapOf("var" to "z")
                )
            )
        }
    }

    @Test
    fun `invalid -- effectively nullable value used non-nullably`() {
        Fixture("type Query { x:Int, y(a:Int!):Int, z:Query, w:Int! }") {
            val err = assertOneInvalid<InvalidVariableException>(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(a:\$var), z { w }",
                    // this path traverses through z:Query, making the value effectively nullable
                    objectVariablePaths = mapOf("var" to "z.w")
                )
            )
            assertTrue(err.reason.lowercase().contains("types not compatible"))
        }
    }

    @Test
    fun `valid -- nullable value used in oneof`() {
        Fixture(
            """
                type Query { x:Int, y(inp:Inp!):Int, z:Int }
                input Inp @oneOf { x:Int }
            """.trimIndent()
        ) {
            assertAllValid(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(inp:{x:\$var}), z",
                    objectVariablePaths = mapOf("var" to "z")
                )
            )
        }
    }

    @Test
    fun `valid -- non-nullable value used in oneof`() {
        Fixture(
            """
                type Query { x:Int, y(inp:Inp!):Int, z:Int! }
                input Inp @oneOf { x:Int }
            """.trimIndent()
        ) {
            assertAllValid(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(inp:{x:\$var}), z",
                    objectVariablePaths = mapOf("var" to "z")
                )
            )
        }
    }

    @Test
    fun `valid -- nullable value used in nested oneof`() {
        Fixture(
            """
                type Query { x:Int, y(inp1:Inp1!):Int, z:Int }
                input Inp1 @oneOf { inp2:Inp2 }
                input Inp2 @oneOf { x:Int }
            """.trimIndent()
        ) {
            assertAllValid(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(inp1:{inp2:{x:\$var}}), z",
                    objectVariablePaths = mapOf("var" to "z")
                )
            )
        }
    }

    @Test
    fun `valid -- non-nullable value used in nested oneof`() {
        Fixture(
            """
                type Query { x:Int, y(inp1:Inp1!):Int, z:Int! }
                input Inp1 @oneOf { inp2:Inp2 }
                input Inp2 @oneOf { x:Int }
            """.trimIndent()
        ) {
            assertAllValid(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(inp1:{inp2:{x:\$var}}), z",
                    objectVariablePaths = mapOf("var" to "z")
                )
            )
        }
    }

    @Test
    fun `valid -- nullable value used in deep oneof`() {
        Fixture(
            """
                type Query { x:Int, y(inp1:Inp1!):Int, z:Int }
                input Inp1 { inp2:Inp2! }
                input Inp2 @oneOf { x:Int }
            """.trimIndent()
        ) {
            assertAllValid(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(inp1:{inp2:{x:\$var}}), z",
                    objectVariablePaths = mapOf("var" to "z")
                )
            )
        }
    }

    @Test
    fun `valid -- non-nullable value used in deep oneof`() {
        Fixture(
            """
                type Query { x:Int, y(inp1:Inp1!):Int, z:Int! }
                input Inp1 { inp2:Inp2! }
                input Inp2 @oneOf { x:Int }
            """.trimIndent()
        ) {
            assertAllValid(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(inp1:{inp2:{x:\$var}}), z",
                    objectVariablePaths = mapOf("var" to "z")
                )
            )
        }
    }

    @Test
    fun `invalid -- value with narrowing type condition used non-nullably`() {
        Fixture(
            """
                type Query implements I { x:Int, y(a:Int!):Int, z:I!, w:Int! }
                interface I { w:Int! }
            """.trimIndent()
        ) {
            // this is invalid because the z.w selection becomes nullable due to a narrowing
            // type condition, even though all fields are non-nullable.
            val err = assertOneInvalid<InvalidVariableException>(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(a:\$var), z { ... on Query { w } }",
                    objectVariablePaths = mapOf("var" to "z.w")
                )
            )
            assertTrue(err.reason.lowercase().contains("lossy type condition"))
        }
    }

    @Test
    fun `invalid -- value with narrowing type condition used nullably`() {
        Fixture(
            """
                type Query implements I { x:Int, y(a:Int):Int, z:I!, w:Int! }
                interface I { w:Int! }
            """.trimIndent()
        ) {
            // this is invalid because the z.w selection becomes nullable due to a narrowing
            // type condition, even though all fields are non-nullable.
            val err = assertOneInvalid<InvalidVariableException>(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(a:\$var), z { ... on Query { w } }",
                    objectVariablePaths = mapOf("var" to "z.w")
                )
            )
            assertTrue(err.reason.lowercase().contains("lossy type condition"))
        }
    }

    @Test
    fun `valid -- path value includes self spreads`() {
        Fixture("type Query { x:Int, y(a:Int!):Int, z:Query!, w:Int! }") {
            assertAllValid(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(a:\$var), z { ... on Query { w } }",
                    objectVariablePaths = mapOf("var" to "z.w")
                )
            )
        }
    }

    @Test
    fun `valid -- path value includes widening spreads`() {
        Fixture(
            """
                type Query implements I { x:Int, y(a:Int!):Int, z:Query!, w:Int! }
                interface I { w:Int! }
            """.trimIndent()
        ) {
            assertAllValid(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(a:\$var), z { ... on I { w } }",
                    objectVariablePaths = mapOf("var" to "z.w")
                )
            )
        }
    }

    @Test
    fun `invalid -- path value with abstract-abstract spread throws error`() {
        Fixture(
            """
                type Query { x:Int, y(a:Int!):Int, z:U1!, w:Int! }
                union U1 = Query
                union U2 = Query
            """.trimIndent()
        ) {
            val err = assertOneInvalid<InvalidVariableException>(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(a:\$var), z { ... on U2 { ... on Query { w } } }",
                    objectVariablePaths = mapOf("var" to "z.w")
                )
            )
            assertTrue(err.reason.lowercase().contains("lossy type condition"))
        }
    }

    @Test
    fun `valid -- path value requires merging`() {
        Fixture("type Query { x:Int, y(a:Int!):Int, z:Query!, w:Int! }") {
            assertAllValid(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(a:\$var), z { __typename }, z { w }",
                    objectVariablePaths = mapOf("var" to "z.w")
                )
            )
        }
    }

    @Test
    fun `valid -- path value requires merging 1`() {
        Fixture("type Query { x:Int, y(a:Int!):Int, z:Query!, w:Int! }") {
            assertAllValid(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(a:\$var), z { w w }",
                    objectVariablePaths = mapOf("var" to "z.w")
                )
            )
        }
    }

    @Test
    fun `valid -- interface fields can be used in non-nullable locations`() {
        Fixture(
            """
                type Query implements I { x:Int, y(a:Int!):Int, z:I!, w:Int! }
                interface I { w:Int! }
            """.trimIndent()
        ) {
            assertAllValid(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(a:\$var), z { w }",
                    objectVariablePaths = mapOf("var" to "z.w")
                )
            )
        }
    }

    @Test
    fun `valid -- non-nullable non-list value can be coerced to nullable list`() {
        Fixture("type Query { x:Int, y(a:[Int]):Int, z:Int! }") {
            assertAllValid(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(a:\$var), z",
                    objectVariablePaths = mapOf("var" to "z") // z is Int!, used where [Int] expected
                )
            )
        }
    }

    @Test
    fun `valid -- non-nullable non-list value can be coerced to non-nullable list`() {
        Fixture("type Query { x:Int, y(a:[Int]!):Int, z:Int! }") {
            assertAllValid(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(a:\$var), z",
                    objectVariablePaths = mapOf("var" to "z") // z is Int!, used where [Int]! expected
                )
            )
        }
    }

    @Test
    fun `valid -- non-nullable non-list value can be coerced to list with non-nullable items`() {
        Fixture("type Query { x:Int, y(a:[Int!]!):Int, z:Int! }") {
            assertAllValid(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(a:\$var), z",
                    objectVariablePaths = mapOf("var" to "z") // z is Int!, used where [Int!]! expected
                )
            )
        }
    }

    @Test
    fun `valid -- nullable non-list value can be coerced to nullable list`() {
        Fixture("type Query { x:Int, y(a:[Int]):Int, z:Int }") {
            assertAllValid(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(a:\$var), z",
                    objectVariablePaths = mapOf("var" to "z") // z is Int, used where [Int] expected
                )
            )
        }
    }

    @Test
    fun `valid -- non-nullable list value can be coerced to list-of-list`() {
        Fixture("type Query { x:Int, y(a:[[Int]]):Int, z:[Int]! }") {
            assertAllValid(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(a:\$var), z",
                    objectVariablePaths = mapOf("var" to "z") // z is [Int!], used where [[Int]] expected
                )
            )
        }
    }

    @Test
    fun `valid -- nullable list value can be coerced to list-of-list`() {
        Fixture("type Query { x:Int, y(a:[[Int]]):Int, z:[Int] }") {
            assertAllValid(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(a:\$var), z",
                    objectVariablePaths = mapOf("var" to "z") // z is [Int], used where [[Int]] expected
                )
            )
        }
    }

    @Test
    fun `invalid -- terminal list has incompatible inner type`() {
        Fixture("type Query { x:Int, y(a:[Int]):Int, z:[Boolean] }") {
            val err = assertOneInvalid<InvalidVariableException>(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(a:\$var), z",
                    objectVariablePaths = mapOf("var" to "z")
                )
            )
            assertTrue(err.reason.lowercase().contains("types not compatible"))
        }
    }

    @Test
    fun `valid -- statically included selections can be used non-nullably`() {
        Fixture("type Query { x:Int, y(a:Int!):Int!, z:Int!}") {
            assertAllValid(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(a:\$var), z @skip(if:false)",
                    objectVariablePaths = mapOf("var" to "z")
                )
            )
            assertAllValid(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(a:\$var), z @include(if:true)",
                    objectVariablePaths = mapOf("var" to "z")
                )
            )
        }
    }

    @Test
    fun `invalid -- statically excluded selections cannot be used non-nullably`() {
        Fixture("type Query { x:Int, y(a:Int!):Int!, z:Int!}") {
            assertOneInvalid<InvalidVariableException>(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(a:\$var), z @skip(if:true)",
                    objectVariablePaths = mapOf("var" to "z")
                )
            )
            assertOneInvalid<InvalidVariableException>(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(a:\$var), z @include(if:false)",
                    objectVariablePaths = mapOf("var" to "z")
                )
            )
        }
    }

    @Test
    fun `valid -- path is selected in other rss on same type`() {
        Fixture("type Query { x:Int, y(a:Int!):Int!, z:Query!, w:Int!}") {
            assertAllValid(
                mkReg(
                    "Query" to "x",
                    objectSelections = "y(a:\$var)",
                    // object variable depends on a selection in querySelections:
                    queryVariablePaths = mapOf("var" to "z.w"),
                    querySelections = "z { w }",
                )
            )
        }
    }

    @Test
    fun `valid -- path is selected in other rss on different type`() {
        Fixture(
            """
                type Obj { x:Int, y(a:Int!):Int! }
                type Query { w:Int! }
            """.trimIndent()
        ) {
            assertAllValid(
                mkReg(
                    "Obj" to "x",
                    objectSelections = "y(a:\$var)",
                    // object variable depends on a selection in querySelections:
                    queryVariablePaths = mapOf("var" to "w"),
                    querySelections = "w",
                )
            )
        }
    }

    @Test
    fun `invalid -- path matches multiple different selections`() {
        Fixture("type Query { x:Int, y(a:Int!):Int!, z(c:Int!):Query!, w:Int!}") {
            val reg = mkReg(
                "Query" to "x",
                objectSelections = "y(a:\$var), z(c:0) { w }",
                querySelections = "z(c:1) { w }",
                objectVariablePaths = mapOf("var" to "z.w") // z matches z(c:0) and z(c:1)
            )
            val err = assertInvalid<InvalidVariableException>("Query" to "x", reg)
            assertTrue(err.reason.lowercase().contains("ambiguous source"), err.reason)
        }
    }

    @Test
    fun `sanity check - runs for type checker rss`() {
        @Test
        fun `invalid -- path traverses through list`() {
            Fixture("type Query { x:Int, y(a:Int):Int, z:[Query], w:Int }") {
                val err = assertOneInvalid<InvalidVariableException>(
                    mkReg(
                        "Query" to null,
                        objectSelections = "y(a:\$var), z { w }",
                        objectVariablePaths = mapOf("var" to "z.w")
                    )
                )
                assertTrue(err.reason.lowercase().contains("cannot traverse list"))
            }
        }
    }

    @Test
    fun `invalid -- fragment spread with variable`() {
        Fixture("type Query { x:Int, y(a:Int!):Int, z:Query!, w:String! }") {
            val err = assertOneInvalid<InvalidVariableException>(
                mkReg(
                    "Query" to "x",
                    objectSelections = "fragment Main on Query { y(a:\$var), ...QueryFields } fragment QueryFields on Query { z { w } }",
                    objectVariablePaths = mapOf("var" to "z.w")
                )
            )
            assertTrue(err.reason.lowercase().contains("types not compatible"))
        }
    }

    @Test
    fun `invalid -- fragment spread on non-Query type`() {
        Fixture(
            """
                type Query { x:Int }
                type User { id:String!, updateName(name:Int!):String!, username:String! }
            """.trimIndent()
        ) {
            val err = assertOneInvalid<InvalidVariableException>(
                mkReg(
                    "User" to "id",
                    objectSelections = "fragment Main on User { ...UserFields } fragment UserFields on User { updateName(name:\$name), username }",
                    objectVariablePaths = mapOf("name" to "username")
                )
            )
            assertTrue(err.reason.lowercase().contains("types not compatible"))
        }
    }

    private class Fixture(
        sdl: String,
        fn: Fixture.() -> Unit = {}
    ) {
        val schema = ViaductSchema(sdl.asSchema)
        val validator = FromFieldVariablesHaveValidPaths(schema)

        init {
            fn()
        }

        fun mkReg(
            coordinate: TypeOrFieldCoordinate,
            objectSelections: String? = null,
            objectVariablePaths: Map<String, String> = emptyMap(),
            querySelections: String? = null,
            queryVariablePaths: Map<String, String> = emptyMap()
        ): MockRequiredSelectionSetRegistry {
            val parsedObjectSelections = objectSelections?.let { SelectionsParser.parse(coordinate.first, it) }
            val parsedQuerySelections = querySelections?.let { SelectionsParser.parse(schema.schema.queryType.name, it) }
            val objVars = objectVariablePaths.map { FromObjectFieldVariable(it.key, it.value) }
            val queryVars = queryVariablePaths.map { FromQueryFieldVariable(it.key, it.value) }
            val varResolvers = VariablesResolver.fromSelectionSetVariables(
                parsedObjectSelections,
                parsedQuerySelections,
                objVars + queryVars,
                forChecker = false
            )

            return MockRequiredSelectionSetRegistry.builder()
                .also { b ->
                    if (objectSelections != null) {
                        if (coordinate.second == null) {
                            b.typeCheckerEntry(coordinate.first, objectSelections, varResolvers)
                        } else {
                            @Suppress("UNCHECKED_CAST")
                            b.fieldResolverEntry(
                                coordinate as Coordinate,
                                objectSelections,
                                varResolvers
                            )
                        }
                    }

                    if (querySelections != null) {
                        @Suppress("UNCHECKED_CAST")
                        b.fieldResolverEntryForType(
                            schema.schema.queryType.name,
                            coordinate as Coordinate,
                            querySelections,
                            varResolvers
                        )
                    }
                }.build()
        }

        fun assertAllValid(reg: MockRequiredSelectionSetRegistry) {
            reg.entries.forEach { entry ->
                assertDoesNotThrow {
                    validate(entry.coord, reg)
                }
            }
        }

        inline fun <reified T : Exception> assertOneInvalid(reg: MockRequiredSelectionSetRegistry): T {
            assertTrue(reg.entries.size == 1)
            val coord = reg.entries[0].coord
            return assertInvalid<T>(coord, reg)
        }

        inline fun <reified T : Exception> assertInvalid(
            coord: Coordinate,
            reg: MockRequiredSelectionSetRegistry
        ): T =
            assertThrows<T> {
                validate(coord, reg)
            }

        private val MockRequiredSelectionSetRegistry.RequiredSelectionSetEntry.coord: Coordinate
            get() = (this as MockRequiredSelectionSetRegistry.FieldEntry).coord

        fun validate(
            coord: Coordinate,
            reg: RequiredSelectionSetRegistry
        ) {
            validator.validate(RequiredSelectionsValidationCtx(coord.first, coord.second, reg))
        }
    }
}
