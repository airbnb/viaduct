package viaduct.engine.runtime.tenantloading

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.arbitrary.graphql.asSchema
import viaduct.engine.api.Coordinate
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.FromArgumentVariable
import viaduct.engine.api.FromObjectFieldVariable
import viaduct.engine.api.FromQueryFieldVariable
import viaduct.engine.api.SelectionSetVariable
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.mocks.MockRequiredSelectionSetRegistry
import viaduct.engine.api.select.SelectionsParser

class FromFieldVariablesAreNotConditionsTest {
    @Test
    fun `invalid -- object field variable in skip`() {
        Fixture("type Query { x:Int, y:Int, z:Boolean }") {
            val err = assertInvalid(
                "Query" to "x",
                objectSelections = "y @skip(if:\$var), z",
                variables = listOf(FromObjectFieldVariable("var", "z"))
            )
            assertEquals("var", err.variableName)
        }
    }

    @Test
    fun `invalid -- query field variable in include`() {
        Fixture("type Query { x:Int, y:Int, q:Boolean }") {
            val err = assertInvalid(
                "Query" to "x",
                querySelections = "y @include(if:\$var), q",
                variables = listOf(FromQueryFieldVariable("var", "q"))
            )
            assertEquals("var", err.variableName)
        }
    }

    @Test
    fun `invalid -- object field variable used in a query selection condition`() {
        Fixture("type Query { x:Int, y:Int } type Foo { x:Int, z:Boolean }") {
            val err = assertInvalid(
                "Foo" to "x",
                objectSelections = "z",
                querySelections = "y @include(if:\$var)",
                variables = listOf(FromObjectFieldVariable("var", "z"))
            )
            assertEquals("var", err.variableName)
        }
    }

    @Test
    fun `invalid -- condition inside a nested inline fragment`() {
        Fixture("type Query { x:Int, y:Int, z:Boolean, n:Query }") {
            assertInvalid(
                "Query" to "x",
                objectSelections = "z, n { ... on Query @skip(if:\$var) { y } }",
                variables = listOf(FromObjectFieldVariable("var", "z"))
            )
        }
    }

    @Test
    fun `invalid -- condition inside a named fragment`() {
        Fixture("type Query { x:Int, y:Int, z:Boolean }") {
            assertInvalid(
                "Query" to "x",
                objectSelections = "fragment Main on Query { z ...F } fragment F on Query { y @include(if:\$var) }",
                variables = listOf(FromObjectFieldVariable("var", "z"))
            )
        }
    }

    @Test
    fun `valid -- from-field variable used as a field argument`() {
        Fixture("type Query { x:Int, y(a:Int):Int, z:Int }") {
            assertValid(
                "Query" to "x",
                objectSelections = "y(a:\$var), z",
                variables = listOf(FromObjectFieldVariable("var", "z"))
            )
        }
    }

    @Test
    fun `valid -- from-argument variable in a condition`() {
        Fixture("type Query { x(b:Boolean):Int, y:Int }") {
            assertValid(
                "Query" to "x",
                objectSelections = "y @skip(if:\$var)",
                variables = listOf(FromArgumentVariable("var", "b"))
            )
        }
    }

    @Test
    fun `valid -- literal condition on a from-field variable source`() {
        Fixture("type Query { x:Int, y(a:Boolean):Int, z:Boolean }") {
            assertValid(
                "Query" to "x",
                objectSelections = "y(a:\$var) @include(if:true), z @skip(if:false)",
                variables = listOf(FromObjectFieldVariable("var", "z"))
            )
        }
    }

    @Test
    fun `valid -- checker provider variable shares a name with a resolver from-field variable`() {
        Fixture("type Query { x:Int, y:Int, z:Boolean!, sink(a:Boolean!):Int }") {
            val coord = "Query" to "x"
            val reg = MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry(coord, "z, sink(a:\$v)", resolversFor("Query", "z, sink(a:\$v)", listOf(FromObjectFieldVariable("v", "z"))))
                .fieldCheckerEntry(coord, "y @skip(if:\$v)", listOf(VariablesResolver.const(mapOf("v" to true))))
                .build()
            assertDoesNotThrow { validate(coord, reg) }
        }
    }

    @Test
    fun `invalid -- validated from-field variable in a condition`() {
        Fixture("type Query { x:Int, y:Int, z:Boolean }") {
            val coord = "Query" to "x"
            val selections = "y @skip(if:\$var), z"
            val resolvers = resolversFor("Query", selections, listOf(FromObjectFieldVariable("var", "z")))
                .map { it.validated() }
            val reg = MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry(coord, selections, resolvers)
                .build()
            val err = assertThrows<InvalidVariableException> { validate(coord, reg) }
            assertEquals("var", err.variableName)
        }
    }

