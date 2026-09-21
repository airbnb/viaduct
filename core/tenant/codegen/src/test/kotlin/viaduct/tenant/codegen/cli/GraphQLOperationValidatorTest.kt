package viaduct.tenant.codegen.cli

import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.tenant.codegen.ksp.OperationDescriptor
import viaduct.tenant.codegen.ksp.OperationKind

class GraphQLOperationValidatorTest {
    companion object {
        private val TEST_SCHEMA_SDL = """
            directive @tombstoned on FIELD_DEFINITION | OBJECT

            type Query {
                user(id: ID!): User
                viewer: User
            }

            type Mutation {
                createUser(name: String!): User
            }

            type User {
                id: ID!
                name: String
                tombstonedName: String @tombstoned @deprecated(reason: "Use name instead")
            }
        """.trimIndent()

        private val schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(SchemaParser().parse(TEST_SCHEMA_SDL))

        private fun validate(
            text: String,
            kind: OperationKind = OperationKind.QUERY,
            fragmentsByName: Map<String, String> = emptyMap(),
            forbiddenSelectionDirectives: Set<String> = emptySet(),
        ): List<String> =
            mutableListOf<String>().also { errors ->
                GraphQLOperationValidator(schema, forbiddenSelectionDirectives).validate(
                    OperationDescriptor(text = text, kind = kind, implFqn = "com.example.TestOperation"),
                    fragmentsByName,
                    errors,
                )
            }
    }

    @Test
    fun `valid query operation passes`() {
        val errors = validate("query(\$id: ID!) { user(id: \$id) { id name } }", OperationKind.QUERY)
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    @Test
    fun `anonymous shorthand query operation passes`() {
        val errors = validate("{ viewer { id } }", OperationKind.QUERY)
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    @Test
    fun `valid mutation operation passes`() {
        val errors = validate("mutation { createUser(name: \"a\") { id } }", OperationKind.MUTATION)
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    @Test
    fun `operation spreading an external fragment passes`() {
        val errors = validate(
            "{ viewer { ...UserFields } }",
            OperationKind.QUERY,
            mapOf("UserFields" to "fragment UserFields on User { id name }"),
        )
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    @Test
    fun `local fragment shadows a same-named external fragment`() {
        // The external UserFields selects a non-existent field; the local one is valid and must win.
        val errors = validate(
            """
            { viewer { ...UserFields } }
            fragment UserFields on User { id }
            """.trimIndent(),
            OperationKind.QUERY,
            mapOf("UserFields" to "fragment UserFields on User { doesNotExist }"),
        )
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    @Test
    fun `multiple operations fail`() {
        val errors = validate("query A { viewer { id } } query B { viewer { id } }", OperationKind.QUERY)
        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("exactly one operation"), errors.joinToString("\n"))
    }

    @Test
    fun `mutation declared on a QueryFromAnnotation fails`() {
        val errors = validate("mutation { createUser(name: \"a\") { id } }", OperationKind.QUERY)
        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("QueryFromAnnotation"), errors.joinToString("\n"))
    }

    @Test
    fun `query declared on a MutationFromAnnotation fails`() {
        val errors = validate("{ viewer { id } }", OperationKind.MUTATION)
        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("MutationFromAnnotation"), errors.joinToString("\n"))
    }

    @Test
    fun `unknown field fails schema validation`() {
        val errors = validate("{ viewer { doesNotExist } }", OperationKind.QUERY)
        assertTrue(errors.any { it.contains("doesNotExist") }, errors.joinToString("\n"))
    }

    @Test
    fun `unknown field inside an external fragment fails schema validation`() {
        val errors = validate(
            "{ viewer { ...UserFields } }",
            OperationKind.QUERY,
            mapOf("UserFields" to "fragment UserFields on User { doesNotExist }"),
        )
        assertTrue(errors.any { it.contains("doesNotExist") }, errors.joinToString("\n"))
    }

    @Test
    fun `selection of a field carrying a forbidden directive fails`() {
        val errors = validate("{ viewer { tombstonedName } }", forbiddenSelectionDirectives = setOf("tombstoned"))
        assertEquals(
            listOf(
                "@GraphQLOperation on com.example.TestOperation selects User.tombstonedName, which carries @tombstoned. " +
                    "Fields with this directive must not be selected.",
            ),
            errors,
        )
    }

    @Test
    fun `selection of a field carrying a forbidden directive inside an external fragment fails`() {
        val errors = validate(
            "{ viewer { ...UserFields } }",
            fragmentsByName = mapOf("UserFields" to "fragment UserFields on User { tombstonedName }"),
            forbiddenSelectionDirectives = setOf("tombstoned"),
        )
        assertTrue(errors.any { it.contains("User.tombstonedName") && it.contains("@tombstoned") }, errors.joinToString("\n"))
    }

    @Test
    fun `selection of a field carrying a forbidden directive in a mutation fails`() {
        val errors = validate(
            "mutation { createUser(name: \"a\") { tombstonedName } }",
            OperationKind.MUTATION,
            forbiddenSelectionDirectives = setOf("tombstoned"),
        )
        assertTrue(errors.any { it.contains("User.tombstonedName") }, errors.joinToString("\n"))
    }

    @Test
    fun `typename selection is ignored by the forbidden directive check`() {
        val errors = validate("{ __typename viewer { __typename id } }", forbiddenSelectionDirectives = setOf("tombstoned"))
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    @Test
    fun `selection of a field carrying a forbidden directive passes when none are configured`() {
        val errors = validate("{ viewer { tombstonedName } }")
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }
}
