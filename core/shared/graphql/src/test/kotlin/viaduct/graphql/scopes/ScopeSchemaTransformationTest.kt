@file:Suppress("UnstableApiUsage")

package viaduct.graphql.scopes

import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.graphql.scopes.errors.DirectiveRetainedTypeScopeError
import viaduct.graphql.scopes.errors.SchemaScopeValidationError
import viaduct.graphql.scopes.utils.ScopeDirectiveParser
import viaduct.graphql.utils.DefaultSchemaFactory

class ScopeSchemaTransformationTest : SchemaScopeTestBase() {
    @Test
    fun `full view preserves an unscoped schema without validation`() {
        val schema = schemaFromSdl(
            """
            type Query {
                publicField: String
                tenantOnly: String @tenantLocal
            }
            """.trimIndent()
        )

        val unscopedSchema = ScopedSchemaBuilder(schema, SchemaScopingMode.Unscoped, listOf())
            .build(SchemaView.Full)
            .filtered

        assertNotNull(unscopedSchema.queryType.getFieldDefinition("publicField"))
        assertNotNull(unscopedSchema.queryType.getFieldDefinition("tenantOnly"))
    }

    @Test
    fun `full view retains bypassPolicyCheck while base and scoped views hide it`() {
        val schema = schemaFromSdl(
            """
            directive @bypassPolicyCheck on FIELD

            type Query @scope(to: ["public"]) {
                publicField: String
            }
            """.trimIndent()
        )
        val builder = ScopedSchemaBuilder(
            schema,
            SchemaScopingMode.ScopeAware(setOf("public")),
            listOf(),
        )

        val fullSchema = builder.build(SchemaView.Full).filtered
        val baseSchema = builder.build(SchemaView.Base).filtered
        val scopedSchema = builder.build(SchemaView.Scoped(setOf("public"))).filtered

        assertNotNull(fullSchema.directives.find { it.name == "bypassPolicyCheck" })
        assertNull(baseSchema.directives.find { it.name == "bypassPolicyCheck" })
        assertNull(scopedSchema.directives.find { it.name == "bypassPolicyCheck" })
    }

    @Test
    fun `doesnt transform full schema`() {
        val sourceSchema = readSchema("/scopes/simple/source.graphqls")
        val allScopes =
            sortedSetOf(
                // valid scopes for our test
                "test-scope:public",
                "test-scope:data",
                "test-scope:private",
                "some-other-scope"
            )
        val scopedSchemaBuilder =
            ScopedSchemaBuilder(
                sourceSchema,
                SchemaScopingMode.ScopeAware(allScopes),
                listOf()
            )
        val allScopedSchema = scopedSchemaBuilder.build(SchemaView.Full)
        assertSchemaEqualToFixture("/scopes/simple/test-scope__all.graphqls", allScopedSchema.filtered)
    }

    @Test
    fun `tenant-local field filter removes tenant-local fields without requiring scope projection`() {
        val schema = schemaFromSdl(
            """
            type Query {
                publicField: String
                internalOnly: String @tenantLocal
            }
            """.trimIndent()
        )

        val filteredSchema = ScopedSchemaBuilder(schema, SchemaScopingMode.Unscoped, listOf())
            .build(SchemaView.Base)
            .filtered

        assertNotNull(filteredSchema.queryType.getFieldDefinition("publicField"))
        assertNull(filteredSchema.queryType.getFieldDefinition("internalOnly"))
    }

    @Test
    fun `tenant-local field filter removes backing-data fields without requiring scope projection`() {
        val schema = schemaFromSdl(
            """
            type Query {
                publicField: String
                backingData: BackingData @backingData(class: "com.airbnb.TestBackingData")
            }
            """.trimIndent()
        )

        val filteredSchema = ScopedSchemaBuilder(schema, SchemaScopingMode.Unscoped, listOf())
            .build(SchemaView.Base)
            .filtered

        assertNotNull(filteredSchema.queryType.getFieldDefinition("publicField"))
        assertNull(filteredSchema.queryType.getFieldDefinition("backingData"))
    }

