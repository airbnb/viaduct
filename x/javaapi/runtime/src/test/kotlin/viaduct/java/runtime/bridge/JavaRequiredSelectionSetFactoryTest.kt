@file:Suppress("ForbiddenImport")

package viaduct.java.runtime.bridge

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.RequiredSelectionSets
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.variableNames
import viaduct.java.api.annotations.Resolver
import viaduct.java.api.annotations.Variable

/**
 * Tests for [JavaRequiredSelectionSetFactory] - verifies that the factory correctly
 * parses Java @Resolver annotations and creates RequiredSelectionSets.
 *
 * WHAT THESE TESTS ARE TESTING:
 * - Variable declaration validation (variables require fragments, must have exactly one source, etc.)
 * - Variable binding correctness (variables from arguments/objectFields/queryFields are registered)
 * - Annotation parsing (objectValueFragment, queryValueFragment, variables)
 * - Unused variable detection
 *
 * WHAT THESE TESTS ARE NOT TESTING:
 * - VariablesProvider support (not implemented for Java API)
 * - Actual runtime variable resolution (tested via feature app tests)
 */
class JavaRequiredSelectionSetFactoryTest {
    private val factory = JavaRequiredSelectionSetFactory()

    private val defaultSchema = MockSchema.mk(
        """
        extend type Query {
            foo(x: Int!): Int!
            bar(x: Int!, y: Int!, z: Int!): Int!
            baz: Int!
            testField(arg: String): String
            queryData: String
        }

        type Person {
            name: String!
            age: Int
            address: Address
        }

        type Address {
            street: String!
            city: String!
            country: String
        }
        """.trimIndent()
    )

    // ============================================================================
    // Empty/Basic Annotation Tests
    // ============================================================================

    @Test
    fun `empty annotation returns empty RequiredSelectionSets`() {
        val annotation = createResolver()

        val result = factory.mkRequiredSelectionSets(
            schema = defaultSchema,
            annotation = annotation,
            resolverForType = "Query",
            resolverClassName = "TestResolver"
        )

        assertThat(result).isEqualTo(RequiredSelectionSets.empty())
        assertNull(result.objectSelections)
        assertNull(result.querySelections)
    }

    @Test
    fun `annotation with blank fragments returns empty RequiredSelectionSets`() {
        val annotation = createResolver(
            objectValueFragment = "   ",
            queryValueFragment = "   "
        )

        val result = factory.mkRequiredSelectionSets(
            schema = defaultSchema,
            annotation = annotation,
            resolverForType = "Query",
            resolverClassName = "TestResolver"
        )

        assertThat(result).isEqualTo(RequiredSelectionSets.empty())
    }

    // ============================================================================
    // objectValueFragment Tests
    // ============================================================================

    @Test
    fun `objectValueFragment only - parses correctly`() {
        val annotation = createResolver(
            objectValueFragment = "name age"
        )

        val result = factory.mkRequiredSelectionSets(
            schema = defaultSchema,
            annotation = annotation,
            resolverForType = "Person",
            resolverClassName = "TestResolver"
        )

        assertThat(result.objectSelections).isNotNull
        assertNull(result.querySelections)
        // No variables declared, so variableResolvers should be empty
        assertThat(result.objectSelections?.variablesResolvers).isEmpty()
    }

    @Test
    fun `objectValueFragment with nested selections - parses correctly`() {
        val annotation = createResolver(
            objectValueFragment = "address { street city country }"
        )

        val result = factory.mkRequiredSelectionSets(
            schema = defaultSchema,
            annotation = annotation,
            resolverForType = "Person",
            resolverClassName = "FullAddressResolver"
        )

        assertThat(result.objectSelections).isNotNull
        assertNull(result.querySelections)
    }

    // ============================================================================
    // queryValueFragment Tests
    // ============================================================================

    @Test
    fun `queryValueFragment only - parses correctly`() {
        val annotation = createResolver(
            queryValueFragment = "foo(x: 1)"
        )

        val result = factory.mkRequiredSelectionSets(
            schema = defaultSchema,
            annotation = annotation,
            resolverForType = "Person",
            resolverClassName = "TestResolver"
        )

        assertNull(result.objectSelections)
        assertThat(result.querySelections).isNotNull
    }

    @Test
    fun `both fragments together - parses correctly`() {
        val annotation = createResolver(
            objectValueFragment = "name",
            queryValueFragment = "baz"
        )

        val result = factory.mkRequiredSelectionSets(
            schema = defaultSchema,
            annotation = annotation,
            resolverForType = "Person",
            resolverClassName = "TestResolver"
        )

        assertThat(result.objectSelections).isNotNull
        assertThat(result.querySelections).isNotNull
    }

