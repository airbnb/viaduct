package viaduct.ksp.validation

import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import graphql.validation.QueryComplexityLimits
import graphql.validation.Validator
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ValidateResolverFragmentsTest {
    companion object {
        private val TEST_SCHEMA_SDL = """
            directive @namespaceType on OBJECT

            type Query {
                user(id: ID!): User
                namespace: QueryNamespace
            }

            type Mutation {
                createUser(name: String!): User
                namespace: MutationNamespace
                nestedNamespace: MutationRootNamespace
            }

            type User {
                id: ID!
                name: String
                friend: User
            }

            type Photo {
                id: ID!
                url: String
                caption: String
            }

            type QueryNamespace @namespaceType {
                cachedValue: String
                user(id: ID!): User
            }

            type MutationNamespace @namespaceType {
                priorValue: String
                createUser(name: String!): User
            }

            type MutationRootNamespace @namespaceType {
                nested: NestedMutationNamespace
            }

            type NestedMutationNamespace @namespaceType {
                priorValue: String
                createUser(name: String!): User
            }

            directive @generateMock(id: String!) repeatable on FRAGMENT_DEFINITION
        """.trimIndent()

        private val TEST_SCHEMA = UnExecutableSchemaGenerator.makeUnExecutableSchema(
            SchemaParser().parse(TEST_SCHEMA_SDL)
        )

        private val TEST_VALIDATOR = Validator()

        private fun createSpec(
            fragment: String,
            fragmentType: ResolverFragmentType,
            typeName: String,
            packageName: String = "com.example",
            className: String = "TestResolver",
            sourceFileName: String = "TestResolver.kt"
        ): ResolverFragmentSpec =
            ResolverFragmentSpec(
                fragment = fragment,
                metadata = Metadata(
                    packageName = packageName,
                    className = className,
                    sourceFileName = sourceFileName,
                    typeName = typeName,
                    fragmentType = fragmentType,
                ),
            )

        private fun createValidator(specs: List<ResolverFragmentSpec>) =
            ValidateResolverFragments(
                specs = specs,
                schema = TEST_SCHEMA,
                fragmentValidator = TEST_VALIDATOR
            )
    }

    @Test
    fun `validation filters out UnusedFragment errors`() {
        val unusedFragmentString = """
            fragment Main on User {
                id
                name
            }
        """.trimIndent()

        val spec = createSpec(
            fragment = unusedFragmentString,
            fragmentType = ResolverFragmentType.OBJECT,
            typeName = "User"
        )

        val errors = createValidator(listOf(spec)).validate()
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `invalid - fragment contains field not in schema`() {
        val fragmentWithMissingField = """
            fragment Main on User {
                id
                thisFieldDoesNotExist
            }
        """.trimIndent()

        val spec = createSpec(
            fragment = fragmentWithMissingField,
            fragmentType = ResolverFragmentType.OBJECT,
            typeName = "User"
        )

        val errors = createValidator(listOf(spec)).validate()
        assertTrue(errors.isNotEmpty())
        assertTrue(errors[0].contains("thisFieldDoesNotExist"))
        assertTrue(errors[0].contains("compilation schema"))
    }

    @Test
    fun `valid objectValueFragment type`() {
        val spec = createSpec(
            fragment = "fragment _ on User { id name }",
            fragmentType = ResolverFragmentType.OBJECT,
            typeName = "User"
        )

        val errors = createValidator(listOf(spec)).validate()
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `valid objectValueFragment can exceed graphql java default query depth limit`() {
        val nestedFriendDepth = QueryComplexityLimits.DEFAULT.maxDepth + 1
        val deepFragmentString = buildString {
            append("fragment Main on User {\n")
            repeat(nestedFriendDepth) { append("friend {\n") }
            append("id\n")
            repeat(nestedFriendDepth) { append("}\n") }
            append("}")
        }

        val spec = createSpec(
            fragment = deepFragmentString,
            fragmentType = ResolverFragmentType.OBJECT,
            typeName = "User"
        )

        val errors = createValidator(listOf(spec)).validate()
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    @Test
    fun `invalid objectValueFragment type`() {
        val spec = createSpec(
            fragment = "fragment _ on Photo { url }",
            fragmentType = ResolverFragmentType.OBJECT,
            typeName = "User"
        )

        val errors = createValidator(listOf(spec)).validate()
        assertTrue(errors.isNotEmpty())
        assertTrue(errors[0].contains("must be on the parent type"))
    }

    @Test
    fun `invalid - mutation resolver with objectValueFragment`() {
        val spec = createSpec(
            fragment = "fragment _ on Mutation { createUser(name: \"test\") { id } }",
            fragmentType = ResolverFragmentType.OBJECT,
            typeName = "Mutation"
        )

        val errors = createValidator(listOf(spec)).validate()
        assertTrue(errors.isNotEmpty())
        assertTrue(errors[0].contains("Mutation resolver"))
        assertTrue(errors[0].contains("should not set objectValueFragment"))
    }

    @Test
    fun `invalid - namespaced mutation resolver with objectValueFragment`() {
        val spec = createSpec(
            fragment = "fragment _ on MutationNamespace { priorValue }",
            fragmentType = ResolverFragmentType.OBJECT,
            typeName = "MutationNamespace"
        )

        val errors = createValidator(listOf(spec)).validate()
        assertTrue(errors.isNotEmpty())
        assertTrue(errors[0].contains("Mutation resolver"))
        assertTrue(errors[0].contains("should not set objectValueFragment"))
    }

    @Test
    fun `invalid - nested namespaced mutation resolver with objectValueFragment`() {
        val spec = createSpec(
            fragment = "fragment _ on NestedMutationNamespace { priorValue }",
            fragmentType = ResolverFragmentType.OBJECT,
            typeName = "NestedMutationNamespace"
        )

        val errors = createValidator(listOf(spec)).validate()
        assertTrue(errors.isNotEmpty())
        assertTrue(errors[0].contains("Mutation resolver"))
        assertTrue(errors[0].contains("should not set objectValueFragment"))
    }

    @Test
    fun `valid query namespace resolver with objectValueFragment`() {
        val spec = createSpec(
            fragment = "fragment _ on QueryNamespace { cachedValue }",
            fragmentType = ResolverFragmentType.OBJECT,
            typeName = "QueryNamespace"
        )

        val errors = createValidator(listOf(spec)).validate()
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    @Test
    fun `valid queryValueFragment type`() {
        val spec = createSpec(
            fragment = "fragment _ on Query { user(id: \"1\") { id } }",
            fragmentType = ResolverFragmentType.QUERY,
            typeName = "User"
        )

        val errors = createValidator(listOf(spec)).validate()
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `invalid queryValueFragment type`() {
        val spec = createSpec(
            fragment = "fragment _ on User { id }",
            fragmentType = ResolverFragmentType.QUERY,
            typeName = "User"
        )

        val errors = createValidator(listOf(spec)).validate()
        assertTrue(errors.isNotEmpty())
        assertTrue(errors[0].contains("must be on the root query type"))
    }

    @Test
    fun `validates multiple specs and reports all errors`() {
        val spec1 = createSpec(
            fragment = "fragment _ on Photo { url }",
            fragmentType = ResolverFragmentType.OBJECT,
            typeName = "User",
            packageName = "com.example",
            className = "Resolver1"
        )

        val spec2 = createSpec(
            fragment = "fragment _ on User { thisFieldDoesNotExist }",
            fragmentType = ResolverFragmentType.OBJECT,
            typeName = "User",
            packageName = "com.example",
            className = "Resolver2"
        )

        val errors = createValidator(listOf(spec1, spec2)).validate()
        assertEquals(2, errors.size)
        assertTrue(errors.any { it.contains("Resolver1") })
        assertTrue(errors.any { it.contains("Resolver2") })
    }
}