    @Test
    fun `parent fields are hidden from base and scoped schemas`() {
        val schema = schemaFromSdl(
            """
            type Query @scope(to: ["public"]) {
                user: User
            }

            type User @scope(to: ["public"]) {
                name: String
                parent: Company @parent
            }

            type Company @scope(to: ["public"]) {
                name: String
            }
            """.trimIndent()
        )
        val scopedSchemaBuilder = ScopedSchemaBuilder(
            schema,
            SchemaScopingMode.ScopeAware(setOf("public")),
            listOf(),
        )

        val baseUser = scopedSchemaBuilder.build(SchemaView.Base).filtered.getObjectType("User")
        val scopedUser = scopedSchemaBuilder.build(SchemaView.Scoped(setOf("public"))).filtered.getObjectType("User")
        val metadata = ScopeDirectiveParser(setOf("public")).metadataForElement(schema.getObjectType("User"))

        assertNotNull(baseUser.getFieldDefinition("name"))
        assertNull(baseUser.getFieldDefinition("parent"))
        assertNotNull(scopedUser.getFieldDefinition("name"))
        assertNull(scopedUser.getFieldDefinition("parent"))
        assertEquals(listOf("name"), metadata!!.elementsForScopes["public"]!!.map { it.name })
    }

    @Test
    fun `tenant-local field filter removes types emptied by tenant-local fields`() {
        val schema = schemaFromSdl(
            """
            type Query {
                publicField: String
                internalOnly: InternalOnly @tenantLocal
            }

            type InternalOnly {
                value: String @tenantLocal
            }
            """.trimIndent()
        )

        val filteredSchema = ScopedSchemaBuilder(schema, SchemaScopingMode.Unscoped, listOf())
            .build(SchemaView.Base)
            .filtered

        assertNotNull(filteredSchema.queryType.getFieldDefinition("publicField"))
        assertNull(filteredSchema.queryType.getFieldDefinition("internalOnly"))
        assertNull(filteredSchema.getType("InternalOnly"))
    }

    @Test
    fun `semantic views distinguish full base and scoped schemas`() {
        val schema = schemaFromSdl(
            """
            type Query @scope(to: ["public", "internal"]) {
                shared: String
                tenantOnly: String @tenantLocal
            }

            extend type Query @scope(to: ["internal"]) {
                internal: String
            }
            """.trimIndent()
        )
        val builder = ScopedSchemaBuilder(
            schema,
            SchemaScopingMode.ScopeAware(setOf("public", "internal")),
            listOf(),
        )

        val fullSchema = builder.build(SchemaView.Full).filtered
        val baseSchema = builder.build(SchemaView.Base).filtered
        val publicSchema = builder.build(SchemaView.Scoped(setOf("public"))).filtered
        val allScopesSchema = builder.build(SchemaView.Scoped(setOf("public", "internal"))).filtered

        assertNotNull(fullSchema.queryType.getFieldDefinition("tenantOnly"))
        assertNotNull(fullSchema.queryType.getFieldDefinition("internal"))
        assertNull(baseSchema.queryType.getFieldDefinition("tenantOnly"))
        assertNotNull(baseSchema.queryType.getFieldDefinition("internal"))
        assertNull(publicSchema.queryType.getFieldDefinition("tenantOnly"))
        assertNull(publicSchema.queryType.getFieldDefinition("internal"))
        assertNotNull(publicSchema.queryType.getFieldDefinition("shared"))
        assertNull(allScopesSchema.queryType.getFieldDefinition("tenantOnly"))
        assertNotNull(allScopesSchema.queryType.getFieldDefinition("internal"))
    }

    @Test
    fun `scoped views require scope-aware schemas and known non-empty scopes`() {
        assertThrows<IllegalArgumentException> {
            SchemaView.Scoped(emptySet())
        }
        assertThrows<IllegalArgumentException> {
            SchemaScopingMode.ScopeAware(emptySet())
        }
        assertThrows<IllegalArgumentException> {
            SchemaScopingMode.ScopeAware(setOf("*"))
        }

        val schema = schemaFromSdl(
            """
            type Query {
                publicField: String
            }
            """.trimIndent()
        )
        assertThrows<IllegalArgumentException> {
            ScopedSchemaBuilder(schema, SchemaScopingMode.Unscoped, listOf())
                .build(SchemaView.Scoped(setOf("public")))
        }

        val scopeAwareBuilder = ScopedSchemaBuilder(
            schema,
            SchemaScopingMode.ScopeAware(setOf("public")),
            listOf(),
        )
        assertThrows<IllegalArgumentException> {
            scopeAwareBuilder.build(SchemaView.Scoped(setOf("unknown")))
        }
    }

    @Test
    fun `scope metadata excludes tenant-local fields`() {
        val schema = schemaFromSdl(
            """
            type Query @scope(to: ["public"]) {
                publicField: String
                internalOnly: String @tenantLocal
            }
            """.trimIndent()
        )

        val metadata = ScopeDirectiveParser(setOf("public")).metadataForElement(schema.queryType)

        assertEquals(
            listOf("publicField"),
            metadata!!.elementsForScopes["public"]!!.map { it.name }
        )
    }

