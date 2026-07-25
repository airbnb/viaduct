package viaduct.engine.runtime.execution

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.execution.MergedField
import graphql.language.ArrayValue
import graphql.language.Field
import graphql.language.IntValue
import graphql.language.ObjectValue
import graphql.language.OperationDefinition
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldContainExactly as shouldContainExactlyEntries
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import java.time.OffsetDateTime
import java.util.Locale
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.parse.DocumentParser
import viaduct.graphql.Scalars
import viaduct.graphql.utils.collectVariableReferences
import viaduct.graphql.utils.rawValue

class VariableInlinerTest {
    @Test
    fun `inlines DateTime`() {
        assertPreservesScalar(
            Scalars.DateTimeScalar,
            OffsetDateTime.parse("2025-01-02T03:04:05Z"),
        )
    }

    @Test
    fun `inlines Long`() {
        assertPreservesScalar(Scalars.GraphQLLong, -1L)
    }

    @Test
    fun `inlining preserves int argument values`(): Unit =
        runTest {
            val fixtures = listOf(
                "type Query { f(value: Int!): Int }" to
                    "{ f(value: ${"$"}value) }",
                "type Query { f(value: Int): Int }" to
                    "{ f(value: ${"$"}value) }",
                "type Query { f(values: [Int!]!): Int }" to
                    "{ f(values: [${"$"}value]) }",
                """
                    input Options { value: Int! }
                    type Query { f(options: Options!): Int }
                """.trimIndent() to
                    "{ f(options: {value: ${"$"}value}) }",
            ).map { (schemaSDL, query) ->
                InliningFixture(mkSchema(schemaSDL), parseField(query))
            }

            checkAll(Arb.int()) { value ->
                val variables = CoercedVariables.of(mapOf("value" to value))
                fixtures.forEach { fixture ->
                    val expected = fixture.resolve(fixture.field, variables)

                    val inlined = fixture.inline(variables)

                    inlined.collectVariableReferences().shouldBeEmpty()
                    assertEquals(
                        expected,
                        fixture.resolve(inlined, CoercedVariables.emptyVariables()),
                    )
                }
            }
        }

    @Test
    fun `inlines negative required int`() {
        val inliner = mkInliner(
            "type Query { f(value: Int!): Int }",
            mapOf("value" to -1),
        )

        val result = inliner.shallowInline(parseField("{ f(value: ${"$"}value) }"))

        assertInstanceOf(IntValue::class.java, result.arguments.single().value)
    }

    @Test
    fun `inlines negative nullable int`() {
        val inliner = mkInliner(
            "type Query { f(value: Int): Int }",
            mapOf("value" to -1),
        )

        val result = inliner.shallowInline(parseField("{ f(value: ${"$"}value) }"))

        assertInstanceOf(IntValue::class.java, result.arguments.single().value)
    }

    @Test
    fun `inlines negative int in list`() {
        val inliner = mkInliner(
            "type Query { f(values: [Int!]!): Int }",
            mapOf("value" to -1),
        )

        val result = inliner.shallowInline(parseField("{ f(values: [${"$"}value]) }"))
        val values = assertInstanceOf(ArrayValue::class.java, result.arguments.single().value)

        assertInstanceOf(IntValue::class.java, values.values.single())
    }

    @Test
    fun `inlines negative int in input object`() {
        val inliner = mkInliner(
            """
                input Options { value: Int! }
                type Query { f(options: Options!): Int }
            """.trimIndent(),
            mapOf("value" to -1),
        )

        val result = inliner.shallowInline(
            parseField("{ f(options: {value: ${"$"}value}) }")
        )
        val options = assertInstanceOf(ObjectValue::class.java, result.arguments.single().value)

        assertInstanceOf(IntValue::class.java, options.objectFields.single().value)
    }