    @Test
    fun `invalid -- condition on a fragment spread`() {
        Fixture("type Query { x:Int, y:Int, z:Boolean }") {
            assertInvalid(
                "Query" to "x",
                objectSelections = "fragment Main on Query { z ...F @skip(if:\$var) } fragment F on Query { y }",
                variables = listOf(FromObjectFieldVariable("var", "z"))
            )
        }
    }

    @Test
    fun `invalid -- condition on a repeated fragment spread`() {
        Fixture("type Query { x:Int, y:Int, z:Boolean }") {
            assertInvalid(
                "Query" to "x",
                objectSelections = "fragment Main on Query { z ...F ...F @include(if:\$var) } fragment F on Query { y }",
                variables = listOf(FromObjectFieldVariable("var", "z"))
            )
        }
    }

    @Test
    fun `invalid -- field checker with its own from-field variable in a condition`() {
        Fixture("type Query { x:Int, y:Int, z:Boolean }") {
            val coord = "Query" to "x"
            val selections = "y @include(if:\$var), z"
            val reg = MockRequiredSelectionSetRegistry.builder()
                .fieldCheckerEntry(coord, selections, resolversFor("Query", selections, listOf(FromObjectFieldVariable("var", "z")), forChecker = true))
                .build()
            val err = assertThrows<InvalidVariableException> { validate(coord, reg) }
            assertEquals("var", err.variableName)
        }
    }

    @Test
    fun `invalid -- type checker with a from-field variable in a condition`() {
        Fixture("type Query { x:Int } type Foo { y:Int, z:Boolean }") {
            val selections = "y @skip(if:\$var), z"
            val reg = MockRequiredSelectionSetRegistry.builder()
                .typeCheckerEntry("Foo", selections, resolversFor("Foo", selections, listOf(FromObjectFieldVariable("var", "z")), forChecker = true))
                .build()
            val err = assertThrows<InvalidVariableException> { validateType("Foo", reg) }
            assertEquals("var", err.variableName)
        }
    }

    private class Fixture(
        sdl: String,
        fn: Fixture.() -> Unit = {}
    ) {
        val schema = EngineSchema(sdl.asSchema)
        val validator = FromFieldVariablesAreNotConditions()

        init {
            fn()
        }

        fun assertValid(
            coordinate: Coordinate,
            objectSelections: String? = null,
            querySelections: String? = null,
            variables: List<SelectionSetVariable> = emptyList()
        ) {
            val reg = mkReg(coordinate, objectSelections, querySelections, variables)
            assertDoesNotThrow { validate(coordinate, reg) }
        }

        fun assertInvalid(
            coordinate: Coordinate,
            objectSelections: String? = null,
            querySelections: String? = null,
            variables: List<SelectionSetVariable> = emptyList()
        ): InvalidVariableException {
            val reg = mkReg(coordinate, objectSelections, querySelections, variables)
            return assertThrows<InvalidVariableException> { validate(coordinate, reg) }
        }

        private fun mkReg(
            coordinate: Coordinate,
            objectSelections: String?,
            querySelections: String?,
            variables: List<SelectionSetVariable>
        ): MockRequiredSelectionSetRegistry {
            val varResolvers = VariablesResolver.fromSelectionSetVariables(
                objectSelections?.let { SelectionsParser.parse(coordinate.first, it) },
                querySelections?.let { SelectionsParser.parse(schema.schema.queryType.name, it) },
                variables,
                forChecker = false
            )
            return MockRequiredSelectionSetRegistry.builder()
                .also { b ->
                    if (objectSelections != null) {
                        b.fieldResolverEntry(coordinate, objectSelections, varResolvers)
                    }
                    if (querySelections != null) {
                        b.fieldResolverEntryForType(schema.schema.queryType.name, coordinate, querySelections, varResolvers)
                    }
                }.build()
        }

        fun resolversFor(
            typeName: String,
            selections: String,
            variables: List<SelectionSetVariable>,
            forChecker: Boolean = false
        ): List<VariablesResolver> =
            VariablesResolver.fromSelectionSetVariables(
                SelectionsParser.parse(typeName, selections),
                null,
                variables,
                forChecker = forChecker
            )

        fun validate(
            coordinate: Coordinate,
            reg: MockRequiredSelectionSetRegistry
        ) {
            validator.validate(RequiredSelectionsValidationCtx(coordinate.first, coordinate.second, reg))
        }

        fun validateType(
            typeName: String,
            reg: MockRequiredSelectionSetRegistry
        ) {
            validator.validate(RequiredSelectionsValidationCtx(typeName, null, reg))
        }
    }
}