    @Test
    fun `scope metadata excludes backing-data fields`() {
        val schema = schemaFromSdl(
            """
            type Query @scope(to: ["public"]) {
                publicField: String
                backingData: BackingData @backingData(class: "com.airbnb.TestBackingData")
            }
            """.trimIndent()
        )

        val metadata = ScopeDirectiveParser(setOf("public")).metadataForElement(schema.queryType)

        assertEquals(
            listOf("publicField"),
            metadata!!.elementsForScopes["public"]!!.map { it.name }
        )
    }

    @Test
    fun `tenant-local-only extensions do not require scope directives`() {
        val schema = schemaFromSdl(
            """
            type Query @scope(to: ["public"]) {
                publicField: String
            }

            extend type Query {
                internalOnly: String @tenantLocal
            }
            """.trimIndent()
        )

        val filteredSchema = ScopedSchemaBuilder(
            schema,
            SchemaScopingMode.ScopeAware(setOf("public")),
            listOf(),
        ).build(SchemaView.Scoped(setOf("public")))
            .filtered

        assertNotNull(filteredSchema.queryType.getFieldDefinition("publicField"))
        assertNull(filteredSchema.queryType.getFieldDefinition("internalOnly"))
    }

    @Test
    fun `backing-data-only extensions do not require scope directives`() {
        val schema = schemaFromSdl(
            """
            type Query @scope(to: ["public"]) {
                publicField: String
            }

            extend type Query {
                backingData: BackingData @backingData(class: "com.airbnb.TestBackingData")
            }
            """.trimIndent()
        )

        val filteredSchema = ScopedSchemaBuilder(
            schema,
            SchemaScopingMode.ScopeAware(setOf("public")),
            listOf(),
        )
            .build(SchemaView.Scoped(setOf("public")))
            .filtered

        assertNotNull(filteredSchema.queryType.getFieldDefinition("publicField"))
        assertNull(filteredSchema.queryType.getFieldDefinition("backingData"))
    }

    @Test
    fun `parent-only extensions do not require scope directives`() {
        val schema = schemaFromSdl(
            """
            type Query @scope(to: ["public"]) {
                publicField: String
            }

            type Parent @scope(to: ["public"]) {
                value: String
            }

            extend type Query {
                parent: Parent @parent
            }
            """.trimIndent()
        )

        val filteredSchema = ScopedSchemaBuilder(
            schema,
            SchemaScopingMode.ScopeAware(setOf("public")),
            listOf(),
        )
            .build(SchemaView.Scoped(setOf("public")))
            .filtered

        assertNotNull(filteredSchema.queryType.getFieldDefinition("publicField"))
        assertNull(filteredSchema.queryType.getFieldDefinition("parent"))
    }

    @Test
    fun `mixed unscoped extensions still require scope directives when they contain backing-data fields`() {
        val schema = schemaFromSdl(
            """
            type Query @scope(to: ["public"]) {
                publicField: String
            }

            extend type Query {
                unscopedPublic: String
                backingData: BackingData @backingData(class: "com.airbnb.TestBackingData")
            }
            """.trimIndent()
        )

        val error = assertThrows<SchemaScopeValidationError> {
            ScopedSchemaBuilder(
                schema,
                SchemaScopingMode.ScopeAware(setOf("public")),
                listOf(),
            )
                .build(SchemaView.Scoped(setOf("public")))
        }

        assertEquals(true, error.message!!.contains("No scope directives found"))
    }

    @Test
    fun `includes skip and include directives for full schema`() {
        val sourceSchema = readSchema("/scopes/simple/source.graphqls")
        val allScopes =
            sortedSetOf(
                // valid scopes for our test
                "test-scope:public",
                "test-scope:data",
                "test-scope:private",
                "some-other-scope"
            )
        val scopedSchemaBuilder =
            ScopedSchemaBuilder(
                sourceSchema,
                SchemaScopingMode.ScopeAware(allScopes),
                listOf()
            )
        val allScopedSchema = scopedSchemaBuilder.build(SchemaView.Full)
        assertSchemaEqualToFixture(
            "/scopes/simple/test-scope-with-directives__all.graphqls",
            allScopedSchema.filtered,
            includeScopeDirectives = true,
            includeDirectiveDefinitions = true
        )
    }