    // ============================================================================
    // @Variable with fromArgument Tests
    // ============================================================================

    @Test
    fun `Variable with fromArgument - converts correctly`() {
        val annotation = createResolver(
            objectValueFragment = "foo(x: \$argVar)",
            variables = arrayOf(
                createVariable(name = "argVar", fromArgument = "x")
            )
        )

        val result = factory.mkRequiredSelectionSets(
            schema = defaultSchema,
            annotation = annotation,
            resolverForType = "Query",
            resolverClassName = "TestResolver"
        )

        assertThat(result.objectSelections).isNotNull
        assertEquals(setOf("argVar"), result.objectSelections?.variablesResolvers?.variableNames)
    }

    // ============================================================================
    // @Variable with fromObjectField Tests
    // ============================================================================

    @Test
    fun `Variable with fromObjectField - converts correctly`() {
        val annotation = createResolver(
            objectValueFragment = "foo(x: \$objVar) baz",
            variables = arrayOf(
                createVariable(name = "objVar", fromObjectField = "baz")
            )
        )

        val result = factory.mkRequiredSelectionSets(
            schema = defaultSchema,
            annotation = annotation,
            resolverForType = "Query",
            resolverClassName = "TestResolver"
        )

        assertThat(result.objectSelections).isNotNull
        assertEquals(setOf("objVar"), result.objectSelections?.variablesResolvers?.variableNames)
    }

    // ============================================================================
    // @Variable with fromQueryField Tests
    // ============================================================================

    @Test
    fun `Variable with fromQueryField - converts correctly`() {
        val annotation = createResolver(
            objectValueFragment = "foo(x: \$queryVar)",
            queryValueFragment = "queryData",
            variables = arrayOf(
                createVariable(name = "queryVar", fromQueryField = "queryData")
            )
        )

        val result = factory.mkRequiredSelectionSets(
            schema = defaultSchema,
            annotation = annotation,
            resolverForType = "Query",
            resolverClassName = "TestResolver"
        )

        assertThat(result.objectSelections).isNotNull
        assertThat(result.querySelections).isNotNull
        assertEquals(setOf("queryVar"), result.objectSelections?.variablesResolvers?.variableNames)
    }

    // ============================================================================
    // Variable Source Validation Tests
    // ============================================================================

    @Test
    fun `Variable with no source - throws IllegalStateException`() {
        val annotation = createResolver(
            objectValueFragment = "baz",
            variables = arrayOf(
                createVariable(name = "badVar")
            )
        )

        assertThrows<IllegalStateException> {
            factory.mkRequiredSelectionSets(
                schema = defaultSchema,
                annotation = annotation,
                resolverForType = "Query",
                resolverClassName = "TestResolver"
            )
        }
    }

    @Test
    fun `Variable with multiple sources - throws IllegalStateException`() {
        val annotation = createResolver(
            objectValueFragment = "baz",
            variables = arrayOf(
                createVariable(
                    name = "badVar",
                    fromObjectField = "baz",
                    fromArgument = "x"
                )
            )
        )

        val exception = assertThrows<IllegalStateException> {
            factory.mkRequiredSelectionSets(
                schema = defaultSchema,
                annotation = annotation,
                resolverForType = "Query",
                resolverClassName = "TestResolver"
            )
        }

        assertThat(exception.message).contains("badVar")
        assertThat(exception.message).contains("exactly one")
    }

    @Test
    fun `Variable with fromObjectField and fromQueryField - throws IllegalStateException`() {
        val annotation = createResolver(
            objectValueFragment = "baz",
            queryValueFragment = "queryData",
            variables = arrayOf(
                createVariable(
                    name = "badVar",
                    fromObjectField = "baz",
                    fromQueryField = "queryData"
                )
            )
        )

        assertThrows<IllegalStateException> {
            factory.mkRequiredSelectionSets(
                schema = defaultSchema,
                annotation = annotation,
                resolverForType = "Query",
                resolverClassName = "TestResolver"
            )
        }
    }

    // ============================================================================
    // Unused Variable Detection Tests
    // ============================================================================

    @Test
    fun `unused variable declared - throws IllegalArgumentException`() {
        val annotation = createResolver(
            objectValueFragment = "baz",
            variables = arrayOf(
                createVariable(name = "unusedVar", fromArgument = "x")
            )
        )

        val exception = assertThrows<IllegalArgumentException> {
            factory.mkRequiredSelectionSets(
                schema = defaultSchema,
                annotation = annotation,
                resolverForType = "Query",
                resolverClassName = "TestResolver"
            )
        }

        assertThat(exception.message).contains("unused variables")
        assertThat(exception.message).contains("unusedVar")
    }

