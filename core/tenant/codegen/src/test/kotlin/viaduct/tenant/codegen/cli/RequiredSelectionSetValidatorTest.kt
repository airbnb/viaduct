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
            directive @tombstoned on FIELD_DEFINITION | OBJECT
            directive @parent on FIELD_DEFINITION
            directive @tag(name: String) on FIELD | INLINE_FRAGMENT | FRAGMENT_SPREAD | FRAGMENT_DEFINITION
            scalar Long

            type Query {
                user(id: ID!): User
                holding: Holding
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
                tombstonedName: String @tombstoned @deprecated(reason: "Use name instead")
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
                logoUrl(size: Int): String
                employees: [Employee]
                parent: Holding @parent
            }

            type Holding {
                id: ID!
                name(locale: String): String
                companies: [Company]
            }

            type Employee {
                id: ID!
                title: String
                parent: Company @parent
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
        forbiddenSelectionDirectives: Set<String> = emptySet(),
        currentTenantModule: String? = "feature",
    ): List<String> =
        mutableListOf<String>().also { errors ->
            validator(forbiddenSelectionDirectives, currentTenantModule).validate(
                normalizedSelections = selections,
                expandedSelections = expandedSelections,
                typeName = typeName,
                isQuery = isQuery,
                field = field,
                errors = errors,
            )
        }

    private fun validateParentSelections(
        selections: String,
        typeName: String = "Employee",
        isQuery: Boolean = false,
        expandedSelections: String = selections,
        currentTenantModule: String? = "feature",
    ): List<String> =
        validate(
            selections = selections,
            typeName = typeName,
            isQuery = isQuery,
            expandedSelections = expandedSelections,
            field = field(typeName = "Employee", fieldName = "title"),
            currentTenantModule = currentTenantModule,
        )

    private fun parentFieldVariableError(
        variables: String,
        parentFieldCoordinate: String,
    ): String =
        "Required selection set for com.example.TestResolver (Employee.title) uses $variables beneath " +
            "@parent field $parentFieldCoordinate. Required selection sets may not use variables beneath a @parent field."

    private fun parentFieldDirectiveError(
        directives: String,
        parentFieldCoordinate: String,
    ): String =
        "Required selection set for com.example.TestResolver (Employee.title) applies $directives to " +
            "@parent field $parentFieldCoordinate. Directives may not be applied to a @parent field or to inline fragments " +
            "or fragment spreads that directly contain it."

    private fun validator(
        forbiddenSelectionDirectives: Set<String> = emptySet(),
        currentTenantModule: String? = "feature",
    ): RequiredSelectionSetValidator {
        val schemaFiles = listOf(
            schemaFile("build/viaduct/centralSchema/partition/feature/graphql/schema.graphqls", FEATURE_SCHEMA_SDL),
            schemaFile("build/viaduct/centralSchema/partition/other/graphql/schema.graphqls", OTHER_SCHEMA_SDL),
        )
        val typeDefinitionRegistry = schemaRegistry(schemaFiles)
        return RequiredSelectionSetValidator(
            tenantCompilationSchema = UnExecutableSchemaGenerator.makeUnExecutableSchema(typeDefinitionRegistry),
            currentTenantModule = currentTenantModule,
            tenantCompilationViaductSchema = currentTenantModule?.let { ViaductSchema.fromTypeDefinitionRegistry(schemaFiles) },
            forbiddenSelectionDirectives = forbiddenSelectionDirectives,
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
    fun `selection of a field carrying a forbidden directive fails`() {
        val errors = validate(
            "fragment Main on User { tombstonedName }",
            typeName = "User",
            forbiddenSelectionDirectives = setOf("tombstoned"),
        )
        assertEquals(
            listOf(
                "Required selection set for com.example.TestResolver (User.name) " +
                    "selects User.tombstonedName, which carries @tombstoned. " +
                    "Fields with this directive must not be selected.",
            ),
            errors,
        )
    }

    @Test
    fun `selection of a field carrying a forbidden directive fails without tenant-local ownership validation`() {
        val errors = validate(
            "fragment Main on User { tombstonedName }",
            typeName = "User",
            forbiddenSelectionDirectives = setOf("tombstoned"),
            currentTenantModule = null,
        )
        assertEquals(
            listOf(
                "Required selection set for com.example.TestResolver (User.name) " +
                    "selects User.tombstonedName, which carries @tombstoned. " +
                    "Fields with this directive must not be selected.",
            ),
            errors,
        )
    }

    @Test
    fun `typename selection is ignored by the forbidden directive check`() {
        val errors = validate(
            "fragment Main on User { __typename friend { __typename id } }",
            typeName = "User",
            forbiddenSelectionDirectives = setOf("tombstoned"),
        )
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    @Test
    fun `selection of a field carrying a forbidden directive passes when none are configured`() {
        val errors = validate("fragment Main on User { tombstonedName }", typeName = "User")
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
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
    fun `mutation resolver with query selection set fails`() {
        val errors = validate(
            "fragment Main on Query { user(id: \"1\") { id } }",
            typeName = "Query",
            isQuery = true,
            field = field(typeName = "Mutation", fieldName = "createUser"),
        )
        assertTrue(errors.any { it.contains("Mutation.createUser") && it.contains("must not set queryValueFragment") }, errors.toString())
    }

    @Test
    fun `namespaced mutation resolver with query selection set fails`() {
        val errors = validate(
            "fragment Main on Query { user(id: \"1\") { id } }",
            typeName = "Query",
            isQuery = true,
            field = field(typeName = "MutationNamespace", fieldName = "createUser"),
        )
        assertTrue(errors.any { it.contains("MutationNamespace.createUser") && it.contains("must not set queryValueFragment") }, errors.toString())
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
    fun `variable in an argument under a parent field fails`() {
        val errors = validateParentSelections(
            """
            fragment Main on Employee {
                parent {
                    logoUrl(size: ${'$'}size)
                }
            }
            """.trimIndent(),
        )

        assertEquals(listOf(parentFieldVariableError("${'$'}size", "Employee.parent")), errors)
    }

    @Test
    fun `variable in a conditional directive on a parent field fails`() {
        val errors = validateParentSelections("fragment Main on Employee { parent @include(if: ${'$'}withParent) { companyName } }")

        assertEquals(listOf(parentFieldDirectiveError("@include", "Employee.parent")), errors)
    }

    @Test
    fun `literal conditional directive on a parent field fails`() {
        val errors = validateParentSelections("fragment Main on Employee { parent @skip(if: false) { companyName } }")

        assertEquals(listOf(parentFieldDirectiveError("@skip", "Employee.parent")), errors)
    }

    @Test
    fun `non-conditional directive on a parent field fails`() {
        val errors = validateParentSelections("fragment Main on Employee { parent @tag(name: \"a\") { companyName } }")

        assertEquals(listOf(parentFieldDirectiveError("@tag", "Employee.parent")), errors)
    }

    @Test
    fun `directive on a parent field nested under another parent field fails`() {
        val errors = validateParentSelections("fragment Main on Employee { parent { parent @include(if: true) { id } } }")

        assertEquals(listOf(parentFieldDirectiveError("@include", "Company.parent")), errors)
    }

    @Test
    fun `variable in a directive on an inline fragment around a parent field fails`() {
        val errors = validateParentSelections("fragment Main on Employee { ... @include(if: ${'$'}withParent) { title parent { companyName } } }")

        assertEquals(listOf(parentFieldDirectiveError("@include", "Employee.parent")), errors)
    }

    @Test
    fun `literal directive on an inline fragment around a parent field fails`() {
        val errors = validateParentSelections("fragment Main on Employee { ... on Employee @tag(name: \"a\") { parent { companyName } } }")

        assertEquals(listOf(parentFieldDirectiveError("@tag", "Employee.parent")), errors)
    }

    @Test
    fun `directive on a fragment definition containing a parent field fails`() {
        val errors = validateParentSelections(
            selections = "fragment Main on Employee { ...EmployeeParent }",
            expandedSelections = """
                fragment Main on Employee { ...EmployeeParent }
                fragment EmployeeParent on Employee @tag(name: "a") { parent { companyName } }
            """.trimIndent(),
        )

        assertEquals(listOf(parentFieldDirectiveError("@tag", "Employee.parent")), errors)
    }

    @Test
    fun `directive on the entry fragment definition around a parent field fails`() {
        val errors = validateParentSelections("fragment Main on Employee @tag(name: \"a\") { parent { companyName } }")

        assertEquals(listOf(parentFieldDirectiveError("@tag", "Employee.parent")), errors)
    }

    @Test
    fun `variable in a directive on a fragment spread around a parent field fails`() {
        val errors = validateParentSelections(
            selections = "fragment Main on Employee { ...EmployeeParent @include(if: ${'$'}withParent) }",
            expandedSelections = """
                fragment Main on Employee { ...EmployeeParent @include(if: ${'$'}withParent) }
                fragment EmployeeParent on Employee { parent { companyName } }
            """.trimIndent(),
        )

        assertEquals(listOf(parentFieldDirectiveError("@include", "Employee.parent")), errors)
    }

    @Test
    fun `variable under a parent field selected inside a fragment spread fails`() {
        val errors = validateParentSelections(
            selections = "fragment Main on Employee { ...EmployeeParent }",
            expandedSelections = """
                fragment Main on Employee { ...EmployeeParent }
                fragment EmployeeParent on Employee { parent { logoUrl(size: ${'$'}size) } }
            """.trimIndent(),
        )

        assertEquals(listOf(parentFieldVariableError("${'$'}size", "Employee.parent")), errors)
    }

    @Test
    fun `variables in a fragment spread under a parent field fail`() {
        val errors = validateParentSelections(
            selections = "fragment Main on Employee { parent { ...CompanyFields } }",
            expandedSelections = """
                fragment Main on Employee { parent { ...CompanyFields } }
                fragment CompanyFields on Company { logoUrl(size: ${'$'}size) companyName @skip(if: ${'$'}skipName) }
            """.trimIndent(),
        )

        assertEquals(listOf(parentFieldVariableError("${'$'}size, ${'$'}skipName", "Employee.parent")), errors)
    }

    @Test
    fun `guarded spread of a shared parent fragment fails after an unguarded spread`() {
        val errors = validateHoldingSpreads(
            first = "...EmployeeParent",
            second = "...EmployeeParent @include(if: ${'$'}flag)",
        )

        assertEquals(listOf(parentFieldDirectiveError("@include", "Employee.parent")), errors)
    }

    @Test
    fun `guarded spread of a shared parent fragment fails before an unguarded spread`() {
        val errors = validateHoldingSpreads(
            first = "...EmployeeParent @include(if: ${'$'}flag)",
            second = "...EmployeeParent",
        )

        assertEquals(listOf(parentFieldDirectiveError("@include", "Employee.parent")), errors)
    }

    @Test
    fun `inline fragment guard around a later spread of a shared parent fragment fails`() {
        val errors = validateHoldingSpreads(
            first = "...EmployeeParent",
            second = "... @include(if: ${'$'}flag) { ...EmployeeParent }",
        )

        assertEquals(listOf(parentFieldDirectiveError("@include", "Employee.parent")), errors)
    }

    @Test
    fun `directives from different spreads of a shared parent fragment are reported together`() {
        val errors = validateHoldingSpreads(
            first = "...EmployeeParent @skip(if: ${'$'}skip)",
            second = "... @include(if: ${'$'}flag) { ...EmployeeParent }",
        )

        assertEquals(listOf(parentFieldDirectiveError("@skip, @include", "Employee.parent")), errors)
    }

    private fun validateHoldingSpreads(
        first: String,
        second: String,
    ): List<String> {
        val main = "fragment Main on Holding { a: companies { employees { $first } } b: companies { employees { $second } } }"
        return validateParentSelections(
            selections = main,
            typeName = "Holding",
            expandedSelections = "$main\nfragment EmployeeParent on Employee { parent { companyName } }",
        )
    }

    @Test
    fun `variable in an inline fragment directive under a parent field fails`() {
        val errors = validateParentSelections("fragment Main on Employee { parent { ... on Company @include(if: ${'$'}withName) { companyName } } }")

        assertEquals(listOf(parentFieldVariableError("${'$'}withName", "Employee.parent")), errors)
    }

    @Test
    fun `variable under a parent field reached through other fields fails`() {
        val errors = validateParentSelections(
            "fragment Main on Holding { companies { employees { parent { logoUrl(size: ${'$'}size) } } } }",
            typeName = "Holding",
        )

        assertEquals(listOf(parentFieldVariableError("${'$'}size", "Employee.parent")), errors)
    }

    @Test
    fun `variable under nested parent fields is reported once at the outer parent field`() {
        val errors = validateParentSelections("fragment Main on Employee { parent { parent { name(locale: ${'$'}locale) } } }")

        assertEquals(listOf(parentFieldVariableError("${'$'}locale", "Employee.parent")), errors)
    }

    @Test
    fun `variable under a parent field in query selections fails`() {
        val errors = validateParentSelections(
            "fragment Main on Query { holding { companies { employees { parent { logoUrl(size: ${'$'}size) } } } } }",
            typeName = "Query",
            isQuery = true,
        )

        assertEquals(listOf(parentFieldVariableError("${'$'}size", "Employee.parent")), errors)
    }

    @Test
    fun `parent field variables are checked without tenant-local ownership validation`() {
        val errors = validateParentSelections(
            "fragment Main on Employee { parent { logoUrl(size: ${'$'}size) } }",
            currentTenantModule = null,
        )

        assertEquals(listOf(parentFieldVariableError("${'$'}size", "Employee.parent")), errors)
    }

    @Test
    fun `variable in a non-conditional directive under a parent field fails`() {
        val errors = validateParentSelections("fragment Main on Employee { parent { companyName @tag(name: ${'$'}name) } }")

        assertEquals(listOf(parentFieldVariableError("${'$'}name", "Employee.parent")), errors)
    }

    @Test
    fun `variable under a statically skipped selection beneath a parent field fails`() {
        val errors = validateParentSelections("fragment Main on Employee { parent { logoUrl(size: ${'$'}size) @skip(if: true) } }")

        assertEquals(listOf(parentFieldVariableError("${'$'}size", "Employee.parent")), errors)
    }

    @Test
    fun `directive and variable violations on one parent field are both reported`() {
        val errors = validateParentSelections("fragment Main on Employee { parent @include(if: true) { logoUrl(size: ${'$'}size) } }")

        assertEquals(
            listOf(
                parentFieldDirectiveError("@include", "Employee.parent"),
                parentFieldVariableError("${'$'}size", "Employee.parent"),
            ),
            errors,
        )
    }

    @Test
    fun `literal directives beneath a parent field pass`() {
        val errors = validateParentSelections("fragment Main on Employee { parent { companyName @skip(if: false) ... @include(if: true) { id } } }")

        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    @Test
    fun `variables beside a parent field pass`() {
        val errors = validateParentSelections(
            """
            fragment Main on Employee {
                title @skip(if: ${'$'}skipTitle)
                ... @include(if: ${'$'}withId) { id }
                parent {
                    logoUrl(size: 64)
                }
            }
            """.trimIndent(),
        )

        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    @Test
    fun `variables on the field directly enclosing a parent field pass`() {
        val errors = validateParentSelections(
            "fragment Main on Company { employees @include(if: ${'$'}withEmployees) { parent { companyName } } }",
            typeName = "Company",
        )

        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    @Test
    fun `variable under a parent field reached through an interface type condition fails`() {
        val errors = validateParentSelections(
            "fragment Main on Organization { ... on Company { parent { name(locale: ${'$'}locale) } } }",
            typeName = "Organization",
        )

        assertEquals(listOf(parentFieldVariableError("${'$'}locale", "Company.parent")), errors)
    }

    @Test
    fun `variables on fields above a parent field pass`() {
        val errors = validateParentSelections(
            "fragment Main on Holding { companies @include(if: ${'$'}withCompanies) { employees { parent { companyName } } } }",
            typeName = "Holding",
        )

        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    @Test
    fun `variables on inline fragments above a parent field's enclosing field pass`() {
        val errors = validateParentSelections(
            """
            fragment Main on Holding {
                ... @include(if: ${'$'}withCompanies) { companies { employees { parent { companyName } } } }
                companies { ... @include(if: ${'$'}withEmployees) { employees { parent { companyName } } } }
            }
            """.trimIndent(),
            typeName = "Holding",
        )

        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    @Test
    fun `variables on fragment spreads above a parent field's enclosing field pass`() {
        val errors = validateParentSelections(
            selections = "fragment Main on Holding { ...HoldingCompanies @include(if: ${'$'}withCompanies) }",
            typeName = "Holding",
            expandedSelections = """
                fragment Main on Holding { ...HoldingCompanies @include(if: ${'$'}withCompanies) }
                fragment HoldingCompanies on Holding { companies { employees { parent { companyName } } } }
            """.trimIndent(),
        )

        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
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
