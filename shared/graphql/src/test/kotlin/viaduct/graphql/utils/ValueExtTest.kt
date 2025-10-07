package viaduct.graphql.utils

import graphql.language.ArrayValue
import graphql.language.BooleanValue
import graphql.language.Comment
import graphql.language.EnumValue
import graphql.language.FloatValue
import graphql.language.IgnoredChars
import graphql.language.IntValue
import graphql.language.Node
import graphql.language.NodeChildrenContainer
import graphql.language.NodeVisitor
import graphql.language.NullValue
import graphql.language.ObjectField
import graphql.language.ObjectValue
import graphql.language.SourceLocation
import graphql.language.StringValue
import graphql.language.TypeName
import graphql.language.Value
import graphql.language.VariableDefinition
import graphql.language.VariableReference
import graphql.parser.Parser
import graphql.schema.GraphQLTypeUtil
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.graphql.scopes.visitors.toSchema

class ValueExtTest {
    @Test
    fun `rawValue -- StringValue`() {
        assertEquals("string_value", StringValue.newStringValue("string_value").build().rawValue())
    }

    @Test
    fun `rawValue -- IntValue`() {
        assertEquals(1234, IntValue.newIntValue(BigInteger.valueOf(1234)).build().rawValue())
    }

    @Test
    fun `rawValue -- BooleanValue`() {
        assertTrue(BooleanValue.newBooleanValue(true).build().rawValue() as Boolean)
    }

    @Test
    fun `rawValue -- FloatValue`() {
        assertEquals(1.34f, FloatValue.newFloatValue(BigDecimal.valueOf(1.34)).build().rawValue())
    }

    @Test
    fun `rawValue -- NullValue`() {
        assertNull(NullValue.newNullValue().build().rawValue())
    }

    @Test
    fun `rawValue -- ArrayValue`() {
        assertEquals(
            listOf(1.34f, 1234, "string_value"),
            ArrayValue.newArrayValue()
                .values(
                    listOf(
                        FloatValue.newFloatValue(BigDecimal.valueOf(1.34)).build(),
                        IntValue.newIntValue(BigInteger.valueOf(1234)).build(),
                        StringValue.newStringValue("string_value").build()
                    )
                )
                .build().rawValue()
        )
    }

    @Test
    fun `rawValue -- ObjectValue`() {
        val variables = mapOf("var1" to 42)

        val objVal =
            ObjectValue.newObjectValue().objectFields(
                listOf(
                    ObjectField.newObjectField().name("floatVal")
                        .value(FloatValue.newFloatValue(BigDecimal.valueOf(1.34)).build())
                        .build(),
                    ObjectField.newObjectField().name("intVal")
                        .value(IntValue.newIntValue(BigInteger.valueOf(1234)).build())
                        .build(),
                    ObjectField.newObjectField().name("stringVal")
                        .value(StringValue.newStringValue("string_value").build())
                        .build(),
                    ObjectField.newObjectField().name("refVal")
                        .value(VariableReference.newVariableReference().name("var1").build())
                        .build()
                )
            ).build()

        assertEquals(
            mapOf("floatVal" to 1.34f, "intVal" to 1234, "stringVal" to "string_value", "refVal" to 42),
            objVal.rawValue(variables)
        )
    }

    @Test
    fun `rawValue -- EnuMValue`() {
        assertEquals("enum_value", EnumValue.newEnumValue("enum_value").build().rawValue())
    }

    @Test
    fun `rawValue -- VariableReference`() {
        val variables = mapOf("var1" to 42)
        assertEquals(42, VariableReference.newVariableReference().name("var1").build().rawValue(variables))
    }

    @Test
    fun `rawValue -- UnknownValue`() {
        assertThrows(UnrecognizedValueTypeException::class.java) {
            UnknownValue().rawValue()
        }
    }

    @Test
    fun `collectVariableReferences -- non-variable value`() {
        assertEquals(emptySet(), BooleanValue.of(true).collectVariableReferences())
    }