    @Test
    fun `multiple unused variables declared - throws IllegalArgumentException with all names`() {
        val annotation = createResolver(
            objectValueFragment = "baz",
            variables = arrayOf(
                createVariable(name = "unused1", fromArgument = "x"),
                createVariable(name = "unused2", fromObjectField = "baz")
            )
        )

        val exception = assertThrows<IllegalArgumentException> {
            factory.mkRequiredSelectionSets(
                schema = defaultSchema,
                annotation = annotation,
                resolverForType = "Query",
                resolverClassName = "TestResolver"
            )
        }

        assertThat(exception.message).contains("unused1")
        assertThat(exception.message).contains("unused2")
    }

    // ============================================================================
    // Multiple Variables Tests
    // ============================================================================

    @Test
    fun `multiple variables from different sources - all registered correctly`() {
        val annotation = createResolver(
            objectValueFragment = "bar(x: \$argVar, y: \$objVar, z: \$queryVar) baz",
            queryValueFragment = "queryData",
            variables = arrayOf(
                createVariable(name = "argVar", fromArgument = "x"),
                createVariable(name = "objVar", fromObjectField = "baz"),
                createVariable(name = "queryVar", fromQueryField = "queryData")
            )
        )

        val result = factory.mkRequiredSelectionSets(
            schema = defaultSchema,
            annotation = annotation,
            resolverForType = "Query",
            resolverClassName = "TestResolver"
        )

        assertEquals(
            setOf("argVar", "objVar", "queryVar"),
            result.objectSelections?.variablesResolvers?.variableNames
        )
    }

    // ============================================================================
    // Shared Variables Across Selection Sets Tests
    // ============================================================================

    @Test
    fun `shared variables across both selection sets`() {
        val annotation = createResolver(
            objectValueFragment = "foo(x: \$shared)",
            queryValueFragment = "bar(x: \$shared, y: 1, z: 2)",
            variables = arrayOf(
                createVariable(name = "shared", fromArgument = "x")
            )
        )

        val result = factory.mkRequiredSelectionSets(
            schema = defaultSchema,
            annotation = annotation,
            resolverForType = "Query",
            resolverClassName = "TestResolver"
        )

        // Both selection sets should have the shared variable
        assertEquals(setOf("shared"), result.objectSelections?.variablesResolvers?.variableNames)
        assertEquals(setOf("shared"), result.querySelections?.variablesResolvers?.variableNames)
    }

    // ============================================================================
    // Shorthand Fragment Syntax Tests
    // ============================================================================

    @Test
    fun `shorthand fragment syntax - parses correctly`() {
        // Simple field name without wrapping in fragment syntax
        val annotation = createResolver(
            objectValueFragment = "name"
        )

        val result = factory.mkRequiredSelectionSets(
            schema = defaultSchema,
            annotation = annotation,
            resolverForType = "Person",
            resolverClassName = "TestResolver"
        )

        assertThat(result.objectSelections).isNotNull
    }

    // ============================================================================
    // Backward Compatibility Tests
    // ============================================================================

    @Test
    fun `plain Resolver annotation without fragments - backward compatible`() {
        // This tests the scenario where existing resolvers don't use the new features
        val annotation = createResolver()

        val result = factory.mkRequiredSelectionSets(
            schema = defaultSchema,
            annotation = annotation,
            resolverForType = "Query",
            resolverClassName = "LegacyResolver"
        )

        // Should return empty result without errors
        assertThat(result).isEqualTo(RequiredSelectionSets.empty())
    }

    // ============================================================================
    // Helper Methods for Creating Test Annotations
    // ============================================================================

    private fun createResolver(
        objectValueFragment: String = "",
        queryValueFragment: String = "",
        variables: Array<Variable> = emptyArray()
    ): Resolver {
        return mockk {
            every { this@mockk.objectValueFragment } returns objectValueFragment
            every { this@mockk.queryValueFragment } returns queryValueFragment
            every { this@mockk.variables } returns variables
        }
    }

    private fun createVariable(
        name: String,
        fromArgument: String = "",
        fromObjectField: String = "",
        fromQueryField: String = ""
    ): Variable {
        return mockk {
            every { this@mockk.name } returns name
            every { this@mockk.fromArgument } returns fromArgument
            every { this@mockk.fromObjectField } returns fromObjectField
            every { this@mockk.fromQueryField } returns fromQueryField
        }
    }
}
