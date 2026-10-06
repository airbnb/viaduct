package viaduct.tenant.runtime.jvm

import graphql.Scalars
import graphql.language.ArrayValue
import graphql.language.IntValue
import graphql.language.NullValue
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLTypeUtil
import graphql.schema.InputValueWithState
import java.math.BigInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.mocks.MockSchema

class InputTypeFactoryContractTest {
    private val schema = MockSchema.mk(
        """
        directive @both(value: Int) on ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION
        directive @argumentOnly on ARGUMENT_DEFINITION
        input Payload { value: String = "payload" }
        type Under_Score_Type {
            field_with_underscores(
                payload: [Payload!]! = [] @both(value: 7) @argumentOnly
                count: Int = 42
                nullable: String = null
                absent: String
            ): String
            noArgs: String
        }
        extend type Query { test: Under_Score_Type }
        """.trimIndent()
    )

    @Test
    fun `argument coordinates preserve types directives and literal defaults`() {
        val input = InputTypeFactory.argumentsInputType("SyntheticArguments", "Under_Score_Type", "field_with_underscores", schema)
        assertEquals("SyntheticArguments", input.name)
        assertEquals(listOf("payload", "count", "nullable", "absent"), input.fields.map { it.name })
        val payload = input.getField("payload")
        assertEquals("[Payload!]!", GraphQLTypeUtil.simplePrint(payload.type))
        assertEquals(listOf("both"), payload.appliedDirectives.map { it.name })
        assertEquals(BigInteger.valueOf(7), (payload.getAppliedDirective("both").getArgument("value").argumentValue.value as IntValue).value)
        assertTrue(payload.inputFieldDefaultValue.isLiteral)
        assertEquals(emptyList<Any>(), (payload.inputFieldDefaultValue.value as ArrayValue).values)
        assertEquals(BigInteger.valueOf(42), (input.getField("count").inputFieldDefaultValue.value as IntValue).value)
        assertTrue(input.getField("nullable").inputFieldDefaultValue.value is NullValue)
        assertFalse(input.getField("absent").hasSetDefaultValue())
        assertSame(schema.schema.getType("Payload"), InputTypeFactory.inputObjectInputType("Payload", schema))
    }

    @Test
    fun `programmatic defaults retain their external state on synthetic argument fields`() {
        val query = GraphQLObjectType.newObject().name("Query")
            .field(
                GraphQLFieldDefinition.newFieldDefinition().name("field").type(Scalars.GraphQLString)
                    .argument(GraphQLArgument.newArgument().name("arg").type(Scalars.GraphQLString).defaultValueProgrammatic("default"))
            ).build()
        val programmaticSchema = EngineSchema(GraphQLSchema.newSchema().query(query).build())
        val input = InputTypeFactory.argumentsInputType("Arguments", "Query", "field", programmaticSchema)
        assertTrue(input.getField("arg").inputFieldDefaultValue.isExternal)
        assertEquals("default", input.getField("arg").inputFieldDefaultValue.value)
    }

    @Test
    fun `coordinate errors retain their diagnostics`() {
        assertEquals(
            "Type Missing not in schema.",
            assertThrows<IllegalArgumentException> { InputTypeFactory.argumentsInputType("Arguments", "Missing", "field", schema) }.message
        )
        assertTrue(
            assertThrows<IllegalArgumentException> { InputTypeFactory.argumentsInputType("Arguments", "Payload", "value", schema) }
                .message!!.endsWith("is not an object type.")
        )
        assertEquals(
            "Field Under_Score_Type.missing not found.",
            assertThrows<IllegalArgumentException> { InputTypeFactory.argumentsInputType("Arguments", "Under_Score_Type", "missing", schema) }.message
        )
        assertEquals(
            "No arguments found for field noArgs on type.",
            assertThrows<IllegalArgumentException> { InputTypeFactory.argumentsInputType("Arguments", "Under_Score_Type", "noArgs", schema) }.message
        )
        assertEquals(
            "Type Missing does not exist in schema.",
            assertThrows<IllegalArgumentException> { InputTypeFactory.inputObjectInputType("Missing", schema) }.message
        )
        assertTrue(
            assertThrows<IllegalArgumentException> { InputTypeFactory.inputObjectInputType("Under_Score_Type", schema) }
                .message!!.endsWith("is not an input type.")
        )
    }

    @Test
    fun `synthetic argument fields reject internally coerced defaults`() {
        val builder = GraphQLInputObjectField.newInputObjectField()
            .name("value")
            .type(Scalars.GraphQLInt)

        val error = assertThrows<IllegalArgumentException> {
            InputTypeFactory.copyDefaultValue(InputValueWithState.newInternalValue(3), builder, "Query.field.value")
        }

        assertTrue(error.message!!.contains("Query.field.value"))
    }
}
