package viaduct.engine.runtime.tenantloading

import graphql.ErrorType
import graphql.validation.ValidationErrorType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import viaduct.arbitrary.graphql.asSchema
import viaduct.engine.api.Coordinate
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.mocks.MockRequiredSelectionSetRegistry

class RequiredSelectionsAreSchematicallyValidTest {
    @Test
    fun `valid -- simple`() {
        Fixture("type Query { x:Int, y:Int, q:Query }") {
            // simple field selection
            assertValid("Query" to "x", "y")

            // nested field selection
            assertValid("Query" to "x", "q { y }")
        }
    }

    @Test
    fun `valid -- variables`() {
        // required selections may use viaduct variables which may not appear in the document and are validated separately
        Fixture("type Query { x:Int, y(z:Int):Int }")
            .assertValid(
                "Query" to "x",
                "y(z:\$varz)",
                listOf(VariablesResolver.const(mapOf("varz" to 1)))
            )
    }

    @Test
    fun `invalid -- selection is undefined`() {
        Fixture("type Query {x:Int}")
            .assertInvalid("Query" to "x", "y")
            .assertHasError(ValidationErrorType.FieldUndefined)
    }

    @Test
    fun `invalid -- sub selections are not allowed`() {
        Fixture("type Query { x:Int, y:Int }")
            .assertInvalid("Query" to "x", "y { __typename }")
            .assertHasError(ValidationErrorType.SubselectionNotAllowed)
    }

    @Test
    fun `invalid -- sub selections are missing`() {
        Fixture("type Query { x:Int, q:Query }")
            .assertInvalid("Query" to "x", "q")
            .assertHasError(ValidationErrorType.SubselectionRequired)
    }

    @Test
    fun `invalid -- undefined fragment`() {
        Fixture("type Query { x:Int }")
            .assertInvalid("Query" to "x", "...Fragment")
            .assertHasError(ValidationErrorType.UndefinedFragment)
    }

    @Test
    fun `invalid -- illegal fragment spread`() {
        Fixture(
            """
                type Query { x:Int, q:Query }
                type Foo { x:Int }
            """.trimIndent()
        ) {
            assertInvalid(
                "Query" to "x",
                """
                    fragment F on Foo { x }
                    fragment Main on Query { ...F }
                """.trimIndent()
            ).assertHasError(ValidationErrorType.InvalidFragmentType)
        }
    }

    @Test
    fun `invalid -- fragment cycles`() {
        Fixture("type Query { x:Int, q:Query }")
            .assertInvalid("Query" to "x", "fragment Main on Query { x, ...Main }")
            .assertHasError(ValidationErrorType.FragmentCycle)
    }

    @Test
    fun `invalid -- bad argument types`() {
        Fixture("type Query { x:Int, y(z:Int):Int }")
            .assertInvalid("Query" to "x", "y(z:\"str\")")
            .assertHasError(ValidationErrorType.WrongType)
    }

    @Test
    fun `invalid -- undefined type condition`() {
        Fixture("type Query { x:Int }")
            .assertInvalid("Query" to "x", "... on Missing { x }")
            .assertHasError(ValidationErrorType.UnknownType)
    }

    @Test
    fun `invalid -- illegal inline type condition`() {
        Fixture("type Query { x:Int }")
            .assertInvalid("Query" to "x", "... on String { x }")
            .assertHasError(ValidationErrorType.InlineFragmentTypeConditionInvalid)
    }

    @Test
    fun `invalid -- illegal fragment definition type condition`() {
        Fixture("type Query { x:Int }")
            .assertInvalid(
                "Query" to "x",
                """
                    fragment Main on Query { ...F }
                    fragment F on Missing { x }
                """.trimIndent()
            )
            .assertHasError(ValidationErrorType.UnknownType)
    }

