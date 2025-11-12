package viaduct.engine.runtime.tenantloading

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import viaduct.arbitrary.graphql.asSchema
import viaduct.engine.api.Coordinate
import viaduct.engine.api.FromArgument
import viaduct.engine.api.Validated
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.mocks.MockRequiredSelectionSetRegistry

class FromArgumentVariablesHaveValidPathsTest {
    @Test
    fun `valid -- simple argument path`() {
        Fixture("type Query { foo(x: Int!): Int!, bar(y: Int): Int }") {
            // Simple argument reference - variable name matches argument name
            assertValid(
                "Query" to "foo",
                "bar(y: \$x)",
                listOf(FromArgument("x", listOf("x")))
            )

            // Variable name different from the argument name
            assertValid(
                "Query" to "foo",
                "bar(y: \$myVar)",
                listOf(FromArgument("myVar", listOf("x")))
            )
        }
    }

    @Test
    fun `valid -- nested object path`() {
        Fixture(
            """
            input UserInput { name: String!, age: Int! }
            input NestedInput { user: UserInput!, count: Int! }
            type Query { foo(input: NestedInput!): String!, bar(name: String!): String }
            """.trimIndent()
        ) {
            // Nested path traversal
            assertValid(
                "Query" to "foo",
                "bar(name: \$userName)",
                listOf(FromArgument("userName", listOf("input", "user", "name")))
            )

            // Multiple nested paths
            assertValid(
                "Query" to "foo",
                "bar(name: \$userName)",
                listOf(
                    FromArgument("userName", listOf("input", "user", "name")),
                    FromArgument("userAge", listOf("input", "user", "age"))
                )
            )
        }
    }

    @Test
    fun `valid -- field not directly on Query object`() {
        Fixture(
            """
            type BarObject { buzz(buzzArg: String!): String }
            type Query { foo(x: String!): String!, bar: BarObject }
            """.trimIndent()
        ) {
            assertValid(
                "Query" to "foo",
                "bar { buzz(buzzArg: \$buzz) }",
                listOf(FromArgument("buzz", listOf("x")))
            )
        }
    }

    @Test
    fun `valid -- Validated argument wrapper`() {
        Fixture("type Query { foo(x: Int!): Int!, bar(y: Int): Int }") {
            // Test that Validated wrapper is handled correctly
            assertValid(
                "Query" to "foo",
                "bar(y: \$x)",
                listOf(
                    Validated(
                        FromArgument("x", listOf("x"))
                    )
                )
            )
        }
    }

    @Test
    fun `invalid -- type mismatch argument types`() {
        Fixture("type Query { foo(x: Int!): Int!, bar(y: String): String }") {
            val exception = assertInvalid(
                "Query" to "foo",
                "bar(y: \$x)",
                listOf(FromArgument("x", listOf("x")))
            )

            assertTrue(exception.message.contains("Type mismatch"))
        }
    }

    @Test
    fun `invalid -- empty path`() {
        // The FromArgument constructor itself validates empty paths,
        // so we test that the constructor throws the expected exception
        val exception = assertThrows<IllegalArgumentException> {
            FromArgument("x", emptyList())
        }

        assertTrue(exception.message!!.contains("Argument path for variable `x` is empty"))
    }

    @Test
    fun `invalid -- argument does not exist`() {
        Fixture("type Query { foo(x: Int!): Int!, bar: String }") {
            val exception = assertInvalid(
                "Query" to "foo",
                "bar",
                listOf(FromArgument("nonExistent", listOf("nonExistent")))
            )

            assertTrue(exception.message.contains("Argument 'nonExistent' does not exist"))
            assertEquals("nonExistent", exception.variableName)
        }
    }

    @Test
    fun `invalid -- field does not exist in input type`() {
        Fixture(
            """
            input UserInput { name: String!, age: Int! }
            type Query { foo(user: UserInput!): String! }
            """.trimIndent()
        ) {
            val exception = assertInvalid(
                "Query" to "foo",
                "foo",
                listOf(FromArgument("invalid", listOf("user", "invalidField")))
            )

            assertTrue(exception.message.contains("Field 'invalidField' does not exist"))
        }
    }

    @Test
    fun `invalid -- cannot traverse through list argument`() {
        Fixture(
            """
            input UserInput { name: String! }
            type Query { foo(users: [UserInput!]!): String! }
            """.trimIndent()
        ) {
            val exception = assertInvalid(
                "Query" to "foo",
                "foo",
                listOf(FromArgument("userName", listOf("users", "name")))
            )

            assertTrue(exception.message.contains("Path traversal through lists is not supported"))
        }
    }