    @Test
    fun `inlines negative int in directive`() {
        val inliner = mkInliner(
            """
                directive @tag(value: Int!) on FIELD
                type Query { f: Int }
            """.trimIndent(),
            mapOf("value" to -1),
        )

        val result = inliner.shallowInline(parseField("{ f @tag(value: ${"$"}value) }"))

        assertInstanceOf(
            IntValue::class.java,
            result.directives.single().arguments.single().value,
        )
    }

    @Test
    fun `inlines variables nested in input values`() {
        val inliner = mkInliner(
            """
                input Options {
                  values: [Int!]!
                  optional: Int
                }
                type Query {
                  f(options: Options!): Int
                }
            """.trimIndent(),
            mapOf("value" to 3),
        )
        val original = parseField(
            "{ f(options: {values: [${"$"}value, 4], optional: ${"$"}optional}) }"
        )

        val inlined = inliner.shallowInline(original)

        @Suppress("UNCHECKED_CAST")
        val options = inlined.arguments.single().value.rawValue() as Map<String, Any?>
        options.shouldContainExactlyEntries(
            mapOf("values" to listOf(3, 4)),
        )
        inlined.collectVariableReferences().shouldBeEmpty()
    }

    @Test
    fun `applies argument defaults and removes absent arguments`() {
        val inliner = mkInliner(
            "type Query { f(defaulted: Int = 7, optional: Int, literal: Int): Int }",
        )
        val original = parseField(
            "{ f(defaulted: ${"$"}defaulted, optional: ${"$"}optional, literal: 9) }"
        )
        val originalLiteral = original.arguments.single { it.name == "literal" }

        val result = inliner.shallowInline(original)
        val arguments = result.arguments.associate { it.name to it.value.rawValue() }

        arguments.shouldContainExactlyEntries(
            mapOf(
                "defaulted" to 7,
                "literal" to 9,
            ),
        )
        assertSame(originalLiteral, result.arguments.single { it.name == "literal" })
    }

    @Test
    fun `inlines directive argument variables`() {
        val parameters = mkExecutionParameters(
            schemaSDL = "extend type Query { f: Int }",
            coordinate = "Query" to "f",
            query = "query(${"$"}include: Boolean! = true) { f @include(if: ${"$"}include) }",
        )
        val original = checkNotNull(parameters.field).mergedField.singleField

        val inlined = VariableInliner(parameters).shallowInline(original)
        val includeDirective = inlined.directives.single { it.name == "include" }

        assertEquals(true, includeDirective.arguments.single().value.rawValue())
        inlined.collectVariableReferences().shouldBeEmpty()
    }

    @Test
    fun `inlines every field in a merged field`() {
        val inliner = mkInliner(
            "type Query { f(value: Int!): Int }",
            mapOf("value" to 3),
        )
        val fields = parseFields(
            "{ f(value: ${"$"}value) f(value: ${"$"}value) }"
        )
        val original = QueryPlan.CollectedField(
            responseKey = "f",
            selectionSet = null,
            mergedField = MergedField.newMergedField(fields).build(),
            childPlans = emptyList(),
            fieldTypeChildPlans = FieldTypeChildPlans.empty,
        )

        val inlined = inliner.shallowInline(original)

        assertEquals(2, inlined.mergedField.fields.size)
        inlined.mergedField.fields
            .map { it.arguments.single().value.rawValue() }
            .shouldContainExactly(3, 3)
    }

    @Test
    fun `does not inline variables in a field selection set`() {
        val inliner = mkInliner(
            """
                type Query {
                  f(value: Int!): F
                }
                type F {
                  child(value: Int!): Int
                }
            """.trimIndent(),
            variables = mapOf(
                "parent" to 1,
                "child" to 2,
            ),
        )
        val original = parseField(
            """
                {
                  f(value: ${"$"}parent) {
                    child(value: ${"$"}child)
                  }
                }
            """.trimIndent(),
        )

        val inlined = inliner.shallowInline(original)

        assertEquals(1, inlined.arguments.single().value.rawValue())
        checkNotNull(inlined.selectionSet)
            .collectVariableReferences()
            .shouldContainExactlyInAnyOrder("child")
    }