    @Test
    fun `invalid -- unused non-Main fragment`() {
        Fixture("type Query { x:Int }")
            .assertInvalid(
                "Query" to "x",
                """
                    fragment Main on Query { x }
                    fragment Unused on Query { __typename }
                """.trimIndent()
            )
            .assertHasError(ValidationErrorType.UnusedFragment)
    }

    @Test
    fun `valid -- fragments`() {
        Fixture(
            """
                type Obj { y:Int }
                type Query { x:Int, obj:Obj }
            """.trimIndent()
        ) {
            // single Main fragment
            assertValid("Query" to "x", "fragment Main on Query { __typename }")

            // single non-Main fragment
            assertValid("Query" to "x", "fragment _ on Query { __typename }")

            // multiple fragments
            assertValid(
                "Query" to "x",
                """
                    fragment Main on Query { ...F }
                    fragment F on Query { __typename }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `invalid -- incorrect oneof`() {
        Fixture(
            """
                input Inp @oneOf { a:Int, b:Int }
                type Query { x(inp:Inp):Int }
            """.trimIndent()
        ) {
            assertInvalid("Query" to "x", "x(inp:{a:1, b:2})")
                .assertHasError(ValidationErrorType.WrongType)
        }
    }

    @Test
    fun `invalid -- null value used non-nullably`() {
        Fixture("type Query { x:Int, y(z:Int!):Int }") {
            assertInvalid("Query" to "x", "y(z:null)")
                .assertHasError(ValidationErrorType.NullValueForNonNullArgument)
        }
    }

    @Test
    fun `invalid -- exception message`() {
        Fixture("type Query {x:Int}")
            .assertInvalid("Query" to "x", "y")
            .let { err ->
                // spot check the message string to ensure that it contains useful information
                assertTrue(err.message.contains("Query.x"), err.message)
                assertTrue(err.message.contains("FieldUndefined"), err.message)
                assertTrue(err.message.contains("Main/y"), err.message)
            }
    }

    @Test
    fun `sanity check - runs for type checker rss`() {
        assertThrows<RequiredSelectionsAreInvalid> {
            val ctx = RequiredSelectionsValidationCtx(
                "Query",
                null,
                MockRequiredSelectionSetRegistry.builder()
                    .typeCheckerEntry("Query", "y")
                    .build()
            )
            RequiredSelectionsAreSchematicallyValid(ViaductSchema("type Query {x:Int}".asSchema)).validate(ctx)
        }
    }

    private class Fixture(sdl: String, fn: Fixture.() -> Unit = {}) {
        val schema = ViaductSchema(sdl.asSchema)
        val validator = RequiredSelectionsAreSchematicallyValid(schema)

        init {
            fn()
        }

        fun assertValid(
            coord: Coordinate,
            selections: String,
            variablesResolvers: List<VariablesResolver> = emptyList()
        ) {
            assertDoesNotThrow {
                validate(coord, selections, variablesResolvers)
            }
        }

        fun assertInvalid(
            coord: Coordinate,
            selections: String,
            variablesResolvers: List<VariablesResolver> = emptyList()
        ): RequiredSelectionsAreInvalid =
            assertThrows<RequiredSelectionsAreInvalid> {
                validate(coord, selections, variablesResolvers)
            }

        fun validate(
            coord: Coordinate,
            selections: String,
            variablesResolvers: List<VariablesResolver> = emptyList()
        ) {
            val reg = MockRequiredSelectionSetRegistry.builder()
                .fieldCheckerEntry(coord, selections, variablesResolvers)
                .build()
            val ctx = RequiredSelectionsValidationCtx(coord.first, coord.second, reg)
            validator.validate(ctx)
        }
    }

    private fun RequiredSelectionsAreInvalid.assertHasError(validationErrorType: ValidationErrorType) {
        val validationError = errors.firstOrNull { it.errorType == ErrorType.ValidationError }
            ?: fail("No ValidationError generated for exception\n$this")

        assertEquals(validationErrorType, validationError.validationErrorType)
    }
}