    @Test
    fun `applies schema scope properly to simple type`() {
        val sourceSchema = readSchema("/scopes/simple/source.graphqls")
        val scopedSchemaBuilder =
            ScopedSchemaBuilder(
                sourceSchema,
                SchemaScopingMode.ScopeAware(
                    sortedSetOf(
                        // valid scopes for our test
                        "test-scope:public",
                        "test-scope:data",
                        "test-scope:private",
                        "some-other-scope"
                    )
                ),
                listOf()
            )
        val testScopeAllSchema =
            scopedSchemaBuilder.build(
                SchemaView.Scoped(setOf("test-scope:public", "test-scope:data", "test-scope:private"))
            )
        assertSchemaEqualToFixture("/scopes/simple/test-scope__some.graphqls", testScopeAllSchema.filtered)
        val testScopeDataSchema = scopedSchemaBuilder.build(SchemaView.Scoped(setOf("test-scope:data")))
        assertSchemaEqualToFixture("/scopes/simple/test-scope__data.graphqls", testScopeDataSchema.filtered)
        val testScopePublicSchema = scopedSchemaBuilder.build(SchemaView.Scoped(setOf("test-scope:public")))
        assertSchemaEqualToFixture("/scopes/simple/test-scope__public.graphqls", testScopePublicSchema.filtered)
    }

    @Test
    fun `ensure types only connected by an interface are transformed`() {
        var sourceSchema = readSchema("/scopes/interface-connection/source.graphqls")
        val scopedSchemaBuilder =
            ScopedSchemaBuilder(
                sourceSchema,
                SchemaScopingMode.ScopeAware(
                    sortedSetOf(
                        // valid scopes for our test
                        "test-scope:public",
                        "test-scope:data",
                        "test-scope:private",
                        "some-other-scope"
                    )
                ),
                listOf()
            )
        val scopedSchema =
            scopedSchemaBuilder.build(
                SchemaView.Scoped(setOf("test-scope:public", "test-scope:data"))
            )
        assertSchemaEqualToFixture("/scopes/interface-connection/expected.graphqls", scopedSchema.filtered)
    }

    @Test
    fun `properly filters fields that don't exist in scope from types`() {
        val sourceSchema = readSchema("/scopes/filter-fields/source.graphqls")
        val scopedSchemaBuilder =
            ScopedSchemaBuilder(
                sourceSchema,
                SchemaScopingMode.ScopeAware(
                    sortedSetOf(
                        // valid scopes for our test
                        "test-scope:public",
                        "test-scope:data",
                        "test-scope:private",
                        "some-other-scope"
                    )
                ),
                listOf()
            )
        val testScopePublicSchema = scopedSchemaBuilder.build(SchemaView.Scoped(setOf("test-scope:public")))
        assertSchemaEqualToFixture("/scopes/filter-fields/test-scope__public.graphqls", testScopePublicSchema.filtered)
        val someOtherScopeSchema = scopedSchemaBuilder.build(SchemaView.Scoped(setOf("some-other-scope")))
        assertSchemaEqualToFixture("/scopes/filter-fields/some-other-scope.graphqls", someOtherScopeSchema.filtered)
    }

    @Test
    fun `filters interface implementations declared outside the applied scope`() {
        val schema = schemaFromSdl(
            """
            type Query @scope(to: ["public", "private"]) {
                thing: Thing
            }

            interface VanityCodeProviding @scope(to: ["public", "private"]) {
                vanityCode: VanityCode
            }

            type VanityCode @scope(to: ["public", "private"]) {
                value: String
            }

            type Thing @scope(to: ["public", "private"]) {
                id: ID!
            }

            extend type Thing implements VanityCodeProviding @scope(to: ["private"]) {
                vanityCode: VanityCode
            }
            """.trimIndent()
        )

        val scopedSchemaBuilder = ScopedSchemaBuilder(
            schema,
            SchemaScopingMode.ScopeAware(setOf("public", "private")),
            listOf(),
        )
        val publicThing = scopedSchemaBuilder
            .build(SchemaView.Scoped(setOf("public")))
            .filtered
            .getObjectType("Thing")
        val privateThing = scopedSchemaBuilder
            .build(SchemaView.Scoped(setOf("private")))
            .filtered
            .getObjectType("Thing")

        assertNull(publicThing.getFieldDefinition("vanityCode"))
        assertEquals(emptyList<String>(), publicThing.interfaces.map { it.name })
        assertNotNull(privateThing.getFieldDefinition("vanityCode"))
        assertEquals(listOf("VanityCodeProviding"), privateThing.interfaces.map { it.name })
    }