    @Test
    fun `invalid -- cannot traverse through list input field`() {
        Fixture(
            """
            input UserInput { name:String! }
            input Inp { users:[UserInput!]! }
            type Query { foo(inp:Inp):String! }
            """.trimIndent()
        ) {
            val exception = assertInvalid(
                "Query" to "foo",
                "foo",
                listOf(FromArgument("userName", listOf("inp", "users", "name")))
            )

            assertTrue(exception.message.contains("Path traversal through lists is not supported"))
        }
    }

    @Test
    fun `invalid -- cannot traverse non-object type`() {
        Fixture("type Query { foo(x: String!): String! }") {
            val exception = assertInvalid(
                "Query" to "foo",
                "foo",
                listOf(FromArgument("nested", listOf("x", "nonexistent")))
            )

            assertTrue(exception.message.contains("Cannot traverse to field 'nonexistent'"))
        }
    }

    @Test
    fun `invalid -- nullness checks`() {
        // Simple nullness check
        Fixture("type Query { foo(x: Int): Int, bar(y: Int!): Int }") {
            assertInvalid(
                "Query" to "foo",
                "bar(y: \$x)",
                listOf(
                    FromArgument("x", listOf("x"))
                )
            )
        }

        // Non-null field in a nullable context
        Fixture(
            """
                input MyInput { x:Int! }
                type Query { foo(myInput:MyInput):Int, bar(y:Int!):Int }
            """.trimIndent()
        ) {
            assertInvalid(
                "Query" to "foo",
                "bar(y:\$x)",
                listOf(
                    FromArgument("x", listOf("myInput", "x"))
                )
            )
        }
    }

    @Test
    fun `FromArgument used on mutation field`() {
        Fixture(
            """
            type Query {
                getUser(id: ID!): String
            }
            type Mutation {
                createUser(name: String!): String!
                updateUser(id: ID!, data: String): String
            }
            """.trimIndent()
        ) {
            assertValid(
                "Mutation" to "createUser",
                "updateUser(data: \$userData)",
                listOf(
                    FromArgument("userData", listOf("name"))
                )
            )
        }
    }

    @Test
    fun `variable reference used in input object field`() {
        Fixture(
            """
            input UserInput {
                name: String!
                age: Int!
            }
            type Query {
                createUser(input: UserInput!): String!
                foo(nameArg: String!, ageArg: Int!): String
            }
            """.trimIndent()
        ) {
            // Valid case: correct types for input object fields
            assertValid(
                "Query" to "foo",
                "createUser(input: { name: \$userName, age: \$userAge })",
                listOf(
                    FromArgument("userName", listOf("nameArg")), // String! used as String! - should work
                    FromArgument("userAge", listOf("ageArg")) // Int! used as Int! - should work
                )
            )
        }

        Fixture(
            """
            input UserInput {
                name: String!
                age: Int!
            }
            type Query {
                createUser(input: UserInput!): String!
                foo(nameArg: String!): String
            }
            """.trimIndent()
        ) {
            assertInvalid(
                "Query" to "foo",
                "createUser(input: { name: \$userName, age: \$userAge })",
                listOf(
                    FromArgument("userName", listOf("nameArg")), // String! used as String! - should work
                    FromArgument("userAge", listOf("nameArg")) // String! used as Int! - should fail
                )
            )
        }
    }

    @Test
    fun `variable reference used in directive argument`() {
        Fixture(
            """
            type Query {
                foo(boolArg: Boolean!): String!
                bar: String
            }
            """.trimIndent()
        ) {
            // Test variable reference in @include directive
            assertValid(
                "Query" to "foo",
                "bar @include(if: \$shouldInclude)",
                listOf(
                    FromArgument("shouldInclude", listOf("boolArg")) // Boolean! used as Boolean! - should work
                )
            )
        }

        // Test @skip directive as well
        Fixture(
            """
            type Query {
                foo(conditionArg: Boolean!): String!
                bar: String
            }
            """.trimIndent()
        ) {
            // Test variable reference in @skip directive
            assertValid(
                "Query" to "foo",
                "bar @skip(if: \$skipCondition)",
                listOf(
                    FromArgument("skipCondition", listOf("conditionArg")) // Boolean! used as Boolean! - should work
                )
            )
        }
    }

    @Test
    fun `nullable variable used in non-nullable location with default value`() {
        Fixture(
            """
            type Query {
                foo(x: Int): String
                bar(y: Int! = 42): String
            }
            """.trimIndent()
        ) {
            // According to GraphQL spec, this should be valid:
            // nullable variable (x: Int) used in non-null argument with default (y: Int! = 42)
            assertValid(
                "Query" to "foo",
                "bar(y: \$nullableVar)",
                listOf(
                    FromArgument("nullableVar", listOf("x")) // Int -> Int! with default should work
                )
            )
        }
    }