    @Test
    fun `collectVariableReferences -- VariableReference`() {
        assertEquals(setOf("foo"), VariableReference.of("foo").collectVariableReferences())
    }

    @Test
    fun `collectVariableReferences -- VariableDefinition`() {
        assertEquals(emptySet(), VariableDefinition("foo", TypeName("Int")).collectVariableReferences())
    }

    @Test
    fun `collectVariableReferences -- document`() {
        fun String.assertReferences(vararg refs: String) {
            assertEquals(refs.toSet(), Parser.parse(this).collectVariableReferences())
        }

        // empty doc
        "{__typename}".assertReferences()

        // operation with VariableDefinition
        "query Q(${'$'}foo:Int) { __typename }".assertReferences()

        // simple variable
        "{x(foo:\$foo)}".assertReferences("foo")

        // multiple variables
        "{x(foo: \$foo), y(bar: \$bar)}".assertReferences("foo", "bar")

        // variable in directive
        "{x @skip(if:\$foo)}".assertReferences("foo")

        // variable in object
        "{x(arg:{a:{b:{c:\$foo}}})}".assertReferences("foo")

        // variable in list
        "{x(arg:[[[\$foo]]])}".assertReferences("foo")
    }

    @Test
    fun `collectVariableUsages -- basic field argument`() {
        val schema = toSchema("type Query { field(arg: String!): String }")
        val document = Parser.parse("{ field(arg: \$var) }")

        val usages = document.collectVariableUsages(schema, "var", "Query")

        assertEquals(1, usages.size)
        val usage = usages.first()
        assertEquals("field", usage.fieldName)
        assertEquals("arg", usage.argumentName)
        assertEquals("String!", GraphQLTypeUtil.simplePrint(usage.type))
        assertEquals(false, usage.hasDefaultValue)
    }

    @Test
    fun `collectVariableUsages -- field argument with default value`() {
        val schema = toSchema("type Query { field(arg: String = \"default\"): String }")
        val document = Parser.parse("{ field(arg: \$var) }")

        val usages = document.collectVariableUsages(schema, "var", "Query")

        assertEquals(1, usages.size)
        val usage = usages.first()
        assertEquals("field", usage.fieldName)
        assertEquals("arg", usage.argumentName)
        assertEquals("String", GraphQLTypeUtil.simplePrint(usage.type))
        assertEquals(true, usage.hasDefaultValue)
    }

    @Test
    fun `collectVariableUsages -- directive argument`() {
        val schema = toSchema("type Query { field: String }")
        val document = Parser.parse("{ field @skip(if: \$var) }")

        val usages = document.collectVariableUsages(schema, "var", "Query")

        assertEquals(1, usages.size)
        val usage = usages.first()
        assertEquals("directive:skip", usage.fieldName)
        assertEquals("if", usage.argumentName)
        assertEquals("Boolean!", GraphQLTypeUtil.simplePrint(usage.type))
        assertEquals(false, usage.hasDefaultValue)
    }

    @Test
    fun `collectVariableUsages -- input object field`() {
        val schema = toSchema(
            """
            input UserInput { name: String!, age: Int }
            type Query { createUser(input: UserInput!): String }
            """.trimIndent()
        )
        val document = Parser.parse("{ createUser(input: { name: \$userName, age: \$userAge }) }")

        val userNameUsages = document.collectVariableUsages(schema, "userName", "Query")
        val userAgeUsages = document.collectVariableUsages(schema, "userAge", "Query")

        assertEquals(1, userNameUsages.size)
        assertEquals(1, userAgeUsages.size)

        val nameUsage = userNameUsages.first()
        assertEquals("createUser", nameUsage.fieldName)
        assertEquals("input", nameUsage.argumentName)
        assertEquals("String!", GraphQLTypeUtil.simplePrint(nameUsage.type))

        val ageUsage = userAgeUsages.first()
        assertEquals("createUser", ageUsage.fieldName)
        assertEquals("input", ageUsage.argumentName)
        assertEquals("Int", GraphQLTypeUtil.simplePrint(ageUsage.type))
    }

