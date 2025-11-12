package viaduct.graphql.utils

import graphql.Scalars
import graphql.language.ArrayValue
import graphql.language.AstPrinter
import graphql.language.BooleanValue
import graphql.language.Comment
import graphql.language.EnumValue
import graphql.language.FloatValue
import graphql.language.FragmentDefinition
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
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
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
    fun `collectAllVariableUsages -- basic field argument`() {
        val schema = toSchema("type Query { field(arg: String!): String }")
        val document = Parser.parse("{ field(arg: \$var) }")

        val allUsages = document.collectAllVariableUsages(schema, "Query")

        assertEquals(1, allUsages.size)
        assertTrue(allUsages.containsKey("var"))

        val usages = allUsages["var"]!!
        assertEquals(1, usages.size)
        val usage = usages.first()
        assertEquals("field 'field', argument 'arg'", usage.contextString)
        assertEquals("String!", GraphQLTypeUtil.simplePrint(usage.type))
        assertEquals(false, usage.hasDefaultValue)
    }

    @Test
    fun `collectAllVariableUsages -- field argument with default value`() {
        val schema = toSchema("type Query { field(arg: String = \"default\"): String }")
        val document = Parser.parse("{ field(arg: \$var) }")

        val allUsages = document.collectAllVariableUsages(schema, "Query")

        assertEquals(1, allUsages.size)
        val usages = allUsages["var"]!!
        assertEquals(1, usages.size)
        val usage = usages.first()
        assertEquals("field 'field', argument 'arg'", usage.contextString)
        assertEquals("String", GraphQLTypeUtil.simplePrint(usage.type))
        assertEquals(true, usage.hasDefaultValue)
    }

    @Test
    fun `collectAllVariableUsages -- directive argument`() {
        val schema = toSchema("type Query { field: String }")
        val document = Parser.parse("{ field @skip(if: \$var) }")

        val allUsages = document.collectAllVariableUsages(schema, "Query")

        assertEquals(1, allUsages.size)
        val usages = allUsages["var"]!!
        assertEquals(1, usages.size)
        val usage = usages.first()
        assertEquals("directive 'skip', argument 'if'", usage.contextString)
        assertEquals("Boolean!", GraphQLTypeUtil.simplePrint(usage.type))
        assertEquals(false, usage.hasDefaultValue)
    }

    @Test
    fun `collectAllVariableUsages -- input object field`() {
        val schema = toSchema(
            """
            input UserInput { name: String!, age: Int }
            type Query { createUser(input: UserInput!): String }
            """.trimIndent()
        )
        val document = Parser.parse("{ createUser(input: { name: \$userName, age: \$userAge }) }")

        val allUsages = document.collectAllVariableUsages(schema, "Query")

        assertEquals(2, allUsages.size)
        assertTrue(allUsages.containsKey("userName"))
        assertTrue(allUsages.containsKey("userAge"))

        val userNameUsages = allUsages["userName"]!!
        assertEquals(1, userNameUsages.size)
        val nameUsage = userNameUsages.first()
        assertEquals("input field 'UserInput.name'", nameUsage.contextString)
        assertEquals("String!", GraphQLTypeUtil.simplePrint(nameUsage.type))

        val userAgeUsages = allUsages["userAge"]!!
        assertEquals(1, userAgeUsages.size)
        val ageUsage = userAgeUsages.first()
        assertEquals("input field 'UserInput.age'", ageUsage.contextString)
        assertEquals("Int", GraphQLTypeUtil.simplePrint(ageUsage.type))
    }

    @Test
    fun `collectAllVariableUsages -- OneOf input object field`() {
        val schema = toSchema(
            """
            input OneOfInput @oneOf { stringOption: String, intOption: Int }
            type Query { process(input: OneOfInput!): String }
            """.trimIndent()
        )
        val document = Parser.parse("{ process(input: { stringOption: \$var }) }")

        val allUsages = document.collectAllVariableUsages(schema, "Query")

        assertEquals(1, allUsages.size)
        val usages = allUsages["var"]!!
        assertEquals(1, usages.size)
        val usage = usages.first()
        assertEquals("input field 'OneOfInput.stringOption'", usage.contextString)
        assertEquals("String", GraphQLTypeUtil.simplePrint(usage.type))
    }

    @Test
    fun `collectAllVariableUsages -- nested input object fields`() {
        val schema = toSchema(
            """
            input NestedInput { value: String! }
            input ContainerInput { nested: NestedInput! }
            type Query { process(input: ContainerInput!): String }
            """.trimIndent()
        )
        val document = Parser.parse("{ process(input: { nested: { value: \$var } }) }")

        val allUsages = document.collectAllVariableUsages(schema, "Query")

        assertEquals(1, allUsages.size)
        val usages = allUsages["var"]!!
        assertEquals(1, usages.size)
        val usage = usages.first()
        assertEquals("input field 'NestedInput.value'", usage.contextString)
        assertEquals("String!", GraphQLTypeUtil.simplePrint(usage.type))
    }

    @Test
    fun `collectAllVariableUsages -- mutation field`() {
        val schema = toSchema(
            """
            type Query { foo: String }
            type Mutation { createUser(name: String!, email: String): String }
            """.trimIndent()
        )
        val document = Parser.parse("mutation { createUser(name: \$userName, email: \$userEmail) }")

        val allUsages = document.collectAllVariableUsages(schema, "Mutation")

        assertEquals(2, allUsages.size)
        assertTrue(allUsages.containsKey("userName"))
        assertTrue(allUsages.containsKey("userEmail"))

        val userNameUsages = allUsages["userName"]!!
        assertEquals(1, userNameUsages.size)
        val userNameUsage = userNameUsages.first()
        assertEquals("field 'createUser', argument 'name'", userNameUsage.contextString)
        assertEquals("String!", GraphQLTypeUtil.simplePrint(userNameUsage.type))

        val userEmailUsages = allUsages["userEmail"]!!
        assertEquals(1, userEmailUsages.size)
        val userEmailUsage = userEmailUsages.first()
        assertEquals("field 'createUser', argument 'email'", userEmailUsage.contextString)
        assertEquals("String", GraphQLTypeUtil.simplePrint(userEmailUsage.type))
    }

    @Test
    fun `collectAllVariableUsages -- no variable usages`() {
        val schema = toSchema("type Query { field(arg: String!): String }")
        val document = Parser.parse("{ field(arg: \"literal\") }")

        val allUsages = document.collectAllVariableUsages(schema, "Query")

        assertEquals(0, allUsages.size)
    }

    @Test
    fun `collectAllVariableUsages -- multiple usages of same variable`() {
        val schema = toSchema("type Query { field1(arg: String!): String, field2(arg: String!): String }")
        val document = Parser.parse("{ field1(arg: \$var), field2(arg: \$var) }")

        val allUsages = document.collectAllVariableUsages(schema, "Query")

        assertEquals(1, allUsages.size)
        val usages = allUsages["var"]!!
        assertEquals(2, usages.size)
    }

    @Test
    fun `collectAllVariableUsages -- fragment not on root type`() {
        val schema = toSchema("type Query { foo: Foo }, type Foo { field1(arg: String!): String }")
        val document = Parser.parse("fragment _ on Foo { field1(arg: \$var) }")

        val allUsages = document.collectAllVariableUsages(schema, "Foo")

        assertEquals(1, allUsages.size)
        val usages = allUsages["var"]!!
        assertEquals(1, usages.size)
        val usage = usages.first()
        assertEquals("field 'field1', argument 'arg'", usage.contextString)
        assertEquals("String!", GraphQLTypeUtil.simplePrint(usage.type))
    }

    @Test
    fun `collectAllVariableUsages -- node is selection set`() {
        val schema = toSchema("type Query { foo: Foo }, type Foo { field1(arg: String!): String }")
        val document = Parser.parse("fragment _ on Foo { field1(arg: \$var) }")

        val selectionSet = document.getFirstDefinitionOfType(FragmentDefinition::class.java).orElseThrow().selectionSet
        val allUsages = selectionSet.collectAllVariableUsages(schema, "Foo")

        assertEquals(1, allUsages.size)
        val usages = allUsages["var"]!!
        assertEquals(1, usages.size)
        val usage = usages.first()
        assertEquals("field 'field1', argument 'arg'", usage.contextString)
        assertEquals("String!", GraphQLTypeUtil.simplePrint(usage.type))
    }

    @Test
    fun `collectAllVariableUsages -- nested fields with arguments at multiple levels`() {
        val schema = toSchema(
            """
            type Post { id: String }
            type User { id: String, posts(limit: Int): [Post] }
            type Query { user(id: String!): User }
            """.trimIndent()
        )
        val document = Parser.parse("{ user(id: \$userId) { posts(limit: \$postLimit) { id } } }")

        val allUsages = document.collectAllVariableUsages(schema, "Query")

        assertEquals(2, allUsages.size)
        assertTrue(allUsages.containsKey("userId"))
        assertTrue(allUsages.containsKey("postLimit"))

        val userIdUsages = allUsages["userId"]!!
        assertEquals(1, userIdUsages.size)
        val userIdUsage = userIdUsages.first()
        assertEquals("field 'user', argument 'id'", userIdUsage.contextString)
        assertEquals("String!", GraphQLTypeUtil.simplePrint(userIdUsage.type))

        val postLimitUsages = allUsages["postLimit"]!!
        assertEquals(1, postLimitUsages.size)
        val postLimitUsage = postLimitUsages.first()
        assertEquals("field 'posts', argument 'limit'", postLimitUsage.contextString)
        assertEquals("Int", GraphQLTypeUtil.simplePrint(postLimitUsage.type))
    }

    @Test
    fun `collectAllVariableUsages -- mixed directives and field arguments`() {
        val schema = toSchema("type Query { field(arg: String!): String }")
        val document = Parser.parse("{ field(arg: \$fieldVar) @skip(if: \$skipVar) }")

        val allUsages = document.collectAllVariableUsages(schema, "Query")

        assertEquals(2, allUsages.size)
        assertTrue(allUsages.containsKey("fieldVar"))
        assertTrue(allUsages.containsKey("skipVar"))

        val fieldVarUsages = allUsages["fieldVar"]!!
        assertEquals(1, fieldVarUsages.size)
        val fieldVarUsage = fieldVarUsages.first()
        assertEquals("field 'field', argument 'arg'", fieldVarUsage.contextString)
        assertEquals("String!", GraphQLTypeUtil.simplePrint(fieldVarUsage.type))

        val skipVarUsages = allUsages["skipVar"]!!
        assertEquals(1, skipVarUsages.size)
        val skipVarUsage = skipVarUsages.first()
        assertEquals("directive 'skip', argument 'if'", skipVarUsage.contextString)
        assertEquals("Boolean!", GraphQLTypeUtil.simplePrint(skipVarUsage.type))
    }

    @Test
    fun `collectAllVariableUsages -- union type with inline fragments`() {
        val schema = toSchema(
            """
            type User { name(filter: String): String }
            type Post { title(filter: String): String }
            union SearchResult = User | Post
            type Query { search(query: String!): SearchResult }
            """.trimIndent()
        )
        val document = Parser.parse(
            """
            {
              search(query: ${'$'}searchQuery) {
                ... on User {
                  name(filter: ${'$'}nameFilter)
                }
                ... on Post {
                  title(filter: ${'$'}titleFilter)
                }
              }
            }
            """.trimIndent()
        )

        val allUsages = document.collectAllVariableUsages(schema, "Query")

        assertEquals(3, allUsages.size)
        assertTrue(allUsages.containsKey("searchQuery"))
        assertTrue(allUsages.containsKey("nameFilter"))
        assertTrue(allUsages.containsKey("titleFilter"))

        val searchQueryUsages = allUsages["searchQuery"]!!
        assertEquals(1, searchQueryUsages.size)
        val searchQueryUsage = searchQueryUsages.first()
        assertEquals("field 'search', argument 'query'", searchQueryUsage.contextString)
        assertEquals("String!", GraphQLTypeUtil.simplePrint(searchQueryUsage.type))

        val nameFilterUsages = allUsages["nameFilter"]!!
        assertEquals(1, nameFilterUsages.size)
        val nameFilterUsage = nameFilterUsages.first()
        assertEquals("field 'name', argument 'filter'", nameFilterUsage.contextString)
        assertEquals("String", GraphQLTypeUtil.simplePrint(nameFilterUsage.type))

        val titleFilterUsages = allUsages["titleFilter"]!!
        assertEquals(1, titleFilterUsages.size)
        val titleFilterUsage = titleFilterUsages.first()
        assertEquals("field 'title', argument 'filter'", titleFilterUsage.contextString)
        assertEquals("String", GraphQLTypeUtil.simplePrint(titleFilterUsage.type))
    }

    @Test
    fun `collectAllVariableUsages -- directive with nested array argument containing variables`() {
        val schema = toSchema(
            """
            directive @matrix(values: [[Int!]!]!) on FIELD
            type Query { field: String }
            """.trimIndent()
        )
        val document = Parser.parse("{ field @matrix(values: [[\$var1, 5], [\$var3]]) }")

        val allUsages = document.collectAllVariableUsages(schema, "Query")

        assertEquals(2, allUsages.size)
        assertTrue(allUsages.containsKey("var1"))
        assertTrue(allUsages.containsKey("var3"))

        // All variables should have the same context (the directive argument)
        // and the same inner type (Int!), even when mixed with literals
        // Array elements never have default values, so hasDefaultValue should be false
        allUsages.forEach { (varName, usages) ->
            assertEquals(1, usages.size, "Variable $varName should have exactly one usage")
            val usage = usages.first()
            assertEquals("directive 'matrix', argument 'values'", usage.contextString)
            assertEquals("Int!", GraphQLTypeUtil.simplePrint(usage.type))
            assertEquals(false, usage.hasDefaultValue, "Array elements should not have default values")
        }
    }

    @Test
    fun `collectAllVariableUsages -- array elements do not inherit parent hasDefaultValue`() {
        val schema = toSchema(
            """
            directive @filter(values: [String!]! = ["default1", "default2"]) on FIELD
            type Query { field: String }
            """.trimIndent()
        )
        val document = Parser.parse("{ field @filter(values: [\$var1, \$var2]) }")

        val allUsages = document.collectAllVariableUsages(schema, "Query")

        assertEquals(2, allUsages.size)
        assertTrue(allUsages.containsKey("var1"))
        assertTrue(allUsages.containsKey("var2"))

        // The 'values' argument has a default value, but variables inside the array
        // should NOT inherit this - array elements cannot have default values
        allUsages.forEach { (varName, usages) ->
            assertEquals(1, usages.size, "Variable $varName should have exactly one usage")
            val usage = usages.first()
            assertEquals("directive 'filter', argument 'values'", usage.contextString)
            assertEquals("String!", GraphQLTypeUtil.simplePrint(usage.type))
            assertEquals(
                false,
                usage.hasDefaultValue,
                "Array element variables should have hasDefaultValue=false even when parent argument has a default"
            )
        }
    }

    @Test
    fun `collectAllVariableUsages -- directive with deeply nested array of input object type`() {
        val schema = toSchema(
            """
            input NestedInput { value: String! }
            input ContainerInput { nested: NestedInput! }
            directive @complex(data: [[ContainerInput!]!]!) on FIELD
            type Query { field: String }
            """.trimIndent()
        )
        val document = Parser.parse(
            """
            {
                field @complex(data: [
                    [${'$'}var1, { nested: { value: ${'$'}var2 } }],
                    [{ nested: { value: ${'$'}var3 } }]
                ])
            }
            """.trimIndent()
        )

        val allUsages = document.collectAllVariableUsages(schema, "Query")

        assertEquals(3, allUsages.size)
        assertTrue(allUsages.containsKey("var1"))
        assertTrue(allUsages.containsKey("var2"))
        assertTrue(allUsages.containsKey("var3"))

        // var1 is passed as the whole object, so it has ContainerInput! type
        val var1Usages = allUsages["var1"]!!
        assertEquals(1, var1Usages.size)
        assertEquals("directive 'complex', argument 'data'", var1Usages.first().contextString)
        assertEquals("ContainerInput!", GraphQLTypeUtil.simplePrint(var1Usages.first().type))

        // var2 and var3 are deeply nested in NestedInput.value
        val var2Usages = allUsages["var2"]!!
        assertEquals(1, var2Usages.size)
        assertEquals("input field 'NestedInput.value'", var2Usages.first().contextString)
        assertEquals("String!", GraphQLTypeUtil.simplePrint(var2Usages.first().type))

        val var3Usages = allUsages["var3"]!!
        assertEquals(1, var3Usages.size)
        assertEquals("input field 'NestedInput.value'", var3Usages.first().contextString)
        assertEquals("String!", GraphQLTypeUtil.simplePrint(var3Usages.first().type))
    }

    @Test
    fun `collectAllVariableUsages -- collects variables from fragment spread`() {
        val schema = toSchema("type Query { foo: Foo } type Foo { bar(filter: String): String, baz: String }")
        val document = Parser.parse(
            """
            {
                foo {
                    ...FooFragment
                }
            }
            fragment FooFragment on Foo {
                bar(filter: ${'$'}filterValue)
                baz @include(if: ${'$'}shouldInclude)
            }
            """.trimIndent()
        )

        val fragmentDef = document.getDefinitionsOfType(FragmentDefinition::class.java).first()
        val fragmentMap = mapOf(fragmentDef.name to fragmentDef)

        val allUsages = document.collectAllVariableUsages(schema, "Query", fragmentMap)

        assertEquals(2, allUsages.size)
        assertTrue(allUsages.containsKey("filterValue"))
        assertTrue(allUsages.containsKey("shouldInclude"))

        val filterUsages = allUsages["filterValue"]!!
        assertEquals(1, filterUsages.size)
        assertEquals("field 'bar', argument 'filter'", filterUsages.first().contextString)
        assertEquals("String", GraphQLTypeUtil.simplePrint(filterUsages.first().type))

        val includeUsages = allUsages["shouldInclude"]!!
        assertEquals(1, includeUsages.size)
        assertEquals("directive 'include', argument 'if'", includeUsages.first().contextString)
        assertEquals("Boolean!", GraphQLTypeUtil.simplePrint(includeUsages.first().type))
    }

    @Test
    fun `collectAllVariableUsages -- collects variables from directive on fragment spread`() {
        val schema = toSchema("type Query { foo: Foo } type Foo { bar: String }")
        val document = Parser.parse(
            """
            {
                foo {
                    ...FooFragment @include(if: ${'$'}shouldIncludeFragment)
                }
            }
            fragment FooFragment on Foo {
                bar
            }
            """.trimIndent()
        )

        val fragmentDef = document.getDefinitionsOfType(FragmentDefinition::class.java).first()
        val fragmentMap = mapOf(fragmentDef.name to fragmentDef)

        val allUsages = document.collectAllVariableUsages(schema, "Query", fragmentMap)

        assertEquals(1, allUsages.size)
        assertTrue(allUsages.containsKey("shouldIncludeFragment"))

        val usages = allUsages["shouldIncludeFragment"]!!
        assertEquals(1, usages.size)
        assertEquals("directive 'include', argument 'if'", usages.first().contextString)
        assertEquals("Boolean!", GraphQLTypeUtil.simplePrint(usages.first().type))
    }

    @Test
    fun `collectVariableUsages -- gets all usages for variable`() {
        val schema = toSchema("type Query { field1(arg: String!): String, field2(arg: Int!): String, field3(arg: String!): String }")
        val document = Parser.parse("{ field1(arg: \$var1), field2(arg: \$var2), field3(arg: \$var1) }")

        val var1Usages = document.collectVariableUsages(schema, "var1", "Query")

        assertEquals(2, var1Usages.size)
        val usageContexts = var1Usages.map { it.contextString }.toSet()
        assertTrue(usageContexts.contains("field 'field1', argument 'arg'"))
        assertTrue(usageContexts.contains("field 'field3', argument 'arg'"))
        var1Usages.forEach { usage ->
            assertEquals("String!", GraphQLTypeUtil.simplePrint(usage.type))
        }
    }

    @Test
    fun `collectVariableDefinitions -- transforms usages to definitions`() {
        val schema = toSchema(
            """
            type Query {
                f1(a: String): String
                f2(a: [Int!]): String
                f3(a: String): String
                f4(a: String!): String
            }
            """.trimIndent()
        )
        val document = Parser.parse("{ f1(a: \$var1), f2(a: \$var2), f3(a: \$var1), f4(a: \$var1) }")

        val definitions = document.collectVariableDefinitions(schema, "Query")

        assertEquals(2, definitions.size)
        val varNames = definitions.map { it.name }.toSet()
        assertTrue(varNames.contains("var1"))
        assertTrue(varNames.contains("var2"))

        val var1Def = definitions.first { it.name == "var1" }
        assertEquals("String!", AstPrinter.printAst(var1Def.type))

        val var2Def = definitions.first { it.name == "var2" }
        assertEquals("[Int!]", AstPrinter.printAst(var2Def.type))
    }

    @Test
    fun `combineNullabilityRequirements -- simple case`() {
        val result = combineNullabilityRequirements(Scalars.GraphQLString, GraphQLNonNull.nonNull(Scalars.GraphQLString))
        assertEquals("String!", GraphQLTypeUtil.simplePrint(result))
    }

    @Test
    fun `combineNullabilityRequirements -- list with nullable vs non-null elements`() {
        val type1 = GraphQLList.list(Scalars.GraphQLString)
        val type2 = GraphQLList.list(GraphQLNonNull.nonNull(Scalars.GraphQLString))
        val result = combineNullabilityRequirements(type1, type2)
        assertEquals("[String!]", GraphQLTypeUtil.simplePrint(result))
    }

    @Test
    fun `combineNullabilityRequirements -- nullable vs non-null list`() {
        val type1 = GraphQLList.list(Scalars.GraphQLString)
        val type2 = GraphQLNonNull.nonNull(GraphQLList.list(Scalars.GraphQLString))
        val result = combineNullabilityRequirements(type1, type2)
        assertEquals("[String]!", GraphQLTypeUtil.simplePrint(result))
    }

    @Test
    fun `combineNullabilityRequirements -- nested lists`() {
        val type1 = GraphQLList.list(GraphQLNonNull.nonNull(GraphQLList.list(Scalars.GraphQLString))) // [[String]!]
        val type2 = GraphQLNonNull.nonNull(GraphQLList.list(GraphQLList.list(GraphQLNonNull.nonNull(Scalars.GraphQLString)))) // [[String!]]!
        val result = combineNullabilityRequirements(type1, type2)
        assertEquals("[[String!]!]!", GraphQLTypeUtil.simplePrint(result))
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
