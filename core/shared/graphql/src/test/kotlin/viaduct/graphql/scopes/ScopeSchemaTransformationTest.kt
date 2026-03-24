@file:Suppress("UnstableApiUsage")

package viaduct.graphql.scopes

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.graphql.scopes.errors.DirectiveRetainedTypeScopeError

class ScopeSchemaTransformationTest : SchemaScopeTestBase() {
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
                allScopes,
                listOf()
            )
        val allScopedSchema = scopedSchemaBuilder.applyScopes(allScopes)
        assertSchemaEqualToFixture("/scopes/simple/test-scope__all.graphqls", allScopedSchema.filtered)
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
                allScopes,
                listOf()
            )
        val allScopedSchema = scopedSchemaBuilder.applyScopes(allScopes)
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
                sortedSetOf(
                    // valid scopes for our test
                    "test-scope:public",
                    "test-scope:data",
                    "test-scope:private",
                    "some-other-scope"
                ),
                listOf()
            )
        val testScopeAllSchema =
            scopedSchemaBuilder.applyScopes(
                setOf("test-scope:public", "test-scope:data", "test-scope:private")
            )
        assertSchemaEqualToFixture("/scopes/simple/test-scope__some.graphqls", testScopeAllSchema.filtered)
        val testScopeDataSchema = scopedSchemaBuilder.applyScopes(setOf("test-scope:data"))
        assertSchemaEqualToFixture("/scopes/simple/test-scope__data.graphqls", testScopeDataSchema.filtered)
        val testScopePublicSchema = scopedSchemaBuilder.applyScopes(setOf("test-scope:public"))
        assertSchemaEqualToFixture("/scopes/simple/test-scope__public.graphqls", testScopePublicSchema.filtered)
    }

    @Test
    fun `ensure types only connected by an interface are transformed`() {
        var sourceSchema = readSchema("/scopes/interface-connection/source.graphqls")
        val scopedSchemaBuilder =
            ScopedSchemaBuilder(
                sourceSchema,
                sortedSetOf(
                    // valid scopes for our test
                    "test-scope:public",
                    "test-scope:data",
                    "test-scope:private",
                    "some-other-scope"
                ),
                listOf()
            )
        val scopedSchema =
            scopedSchemaBuilder.applyScopes(
                setOf("test-scope:public", "test-scope:data")
            )
        assertSchemaEqualToFixture("/scopes/interface-connection/expected.graphqls", scopedSchema.filtered)
    }

    @Test
    fun `properly filters fields that don't exist in scope from types`() {
        val sourceSchema = readSchema("/scopes/filter-fields/source.graphqls")
        val scopedSchemaBuilder =
            ScopedSchemaBuilder(
                sourceSchema,
                sortedSetOf(
                    // valid scopes for our test
                    "test-scope:public",
                    "test-scope:data",
                    "test-scope:private",
                    "some-other-scope"
                ),
                listOf()
            )
        val testScopePublicSchema = scopedSchemaBuilder.applyScopes(setOf("test-scope:public"))
        assertSchemaEqualToFixture("/scopes/filter-fields/test-scope__public.graphqls", testScopePublicSchema.filtered)
        val someOtherScopeSchema = scopedSchemaBuilder.applyScopes(setOf("some-other-scope"))
        assertSchemaEqualToFixture("/scopes/filter-fields/some-other-scope.graphqls", someOtherScopeSchema.filtered)
    }

    @Test
    fun `allows referencing a type when that type's scope is a superset`() {
        val sourceSchema = readSchema("/scopes/superset-scopes/source.graphqls")
        val scopedSchemaBuilder =
            ScopedSchemaBuilder(
                sourceSchema,
                sortedSetOf(
                    // valid scopes for our test
                    "test-scope:public",
                    "test-scope:data",
                    "test-scope:private",
                    "some-other-scope"
                ),
                listOf()
            )
        val scopedSchema = scopedSchemaBuilder.applyScopes(setOf("some-other-scope"))
        assertSchemaEqualToFixture("/scopes/superset-scopes/expected.graphqls", scopedSchema.filtered)
    }

    @Test
    fun `does not cull scoped types retained by a directive`() {
        val sourceSchema = readSchema("/scopes/directive-retained-types/source.graphqls")
        val scopedSchemaBuilder =
            ScopedSchemaBuilder(
                sourceSchema,
                sortedSetOf("test-scope", "other-scope"),
                listOf()
            )

        assertThrows<DirectiveRetainedTypeScopeError> {
            scopedSchemaBuilder.applyScopes(setOf("other-scope"))
        }
    }
}