    @Test
    fun `collectVariableUsages -- OneOf input object field`() {
        val schema = toSchema(
            """
            input OneOfInput @oneOf { stringOption: String, intOption: Int }
            type Query { process(input: OneOfInput!): String }
            """.trimIndent()
        )
        val document = Parser.parse("{ process(input: { stringOption: \$var }) }")

        val usages = document.collectVariableUsages(schema, "var", "Query")

        assertEquals(1, usages.size)
        val usage = usages.first()
        assertEquals("process", usage.fieldName)
        assertEquals("input", usage.argumentName)
        assertEquals("String", GraphQLTypeUtil.simplePrint(usage.type))
    }

    @Test
    fun `collectVariableUsages -- nested input object fields`() {
        val schema =
            toSchema(
                """
            input NestedInput { value: String! }
            input ContainerInput { nested: NestedInput! }
            type Query { process(input: ContainerInput!): String }
                """.trimIndent()
            )
        val document = Parser.parse("{ process(input: { nested: { value: \$var } }) }")

        val usages = document.collectVariableUsages(schema, "var", "Query")

        assertEquals(1, usages.size)
        val usage = usages.first()
        assertEquals("process", usage.fieldName)
        assertEquals("input", usage.argumentName)
        assertEquals("String!", GraphQLTypeUtil.simplePrint(usage.type))
    }

    @Test
    fun `collectVariableUsages -- mutation field`() {
        val schema = toSchema(
            """
            type Query { foo: String }
            type Mutation { createUser(name: String!): String }
            """.trimIndent()
        )
        val document = Parser.parse("mutation { createUser(name: \$userName) }")

        val usages = document.collectVariableUsages(schema, "userName", "Mutation")

        assertEquals(1, usages.size)
        val usage = usages.first()
        assertEquals("createUser", usage.fieldName)
        assertEquals("name", usage.argumentName)
        assertEquals("String!", GraphQLTypeUtil.simplePrint(usage.type))
    }

    @Test
    fun `collectVariableUsages -- no variable usages`() {
        val schema = toSchema("type Query { field(arg: String!): String }")
        val document = Parser.parse("{ field(arg: \"literal\") }")

        val usages = document.collectVariableUsages(schema, "var", "Query")

        assertEquals(0, usages.size)
    }

    @Test
    fun `collectVariableUsages -- multiple usages of same variable`() {
        val schema = toSchema("type Query { field1(arg: String!): String, field2(arg: String!): String }")
        val document = Parser.parse("{ field1(arg: \$var), field2(arg: \$var) }")

        val usages = document.collectVariableUsages(schema, "var", "Query")

        assertEquals(2, usages.size)
    }

    companion object {
        class UnknownValue : Value<UnknownValue> {
            override fun getSourceLocation(): SourceLocation {
                TODO("Not yet implemented")
            }

            override fun getChildren(): MutableList<Node<Node<*>>> {
                TODO("Not yet implemented")
            }

            override fun isEqualTo(node: Node<out Node<*>>?): Boolean {
                TODO("Not yet implemented")
            }

            override fun getComments(): MutableList<Comment> {
                TODO("Not yet implemented")
            }

            override fun getAdditionalData(): MutableMap<String, String> {
                TODO("Not yet implemented")
            }

            override fun getIgnoredChars(): IgnoredChars {
                TODO("Not yet implemented")
            }

            override fun getNamedChildren(): NodeChildrenContainer {
                TODO("Not yet implemented")
            }

            override fun deepCopy(): UnknownValue {
                TODO("Not yet implemented")
            }

            override fun accept(
                context: TraverserContext<Node<Node<*>>>?,
                visitor: NodeVisitor?
            ): TraversalControl {
                TODO("Not yet implemented")
            }

            override fun withNewChildren(newChildren: NodeChildrenContainer?): UnknownValue {
                TODO("Not yet implemented")
            }
        }
    }
}