    @Test
    fun `OneOf input nullability validation`() {
        // valid non-nullable OneOf field wiring
        Fixture(
            """
            input OneOfInput @oneOf {
                stringOption: String
                intOption: Int
            }
            type Query {
                foo(nonNullableValue: String!): String
                testOneOf(input: OneOfInput!): String
            }
            """.trimIndent()
        ) {
            assertValid(
                "Query" to "foo",
                "testOneOf(input: { stringOption: \$value })",
                listOf(
                    FromArgument("value", listOf("nonNullableValue"))
                )
            )
        }

        // valid nullable OneOf field wiring
        Fixture(
            """
            input OneOfInput @oneOf {
                stringOption: String
                intOption: Int
            }
            type Query {
                foo(nullableValue: String): String
                testOneOf(input: OneOfInput!): String
            }
            """.trimIndent()
        ) {
            assertValid(
                "Query" to "foo",
                "testOneOf(input: { stringOption: \$value })",
                listOf(
                    FromArgument("value", listOf("nullableValue"))
                )
            )
        }
    }

    @Test
    fun `valid -- non-list value can be coerced to list context`() {
        Fixture(
            """
            type Query {
                foo(singleString: String!): String
                acceptList(items: [String!]!): String
            }
            """.trimIndent()
        ) {
            assertValid(
                "Query" to "foo",
                "acceptList(items: \$item)",
                listOf(
                    FromArgument("item", listOf("singleString"))
                )
            )
        }

        // Test with a nullable list too
        Fixture(
            """
            type Query {
                foo(singleString: String!): String
                acceptNullableList(items: [String!]): String
            }
            """.trimIndent()
        ) {
            assertValid(
                "Query" to "foo",
                "acceptNullableList(items: \$item)",
                listOf(
                    FromArgument("item", listOf("singleString"))
                )
            )
        }

        // Test nested list
        Fixture(
            """
            type Query {
                foo(innerList: [String!]!): String
                acceptNestedList(items: [[String!]!]!): String
            }
            """.trimIndent()
        ) {
            assertValid(
                "Query" to "foo",
                "acceptNestedList(items: \$innerList)",
                listOf(
                    FromArgument("innerList", listOf("innerList"))
                )
            )
        }

        // Test coercion of a nullable singleton type to a list of a nullable inner type
        Fixture(
            """
            type Query {
                foo(nullableString: String): String
                acceptNullableItems(items: [String]!): String
            }
            """.trimIndent()
        ) {
            assertValid(
                "Query" to "foo",
                "acceptNullableItems(items: \$item)",
                listOf(
                    FromArgument("item", listOf("nullableString"))
                )
            )
        }
    }

    @Test
    fun `invalid -- nullable single value to non-null list items`() {
        // Per spec: nullable values can't be used where non-null list items are expected
        Fixture(
            """
            type Query {
                foo(nullableString: String): String
                acceptList(items: [String!]!): String
            }
            """.trimIndent()
        ) {
            assertInvalid(
                "Query" to "foo",
                "acceptList(items: \$item)",
                listOf(
                    FromArgument("item", listOf("nullableString"))
                )
            )
        }
    }

    @Test
    fun `invalid -- list value used in non-list context`() {
        // The reverse should fail - list values cannot be used in non-list contexts
        Fixture(
            """
            type Query {
                foo(items: [String!]!): String
                acceptSingle(item: String!): String
            }
            """.trimIndent()
        ) {
            val exception = assertInvalid(
                "Query" to "foo",
                "acceptSingle(item: \$items)",
                listOf(
                    FromArgument("items", listOf("items"))
                )
            )

            assertTrue(exception.message.contains("Type mismatch"))
        }
    }

    @Test
    fun `invalid -- fragment spread with variable`() {
        Fixture("type Query { foo(x: String!): Int!, bar(y: Int): Int }") {
            val exception = assertInvalid(
                "Query" to "foo",
                "fragment Main on Query { ...QueryFields } fragment QueryFields on Query { bar(y: \$x) }",
                listOf(FromArgument("x", listOf("x")))
            )
            assertTrue(exception.message.contains("Type mismatch"))
        }
    }

    @Test
    fun `invalid -- fragment spread on non-Query type`() {
        Fixture(
            """
            type Query { foo: String }
            type User { updateName(name: Int!): String!, updateProfile(data: String): String }
            """.trimIndent()
        ) {
            val exception = assertInvalid(
                "User" to "updateName",
                "fragment Main on User { ...UserFields } fragment UserFields on User { updateProfile(data: \$userName) }",
                listOf(FromArgument("userName", listOf("name")))
            )
            assertTrue(exception.message.contains("Type mismatch"))
        }
    }

    private class Fixture(
        sdl: String,
        fn: Fixture.() -> Unit = {}
    ) {
        val schema = ViaductSchema(sdl.asSchema)
        val validator = FromArgumentVariablesHaveValidPaths(schema)

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
        ): InvalidVariableException =
            assertThrows<InvalidVariableException> {
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
}
