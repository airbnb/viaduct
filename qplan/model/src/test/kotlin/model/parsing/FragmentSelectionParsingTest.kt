package model.parsing

import graphql.parser.Parser
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import model.ArgumentResolutionError
import model.Arguments
import model.InclusionCondition
import model.fieldExpressions
import model.lowering.ViaductAndGJSchema
import model.registry.ProviderFragment
import model.registry.ResolverTarget
import model.registry.fromObjectField
import model.registry.fromQueryField
import model.requireObjectField
import model.requireQueryTypeDef
import model.usedVariables
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FragmentSelectionParsingTest {
    @Test
    fun `fragment documents require explicit owners for unbound variables in named spreads`() {
        val document = Parser.parse("fragment Main on Query { ...Input } fragment Input on Query { echo(value: \$value) }")
        assertThrows<IllegalArgumentException> { schemas.fragmentFromDocument(document) }
        val owned = schemas.fragmentFromDocument(document, variableField = owner)
        assertEquals(setOf(Arguments.Variable.of(owner, "value")), owned.subselections.usedVariables())
        val bound = schemas.fragmentFromDocument(document, bindings = mapOf("value" to "bound"))
        assertEquals(mapOf("value" to "bound"), bound.subselections.single().key.arguments.fieldExpressions())
    }

    @Test
    fun `fragment documents reject missing duplicate and cyclic definitions`() {
        for (source in listOf(
            "fragment Main on Query { ...Missing }",
            "fragment Main on Query { echo } fragment Main on Query { unrelated }",
            "fragment Main on Query { ...Child } fragment Child on Query { ...Main }",
            "query { echo }",
        )) {
            assertThrows<IllegalArgumentException> { schemas.fragmentFromDocument(Parser.parse(source)) }
        }
    }

    private val schemas =
        ViaductAndGJSchema.fromGraphQLSchema(
            UnExecutableSchemaGenerator.makeUnExecutableSchema(
                SchemaParser().parse(
                    """
                    type Query {
                      unrelated: String
                      result: String
                      echo(value: String = "default"): String
                      enabled(value: Boolean): Boolean
                      nested(value: Input): String
                    }
                    input Input { values: [String!]! }
                    """.trimIndent(),
                ),
            ),
        )
    private val owner = schemas.loweredSchema.requireObjectField("Query", "result")

    @Test
    fun `unbound argument and directive variables require explicit owners`() {
        for (selection in listOf(
            "echo(value: \$value)",
            "nested(value: {values: [\$value]})",
            "echo @include(if: \$value)",
            "... @skip(if: \$value) { echo }",
        )) {
            val source = "fragment Input on Query { $selection }"
            val failure = assertThrows<IllegalArgumentException> { schemas.materializeSelectionsFrom(source) }
            assertTrue(failure.message.orEmpty().contains("Unbound fragment variable \$value requires an explicit resolver target"))
            assertThrows<IllegalArgumentException> { schemas.selectionsFrom(source) }
        }
    }

    @Test
    fun `arguments and conditions retain each kind of explicit resolver target`() {
        val targets =
            listOf(
                ResolverTarget.FieldValueResolverTarget(owner),
                ResolverTarget.FieldCheckerTarget(owner),
                ResolverTarget.TypeCheckerTarget(schemas.loweredSchema.requireQueryTypeDef()),
            )
        for (target in targets) {
            val (_, selections) =
                schemas.materializeSelectionsFrom(
                    "fragment Input on Query { enabled(value: \$value) @include(if: \$value) }",
                    variableTarget = target,
                )
            val selection = selections.single()
            val argument = selection.key.arguments.usedVariables().single()
            val condition = selection.inclusionCondition.usedVariables().single()

            assertSame(target, argument.target)
            assertEquals(argument, condition)
            assertEquals("value", argument.variableName)
        }
    }

    @Test
    fun `literal and fully bound fragments need no owner`() {
        val (_, literal) = schemas.materializeSelectionsFrom("fragment Input on Query { echo(value: \"ERROR\") }")
        assertEquals("ERROR", literal.single().key.arguments.fieldExpressions().getValue("value"))

        val (_, bound) =
            schemas.materializeSelectionsFrom(
                "fragment Input on Query { echo(value: \$value) @include(if: \$show) }",
                bindings = mapOf("value" to "ERROR", "show" to true),
            )
        assertEquals("ERROR", bound.single().key.arguments.fieldExpressions().getValue("value"))
        assertEquals(InclusionCondition.Always, bound.single().inclusionCondition)
        assertTrue(bound.single().key.arguments.usedVariables().isEmpty())
    }

    @Test
    fun `missing variables stay distinct from null bindings and argument defaults`() {
        val source = "fragment Input on Query { echo(value: \$value) }"
        assertThrows<IllegalArgumentException> { schemas.materializeSelectionsFrom(source) }
        val (_, bound) = schemas.materializeSelectionsFrom(source, bindings = mapOf("value" to null))
        assertEquals(mapOf("value" to null), bound.single().key.arguments.fieldExpressions())
        val (_, defaulted) = schemas.materializeSelectionsFrom("fragment Input on Query { echo }")
        assertEquals(mapOf("value" to "default"), defaulted.single().key.arguments.fieldExpressions())
        assertThrows<IllegalArgumentException> {
            schemas.materializeSelectionsFrom("fragment Input on Query { echo @include(if: \$show) }", bindings = mapOf("show" to null))
        }
    }

    @Test
    fun `bindings accept input data not symbolic variables or argument errors`() {
        for (invalid in listOf(ArgumentResolutionError, Arguments.Variable.of(owner, "other"))) {
            assertThrows<ClassCastException> {
                schemas.materializeSelectionsFrom("fragment Input on Query { echo(value: \$value) }", bindings = mapOf("value" to invalid))
            }
            assertThrows<ClassCastException> {
                schemas.materializeSelectionsFrom(
                    "fragment Input on Query { nested(value: \$value) }",
                    bindings = mapOf("value" to mapOf("values" to listOf(invalid))),
                )
            }
        }
    }

    @Test
    fun `parsing a literal fragment does not establish an implicit owner for later fragments`() {
        val parser = GJSelectionParser(schemas.graphQLSchema, schemas.loweredSchema, emptyMap())
        parser.materializeSelectionsFrom("fragment Literal on Query { echo }")
        assertThrows<IllegalArgumentException> {
            parser.materializeSelectionsFrom("fragment Symbolic on Query { echo(value: \$value) }")
        }
    }

    @Test
    fun `provider paths require owners only for unbound variables`() {
        val source = "fragment Input on Query { alias: echo(value: \$value) }"
        assertThrows<IllegalArgumentException> { schemas.fromObjectField(source, listOf("alias")) }
        assertThrows<IllegalArgumentException> { schemas.fromQueryField(source, listOf("alias")) }
        val objectProvider = schemas.fromObjectField(source, listOf("alias"), variableField = owner)
        val queryProvider = schemas.fromQueryField(source, listOf("alias"), variableField = owner)
        for ((provider, expectedFragment) in listOf(objectProvider to ProviderFragment.OBJECT, queryProvider to ProviderFragment.QUERY)) {
            assertEquals(expectedFragment, provider.providerFragment)
            assertEquals(listOf("alias"), provider.responsePath)
            assertEquals(setOf(Arguments.Variable.of(owner, "value")), provider.keyPath.single().arguments.usedVariables())
        }
        val bound = schemas.fromObjectField(source, listOf("alias"), bindings = mapOf("value" to "bound"))
        assertEquals(mapOf("value" to "bound"), bound.keyPath.single().arguments.fieldExpressions())
    }
}
