package viaduct.tenant.codegen.cli

import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.tenant.codegen.ksp.ResolverParams

class RequiredSelectionSetValidatorTest {
    companion object {
        private val TEST_SCHEMA_SDL = """
            directive @namespaceType on OBJECT

            type Query {
                user(id: ID!): User
            }

            type Mutation {
                createUser(name: String!): User
                namespace: MutationNamespace
            }

            type User {
                id: ID!
                name: String
                friend: User
            }

            type Photo {
                id: ID!
                url: String
            }

            type MutationNamespace @namespaceType {
                createUser(name: String!): User
            }
        """.trimIndent()

        private val validator = RequiredSelectionSetValidator(
            UnExecutableSchemaGenerator.makeUnExecutableSchema(SchemaParser().parse(TEST_SCHEMA_SDL)),
        )

        private fun field(
            typeName: String = "User",
            fieldName: String = "name",
        ): ResolverParams.Field =
            ResolverParams.Field(
                implFqn = "com.example.TestResolver",
                typeName = typeName,
                fieldName = fieldName,
                resolverBaseClass = "com.example.bases.Base",
                isBatching = false,
                isSelective = false,
            )

        /** Runs [RequiredSelectionSetValidator.validate] with the same string for the entry and expanded forms. */
        private fun validate(
            selections: String,
            typeName: String,
            isQuery: Boolean = false,
            expandedSelections: String = selections,
            field: ResolverParams.Field = field(),
        ): List<String> =
            mutableListOf<String>().also { errors ->
                validator.validate(
                    normalizedSelections = selections,
                    expandedSelections = expandedSelections,
                    typeName = typeName,
                    isQuery = isQuery,
                    field = field,
                    errors = errors,
                )
            }
    }

    @Test
    fun `valid object selection set on parent type passes`() {
        val errors = validate("fragment Main on User { id name }", typeName = "User")
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    @Test
    fun `valid query selection set on root query type passes`() {
        val errors = validate("fragment Main on Query { user(id: \"1\") { id } }", typeName = "Query", isQuery = true)
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    @Test
    fun `object selection set on wrong parent type fails`() {
        val errors = validate("fragment Main on Photo { url }", typeName = "User")
        assertTrue(errors.any { it.contains("must be on the parent type (User)") }, errors.toString())
    }

    @Test
    fun `query selection set not on root query type fails`() {
        val errors = validate("fragment Main on User { id }", typeName = "User", isQuery = true)
        assertTrue(errors.any { it.contains("must be on the root query type (Query)") }, errors.toString())
    }

    @Test
    fun `mutation resolver with object selection set fails`() {
        val errors = validate(
            "fragment Main on Mutation { createUser(name: \"x\") { id } }",
            typeName = "Mutation",
        )
        assertTrue(errors.any { it.contains("should not set objectValueFragment") }, errors.toString())
    }

    @Test
    fun `namespaced mutation resolver with object selection set fails`() {
        val errors = validate(
            "fragment Main on MutationNamespace { createUser(name: \"x\") { id } }",
            typeName = "MutationNamespace",
        )
        assertTrue(errors.any { it.contains("should not set objectValueFragment") }, errors.toString())
    }

    @Test
    fun `selection referencing unknown field fails with compilation-schema hint`() {
        val errors = validate("fragment Main on User { notAField }", typeName = "User")
        assertTrue(errors.any { it.contains("notAField") }, errors.toString())
        assertTrue(errors.any { it.contains("missing field or type in your tenant's compilation schema") }, errors.toString())
        assertTrue(errors.any { it.contains("tenant-compilation-schemas") }, errors.toString())
    }

    @Test
    fun `unused fragments are not reported as errors`() {
        // An expanded document may carry a named fragment that the entry selection does not spread;
        // UnusedFragment must be filtered out.
        val errors = validate(
            selections = "fragment Main on User { id }",
            typeName = "User",
            expandedSelections = "fragment Main on User { id }\nfragment Extra on User { name }",
        )
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    @Test
    fun `schema validation runs against the expanded selections`() {
        // $expandedSelections carries a spread whose body references an unknown field; the schema
        // check must follow the appended fragment and fail.
        val errors = validate(
            selections = "fragment Main on User { ...UserFields }",
            typeName = "User",
            expandedSelections = "fragment Main on User { ...UserFields }\nfragment UserFields on User { bogusField }",
        )
        assertTrue(errors.any { it.contains("bogusField") }, errors.toString())
    }
}