    @Test
    fun `returns original nodes when no variables are present`() {
        val inliner = mkInliner(
            "type Query { f(value: Int!): Int }",
        )
        val field = parseField("{ f(value: 3) }")
        val collectedField = QueryPlan.CollectedField(
            responseKey = "f",
            selectionSet = null,
            mergedField = MergedField.newMergedField(field).build(),
            childPlans = emptyList(),
            fieldTypeChildPlans = FieldTypeChildPlans.empty,
        )

        assertSame(field, inliner.shallowInline(field))
        assertSame(collectedField, inliner.shallowInline(collectedField))
    }

    private fun mkInliner(
        schemaSDL: String,
        variables: Map<String, Any?> = emptyMap(),
    ): VariableInliner {
        val graphQLSchema = mkSchema(schemaSDL)
        return VariableInliner(
            schema = ViaductSchema(graphQLSchema),
            variables = CoercedVariables.of(variables),
            ctx = GraphQLContext.getDefault(),
            locale = Locale.getDefault(),
            fieldArgumentDefinitions = graphQLSchema.queryType.getFieldDefinition("f").arguments,
        )
    }

    private fun mkSchema(
        schemaSDL: String,
        vararg scalars: GraphQLScalarType,
    ): GraphQLSchema =
        SchemaGenerator().makeExecutableSchema(
            SchemaParser().parse(schemaSDL),
            if (scalars.isEmpty()) {
                RuntimeWiring.MOCKED_WIRING
            } else {
                RuntimeWiring.newRuntimeWiring()
                    .apply { scalars.forEach(::scalar) }
                    .build()
            },
        )

    private fun assertPreservesScalar(
        scalar: GraphQLScalarType,
        value: Any,
    ) {
        val fixture = InliningFixture(
            mkSchema(
                """
                    scalar ${scalar.name}
                    type Query { f(value: ${scalar.name}!): Int }
                """.trimIndent(),
                scalar,
            ),
            parseField("{ f(value: ${"$"}value) }"),
        )
        val variables = CoercedVariables.of(mapOf("value" to value))
        val expected = fixture.resolve(fixture.field, variables)

        val inlined = fixture.inline(variables)

        inlined.collectVariableReferences().shouldBeEmpty()
        assertEquals(
            expected,
            fixture.resolve(inlined, CoercedVariables.emptyVariables()),
        )
    }

    private fun parseField(query: String): Field = parseFields(query).single()

    private fun parseFields(query: String): List<Field> =
        DocumentParser.parse(query)
            .getDefinitionsOfType(OperationDefinition::class.java)
            .single()
            .selectionSet
            .selections
            .filterIsInstance<Field>()

    private class InliningFixture(
        schema: GraphQLSchema,
        val field: Field,
    ) {
        private val context = GraphQLContext.getDefault()
        private val locale = Locale.getDefault()
        private val fieldDefinition = schema.queryType.getFieldDefinition("f")
        private val inlinerSchema = ViaductSchema(schema)
        private val codeRegistry = schema.codeRegistry

        fun inline(variables: CoercedVariables): Field =
            VariableInliner(
                schema = inlinerSchema,
                variables = variables,
                ctx = context,
                locale = locale,
                fieldArgumentDefinitions = fieldDefinition.arguments,
            ).shallowInline(field)

        fun resolve(
            field: Field,
            variables: CoercedVariables,
        ): Map<String, Any?> =
            FieldExecutionHelpers.resolveFieldArguments(
                codeRegistry = codeRegistry,
                fieldDefinition = fieldDefinition,
                field = MergedField.newMergedField(field).build(),
                coercedVariables = variables,
                graphQLContext = context,
                locale = locale,
            )
    }
}
