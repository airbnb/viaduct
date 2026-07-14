package viaduct.tenant.codegen.cli

import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import graphql.schema.idl.UnExecutableSchemaGenerator
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.tenant.codegen.ksp.ResolverParams

class RequiredSelectionSetValidatorTest {
    @TempDir
    private lateinit var tempDir: File

    companion object {
        private val FEATURE_SCHEMA_SDL = """
            directive @namespaceType on OBJECT
            directive @tenantLocal on FIELD_DEFINITION
            directive @parent on FIELD_DEFINITION
            scalar Long

            type Query {
                user(id: ID!): User
            }

            type Mutation {
                createUser(name: String!): User
                namespace: MutationNamespace
            }

            type ContextualUser {
                user: User
                hasActiveHomeReservationWith(otherUserId: Long!): Boolean
            }

            type User {
                id: ID!
                name: String
                tenantLocalName: String @tenantLocal
                friend: User
                hasActiveHomeReservationWith(otherUserId: Long!): Boolean
                parent: Company @parent
            }

            interface Organization {
                companyName: String
            }

            type Company implements Organization {
                id: ID!
                companyName: String
            }

            type County {
                code: String
            }

            union Place = County

            type Photo {
                id: ID!
                url: String
            }

            type MutationNamespace @namespaceType {
                createUser(name: String!): User
            }
        """.trimIndent()

        private val OTHER_SCHEMA_SDL = """
            extend type User {
                tenantLocalFromOther: String @tenantLocal
                publicFromOther: String
            }
        """.trimIndent()

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
    }

    /** Runs [RequiredSelectionSetValidator.validate] with the same string for the entry and expanded forms. */
    private fun validate(
        selections: String,
        typeName: String,
        isQuery: Boolean = false,
        expandedSelections: String = selections,
        field: ResolverParams.Field = field(),
    ): List<String> =
        mutableListOf<String>().also { errors ->
            validator().validate(
                normalizedSelections = selections,
                expandedSelections = expandedSelections,
                typeName = typeName,
                isQuery = isQuery,
                field = field,
                errors = errors,
            )
        }

    private fun validator(): RequiredSelectionSetValidator {
        val schemaFiles = listOf(
            schemaFile("build/viaduct/centralSchema/partition/feature/graphql/schema.graphqls", FEATURE_SCHEMA_SDL),
            schemaFile("build/viaduct/centralSchema/partition/other/graphql/schema.graphqls", OTHER_SCHEMA_SDL),
        )
        val typeDefinitionRegistry = schemaRegistry(schemaFiles)
        return RequiredSelectionSetValidator(
            tenantCompilationSchema = UnExecutableSchemaGenerator.makeUnExecutableSchema(typeDefinitionRegistry),
            currentTenantModule = "feature",
            tenantCompilationViaductSchema = ViaductSchema.fromTypeDefinitionRegistry(schemaFiles),
        )
    }

    private fun schemaRegistry(schemaFiles: List<File>): TypeDefinitionRegistry =
        TypeDefinitionRegistry().also { registry ->
            schemaFiles.forEach { registry.merge(SchemaParser().parse(it)) }
        }

    private fun schemaFile(
        relativePath: String,
        sdl: String,
    ): File =
        File(tempDir, relativePath).also { file ->
            file.parentFile.mkdirs()
            file.writeText(sdl)
        }

    @Test
    fun `valid object selection set on parent type passes`() {
        val errors = validate("fragment Main on User { id name }", typeName = "User")
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    @Test
    fun `tenant-local field from same tenant in object selection set passes`() {
        val errors = validate("fragment Main on User { tenantLocalName }", typeName = "User")
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    @Test
    fun `public field from another tenant in object selection set passes`() {
        val errors = validate("fragment Main on User { publicFromOther }", typeName = "User")
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    @Test
    fun `tenant-local field from another tenant in object selection set fails`() {
        val errors = validate("fragment Main on User { tenantLocalFromOther }", typeName = "User")
        assertTrue(errors.any { it.contains("User.tenantLocalFromOther") }, errors.toString())
        assertTrue(errors.any { it.contains("owned by other") }, errors.toString())
        assertTrue(errors.any { it.contains("from tenant module feature") }, errors.toString())
    }

    @Test
    fun `valid query selection set on root query type passes`() {
        val errors = validate("fragment Main on Query { user(id: \"1\") { id } }", typeName = "Query", isQuery = true)
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    @Test
    fun `tenant-local field from another tenant in query selection set fails`() {
        val errors = validate(
            "fragment Main on Query { user(id: \"1\") { tenantLocalFromOther } }",
            typeName = "Query",
            isQuery = true,
            field = field(typeName = "Query", fieldName = "user"),
        )
        assertTrue(errors.any { it.contains("User.tenantLocalFromOther") }, errors.toString())
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
    fun `tenant-local field ownership validation ignores unused fragments`() {
        val errors = validate(
            selections = "fragment Main on User { id }",
            typeName = "User",
            expandedSelections = "fragment Main on User { id }\nfragment Extra on User { tenantLocalFromOther }",
        )
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    @Test
    fun `tenant-local field ownership validation does not coerce required arguments supplied by resolver variables`() {
        // Mirrors ContextualUser RSS where GraphQL Java traversal used to coerce
        // $otherUserId with an empty variable map during build-time validation.
        val errors = validate(
            """
            fragment Main on ContextualUser {
                user {
                    hasActiveHomeReservationWith(otherUserId: ${'$'}otherUserId)
                }
            }
            """.trimIndent(),
            typeName = "ContextualUser",
            field = field(typeName = "ContextualUser", fieldName = "hasActiveHomeReservationWith"),
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

    @Test
    fun `tenant-local field ownership validation runs against the expanded selections`() {
        val errors = validate(
            selections = "fragment Main on User { ...UserFields }",
            typeName = "User",
            expandedSelections = "fragment Main on User { ...UserFields }\nfragment UserFields on User { tenantLocalFromOther }",
        )
        assertTrue(errors.any { it.contains("User.tenantLocalFromOther") }, errors.toString())
    }

    @Test
    fun `module name is extracted from Bazel and Gradle source names`() {
        assertEquals(
            "foo/bar",
            moduleNameFromSourceName("/workspace/projects/viaduct/modules/foo/bar/schema/src/main/resources/graphql/schema.graphqls"),
        )
        assertEquals(
            "foo/bar",
            moduleNameFromSourceName("/workspace/build/viaduct/centralSchema/partition/foo.bar/graphql/schema.graphqls"),
        )
        assertEquals(
            "foo/bar",
            moduleNameFromSourceName("/workspace/build/viaduct/schemaPartition/foo/bar/graphql/schema.graphqls"),
        )
    }

    @Test
    fun `parent field selections pass schema validation`() {
        val errors = validate(
            """
            fragment Main on User {
                id
                parent {
                    companyName
                }
            }
            """.trimIndent(),
            typeName = "User",
        )

        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    @Test
    fun `parent field selections still validate parent fields`() {
        val errors = validate(
            """
            fragment Main on User {
                id
                parent {
                    notACompanyField
                }
            }
            """.trimIndent(),
            typeName = "User",
        )

        assertTrue(errors.any { it.contains("notACompanyField") }, errors.toString())
    }

    @Test
    fun `ordinary fragment with arbitrary type condition still fails schema validation`() {
        val errors = validate(
            """
            fragment Main on User {
                id
                ...CompanyFields
            }
            fragment CompanyFields on Company {
                companyName
            }
            """.trimIndent(),
            typeName = "User",
        )

        assertTrue(errors.any { it.contains("CompanyFields") }, errors.toString())
    }
}