    @Test
    fun `keeps interface implementations when interface extensions are filtered by scope`() {
        val schema = schemaFromSdl(
            """
            type Query @scope(to: ["public", "private"]) {
                review: StayReview
            }

            interface Review @scope(to: ["public", "private"]) {
                id: ID!
            }

            extend interface Review @scope(to: ["private"]) {
                moderationData: String
            }

            type StayReview implements Review @scope(to: ["public", "private"]) {
                id: ID!
            }

            extend type StayReview @scope(to: ["private"]) {
                moderationData: String
            }
            """.trimIndent()
        )

        val scopedSchemaBuilder = ScopedSchemaBuilder(
            schema,
            SchemaScopingMode.ScopeAware(setOf("public", "private")),
            listOf(),
        )
        val publicSchema = scopedSchemaBuilder.build(SchemaView.Scoped(setOf("public"))).filtered
        val privateSchema = scopedSchemaBuilder.build(SchemaView.Scoped(setOf("private"))).filtered
        val publicReview = publicSchema.getObjectType("StayReview")
        val privateReview = privateSchema.getObjectType("StayReview")

        assertEquals(listOf("Review"), publicReview.interfaces.map { it.name })
        assertNull(publicReview.getFieldDefinition("moderationData"))
        assertEquals(listOf("Review"), privateReview.interfaces.map { it.name })
        assertNotNull(privateReview.getFieldDefinition("moderationData"))
    }

    @Test
    fun `keeps implemented interfaces when scoped fields narrow interface field types`() {
        val schema = schemaFromSdl(
            """
            type Query @scope(to: ["public"]) {
                phoneNumbers: UserPhoneNumberConnection
            }

            interface ConnectionEdge @scope(to: ["public"]) {
                cursor: String!
                node: UserPhoneNumber
            }

            interface PagedConnection @scope(to: ["public"]) {
                pageInfo: PageInfo!
                edges: [ConnectionEdge]
            }

            type UserPhoneNumber @scope(to: ["public"]) {
                id: ID!
            }

            type UserPhoneNumberEdge implements ConnectionEdge @scope(to: ["public"]) {
                cursor: String!
                node: UserPhoneNumber
            }

            type UserPhoneNumberConnection implements PagedConnection @scope(to: ["public"]) {
                pageInfo: PageInfo!
                edges: [UserPhoneNumberEdge]
            }
            """.trimIndent()
        )

        val scopedSchema = ScopedSchemaBuilder(
            schema,
            SchemaScopingMode.ScopeAware(setOf("public")),
            listOf(),
        )
            .build(SchemaView.Scoped(setOf("public")))
            .filtered

        assertEquals(
            listOf("PagedConnection"),
            scopedSchema.getObjectType("UserPhoneNumberConnection").interfaces.map { it.name }
        )
        assertEquals(
            listOf("ConnectionEdge"),
            scopedSchema.getObjectType("UserPhoneNumberEdge").interfaces.map { it.name }
        )
    }

    @Test
    fun `allows referencing a type when that type's scope is a superset`() {
        val sourceSchema = readSchema("/scopes/superset-scopes/source.graphqls")
        val scopedSchemaBuilder =
            ScopedSchemaBuilder(
                sourceSchema,
                SchemaScopingMode.ScopeAware(
                    sortedSetOf(
                        // valid scopes for our test
                        "test-scope:public",
                        "test-scope:data",
                        "test-scope:private",
                        "some-other-scope"
                    )
                ),
                listOf()
            )
        val scopedSchema = scopedSchemaBuilder.build(SchemaView.Scoped(setOf("some-other-scope")))
        assertSchemaEqualToFixture("/scopes/superset-scopes/expected.graphqls", scopedSchema.filtered)
    }

    @Test
    fun `does not cull scoped types retained by a directive`() {
        val sourceSchema = readSchema("/scopes/directive-retained-types/source.graphqls")
        val scopedSchemaBuilder =
            ScopedSchemaBuilder(
                sourceSchema,
                SchemaScopingMode.ScopeAware(setOf("test-scope", "other-scope")),
                listOf()
            )

        assertThrows<DirectiveRetainedTypeScopeError> {
            scopedSchemaBuilder.build(SchemaView.Scoped(setOf("other-scope")))
        }
    }

    private fun schemaFromSdl(sdl: String) =
        UnExecutableSchemaGenerator.makeUnExecutableSchema(
            SchemaParser().parse(sdl).apply {
                DefaultSchemaFactory.addDefaults(this, allowExisting = true)
            }
        )
}
