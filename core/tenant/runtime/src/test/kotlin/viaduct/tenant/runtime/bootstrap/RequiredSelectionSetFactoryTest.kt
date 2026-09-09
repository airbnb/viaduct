@file:Suppress("ForbiddenImport")

package viaduct.tenant.runtime.bootstrap

import com.google.inject.Guice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import viaduct.api.ResolverBase
import viaduct.api.context.VariablesProviderContext
import viaduct.api.internal.DefaultGRTConvFactory
import viaduct.api.internal.ResolverFor
import viaduct.api.mocks.mockReflectionLoader
import viaduct.api.resolver.Variables
import viaduct.api.resolver.VariablesProvider
import viaduct.api.types.Arguments
import viaduct.engine.api.FromArgumentVariable
import viaduct.engine.api.FromObjectFieldVariable
import viaduct.engine.api.FromQueryFieldVariable
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.api.variableNames
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.tenant.runtime.context.VariablesProviderContextImpl
import viaduct.tenant.runtime.context.factory.VariablesProviderContextFactory
import viaduct.tenant.runtime.internal.InternalContextImpl
import viaduct.tenant.runtime.internal.VariablesProviderInfo

/**
 * Tests for RequiredSelectionSetFactory - tests that the factory returns properly-constructed
 * [RequiredSelectionSet]s (and throws errors where it's supposed to).
 *
 * WHAT THESE TESTS ARE TESTING:
 * - Variable conflict detection (duplicate names, VariablesProvider vs declared-variable conflicts)
 * - Variable binding correctness (variables from arguments/fields/VariablesProvider are registered)
 * - Unused variable detection (variables declared but not used in selections)
 *
 * WHAT THESE TESTS ARE NOT TESTING:
 * - How Arguments objects are created (delegated to argumentsFactory)
 * - How VariablesProviderContext is created (delegated to argumentsFactory)
 * - Actual execution/resolution of variables (tested in behavioral tests)
 *
 * The argumentsFactory parameter is passed through but never invoked in these tests
 * because they only validate structure and configuration, not runtime execution behavior.
 */
class RequiredSelectionSetFactoryTest {
    private fun mkFactory(): RequiredSelectionSetFactory = RequiredSelectionSetFactory

    class MockArguments : Arguments

    private class MockVariablesProvider(val vars: Map<String, Any?> = emptyMap()) : VariablesProvider<MockArguments> {
        override suspend fun provide(context: VariablesProviderContext<MockArguments>): Map<String, Any?> = vars
    }

    private val variablesProviderContextFactory = object : VariablesProviderContextFactory {
        override fun createVariablesProviderContext(
            engineExecutionContext: viaduct.engine.api.EngineExecutionContext,
            requestContext: Any?,
            rawArguments: Map<String, Any?>
        ): VariablesProviderContext<Arguments> {
            val ic = InternalContextImpl(
                engineExecutionContext.fullSchema,
                GlobalIDCodecDefault,
                mockReflectionLoader("viaduct.api.bootstrap.test.grts"),
                DefaultGRTConvFactory
            )
            return VariablesProviderContextImpl(ic, requestContext, MockArguments())
        }
    }

    // ============================================================================
    // variablesProvider() Tests (reflective discovery of a nested @Variables class,
    // still used in production by ViaductModernExecutorFactory)
    // ============================================================================

    private val injector = GuiceCodeInjector(Guice.createInjector())

    @ResolverFor(typeName = "Query", fieldName = "testField", isSelective = false)
    abstract class TestResolverBase : ResolverBase<Unit>

    class MyResolverBase : TestResolverBase() {
        @Suppress("unused")
        @Variables("y:Int!")
        class MyVariablesProvider : VariablesProvider<MockArguments> {
            override suspend fun provide(context: VariablesProviderContext<MockArguments>): Map<String, Any?> = mapOf("y" to 2)
        }
    }

    class NoVariablesProviderResolver : TestResolverBase()

    class InvalidVariablesClassResolver : TestResolverBase() {
        @Variables("x:Int!")
        class NotAVariablesProvider {
            // This class has @Variables but doesn't implement VariablesProvider
        }
    }

    @Test
    fun `variablesProvider -- discovers nested @Variables class implementing VariablesProvider`() {
        val info = MyResolverBase::class.variablesProvider(injector)
        assertEquals(setOf("y"), info?.variables)
    }

    @Test
    fun `variablesProvider -- returns null when no nested @Variables class is present`() {
        assertNull(NoVariablesProviderResolver::class.variablesProvider(injector))
    }

    @Test
    fun `variablesProvider -- throws when nested @Variables class does not implement VariablesProvider`() {
        assertThrows<IllegalArgumentException> {
            InvalidVariablesClassResolver::class.variablesProvider(injector)
        }
    }

    // ============================================================================
    // Public API Tests (validation logic using direct VariablesProviderInfo)
    // ============================================================================

    @Test
    fun `createRequiredSelectionSets -- conflicting variable names should throw at bootstrap time`() {
        // Test that variable conflicts are detected (VariablesProvider variable conflicts with fromArgument variable)
        val objectSelections = SelectionsParser.parse("Query", "foo(x:\$x)")
        val exception = assertThrows<IllegalStateException> {
            mkFactory().createRequiredSelectionSets(
                variablesProvider = VariablesProviderInfo(setOf("x")) { MockVariablesProvider(mapOf("x" to 1)) },
                objectSelections = objectSelections,
                querySelections = null,
                variablesProviderContextFactory = variablesProviderContextFactory,
                variables = listOf(FromArgumentVariable("x", "x")), // Conflicts with VariablesProvider variable
            )
        }

        // Verify the error message matches what is expected for this error condition
        assertNotNull(exception.message)
        assertTrue(exception.message!!.startsWith("Multiple VariablesResolver's provide a value for variable `x`"))
    }

    @Test
    fun `createRequiredSelectionSets -- VariablesProvider variable with unbound field argument should be allowed`() {
        // VariablesProvider can declare variables that match field argument names as long as they're not bound with fromArgument
        val objectSelections = SelectionsParser.parse("Query", "bar(x:\$boundX, y:\$y, z:\$z) baz")
        val rss = mkFactory().createRequiredSelectionSets(
            variablesProvider = VariablesProviderInfo(setOf("y")) { MockVariablesProvider(mapOf("y" to 2)) },
            objectSelections = objectSelections,
            querySelections = null,
            variablesProviderContextFactory = variablesProviderContextFactory,
            variables = listOf(
                FromArgumentVariable("boundX", "x"), // boundX is bound to argument x
                FromObjectFieldVariable("z", "baz") // z is bound to field baz
                // y is provided by VariablesProvider - no conflict since y is not bound to anything
            ),
        ).first

        // Verify the RequiredSelectionSet was created successfully with all three variables
        assertEquals(setOf("boundX", "y", "z"), rss?.variablesResolvers?.variableNames)
    }

    // ============================================================================
    // Public API Tests (core functionality without injector)
    // ============================================================================

    @Test
    fun `createRequiredSelectionSets -- no variables`() {
        val objectSelections = SelectionsParser.parse("Query", "__typename")
        val rss = mkFactory().createRequiredSelectionSets(
            variablesProvider = null,
            objectSelections = objectSelections,
            querySelections = null,
            variablesProviderContextFactory = variablesProviderContextFactory,
            variables = emptyList(),
        )
        assertEquals(objectSelections, rss.first?.selections)
        assertEquals(emptyList<VariablesResolver>(), rss.first?.variablesResolvers)
        assertNull(rss.second)
    }

    @Test
    fun `createRequiredSelectionSets -- from argument`() {
        val objectSelections = SelectionsParser.parse("Query", "x(arg:\$x)")
        val rss = mkFactory().createRequiredSelectionSets(
            variablesProvider = null,
            objectSelections = objectSelections,
            querySelections = null,
            variablesProviderContextFactory = variablesProviderContextFactory,
            variables = listOf(
                FromArgumentVariable("x", "x")
            ),
        )
        assertEquals(objectSelections, rss.first?.selections)
        assertEquals(setOf("x"), rss.first?.variablesResolvers?.variableNames)
        assertNull(rss.second)
    }

    @Test
    fun `createRequiredSelectionSets -- variables from selections`() {
        val objectSelections = SelectionsParser.parse("Query", "x(arg:\$y), baz")
        val rss = mkFactory().createRequiredSelectionSets(
            variablesProvider = null,
            objectSelections = objectSelections,
            querySelections = null,
            variablesProviderContextFactory = variablesProviderContextFactory,
            variables = listOf(
                FromObjectFieldVariable("y", "baz")
            ),
        )
        assertEquals(objectSelections, rss.first?.selections)
        assertEquals(setOf("y"), rss.first?.variablesResolvers?.variableNames)
        assertNull(rss.second)
    }

    @Test
    fun `createRequiredSelectionSets -- variables from VariablesProvider`() {
        val objectSelections = SelectionsParser.parse("Query", "foo(x: \$y)")
        val rss = mkFactory().createRequiredSelectionSets(
            variablesProvider = VariablesProviderInfo(setOf("y")) { MockVariablesProvider() },
            objectSelections = objectSelections,
            querySelections = null,
            variablesProviderContextFactory = variablesProviderContextFactory,
            variables = emptyList(),
        )
        assertEquals(objectSelections, rss.first?.selections)
        assertEquals(setOf("y"), rss.first?.variablesResolvers?.variableNames)
        assertNull(rss.second)
    }

    @Test
    fun `createRequiredSelectionSets -- duplicate variable bindings`() {
        val objectSelections = SelectionsParser.parse("Query", "field(arg: \$x)")

        // multiple from-argument variables with same name
        assertThrows<IllegalStateException> {
            mkFactory().createRequiredSelectionSets(
                variablesProvider = null,
                objectSelections = objectSelections,
                querySelections = null,
                variablesProviderContextFactory = variablesProviderContextFactory,
                variables = listOf(
                    FromArgumentVariable("x", "x1"),
                    FromArgumentVariable("x", "x2"),
                ),
            )
        }

        // multiple from-field variables with same name
        assertThrows<IllegalStateException> {
            mkFactory().createRequiredSelectionSets(
                variablesProvider = null,
                objectSelections = objectSelections,
                querySelections = null,
                variablesProviderContextFactory = variablesProviderContextFactory,
                variables = listOf(
                    FromObjectFieldVariable("x", "x1"),
                    FromArgumentVariable("x", "x2"),
                ),
            )
        }

        // hybrid
        assertThrows<IllegalStateException> {
            mkFactory().createRequiredSelectionSets(
                variablesProvider = null,
                objectSelections = objectSelections,
                querySelections = null,
                variablesProviderContextFactory = variablesProviderContextFactory,
                variables = listOf(
                    FromObjectFieldVariable("x", "x1"),
                    FromArgumentVariable("x", "x2"),
                ),
            )
        }
    }

    @Test
    fun `createRequiredSelectionSets -- VariablesProvider declares unused variable -- should throw at bootstrap time`() {
        // VariablesProvider declares variable 'undeclaredVar' that is not used in the selection set
        // and not declared in variables
        val exception = assertThrows<IllegalArgumentException> {
            val objectSelections = SelectionsParser.parse("Query", "foo(x: 123)") // no variables referenced
            mkFactory().createRequiredSelectionSets(
                variablesProvider = VariablesProviderInfo(
                    setOf("undeclaredVar"), // declares a variable not used anywhere
                    { MockVariablesProvider() }
                ),
                objectSelections = objectSelections,
                querySelections = null,
                variablesProviderContextFactory = variablesProviderContextFactory,
                variables = emptyList(), // no declared variables
            )
        }

        // Verify the error message describes the issue with unused variables
        assertNotNull(exception.message)
        assertTrue(exception.message!!.startsWith("Cannot build required selection sets: found declarations for unused variables:"))
        assertTrue(exception.message!!.contains("undeclaredVar"))
    }

    @Test
    fun `createRequiredSelectionSets -- VariablesProvider declares variable used in selection set -- should be allowed`() {
        // VariablesProvider declares variable 'x' that is actually used in the GraphQL selection
        val objectSelections = SelectionsParser.parse("Query", "foo(x: \$x)")
        val rss = mkFactory().createRequiredSelectionSets(
            variablesProvider = VariablesProviderInfo(
                setOf("x"),
                { MockVariablesProvider() }
            ),
            objectSelections = objectSelections,
            querySelections = null,
            variablesProviderContextFactory = variablesProviderContextFactory,
            variables = emptyList(),
        )

        assertEquals(setOf("x"), rss.first?.variablesResolvers?.variableNames)
        assertNull(rss.second)
    }

    @Test
    fun `createRequiredSelectionSets -- declared variables and VariablesProvider variables both validated for usage`() {
        // Test that both declared variables and VariablesProvider variables are validated for usage
        val exception = assertThrows<IllegalArgumentException> {
            val objectSelections = SelectionsParser.parse("Query", "foo(x: \$usedVar)") // only usedVar is referenced
            mkFactory().createRequiredSelectionSets(
                variablesProvider = VariablesProviderInfo(
                    setOf("unusedProviderVar"), // VariablesProvider declares unused variable
                    { MockVariablesProvider() }
                ),
                objectSelections = objectSelections,
                querySelections = null,
                variablesProviderContextFactory = variablesProviderContextFactory,
                variables = listOf(
                    FromArgumentVariable("usedVar", "arg"),
                    FromArgumentVariable("unusedAnnotationVar", "unused")
                )
            )
        }

        // Verify both unused variables are mentioned in the error
        assertNotNull(exception.message)
        assertTrue(exception.message!!.startsWith("Cannot build required selection sets: found declarations for unused variables:"))
        assertTrue(exception.message!!.contains("unusedProviderVar"))
        assertTrue(exception.message!!.contains("unusedAnnotationVar"))
    }

    @Test
    fun `createRequiredSelectionSets -- VariablesProvider with declared variable -- should be allowed`() {
        // Test that VariablesProvider variables are allowed if they match declared variables
        // Scenario 1: VariablesProvider provides variable used in GraphQL, declared variables provide a different variable
        val objectSelections = SelectionsParser.parse("Query", "foo(y:\$y, z:\$z)") // uses variables
        val rss = mkFactory().createRequiredSelectionSets(
            variablesProvider = VariablesProviderInfo(
                setOf("z"), // declares variable z used in GraphQL
                { MockVariablesProvider() }
            ),
            objectSelections = objectSelections,
            querySelections = null,
            variablesProviderContextFactory = variablesProviderContextFactory,
            variables = listOf(FromArgumentVariable("y", "y")), // declared variable y
        )

        // Both variables should be present
        assertEquals(setOf("y", "z"), rss.first?.variablesResolvers?.variableNames)
        assertNull(rss.second)
    }

    // ============================================================================
    // @Variables Syntax Parsing Tests (testing Variables.asTypeMap() functionality)
    // ============================================================================

    @Test
    fun `Variables -- asTypeMap`() {
        fun assertTypeMap(
            vararg types: String,
            expected: Map<String, String>
        ) = assertEquals(expected, Variables(*types).asTypeMap())

        fun assertThrows(vararg types: String) = assertThrows<IllegalArgumentException> { Variables(*types).asTypeMap() }

        // empty
        assertTypeMap(expected = emptyMap())
        assertTypeMap("", expected = emptyMap())
        assertTypeMap("  ", expected = emptyMap())
        assertTypeMap("\t", expected = emptyMap())

        // single entry
        assertTypeMap("a:A", expected = mapOf("a" to "A"))
        assertTypeMap("  a:A", expected = mapOf("a" to "A"))
        assertTypeMap("a:A  ", expected = mapOf("a" to "A"))
        assertTypeMap("a  :  A", expected = mapOf("a" to "A"))

        // multiple entries
        assertTypeMap("a:A", "b:B", expected = mapOf("a" to "A", "b" to "B"))
        assertTypeMap("   a:A", "b:B", expected = mapOf("a" to "A", "b" to "B"))
        assertTypeMap("a:A", "b:B  ", expected = mapOf("a" to "A", "b" to "B"))

        // bad formatting
        assertThrows("a:")
        assertThrows(":a")
        assertThrows("a:b:c")
        assertThrows(":")
    }
    // ============================================================================
    // Query Selections Tests
    // ============================================================================

    @Test
    fun `createRequiredSelectionSets -- queryValueFragment only, no objectValueFragment`() {
        val querySelections = SelectionsParser.parse("Query", "bar(y: \$y) baz")
        val rss = mkFactory().createRequiredSelectionSets(
            variablesProvider = null,
            objectSelections = null,
            querySelections = querySelections,
            variablesProviderContextFactory = variablesProviderContextFactory,
            variables = listOf(FromQueryFieldVariable("y", "baz")),
        )

        // No object selections were provided, so there's no object RequiredSelectionSet
        assertNull(rss.first)
        assertEquals(setOf("y"), rss.second?.variablesResolvers?.variableNames)
    }

    @Test
    fun `createRequiredSelectionSets -- dual selection sets with query selections`() {
        val objectSelections = SelectionsParser.parse("Foo", "foo(x: \$objVar)")
        val querySelections = SelectionsParser.parse("Query", "bar(y: \$queryVar) baz")
        val selections = mkFactory().createRequiredSelectionSets(
            variablesProvider = null,
            objectSelections = objectSelections,
            querySelections = querySelections,
            variablesProviderContextFactory = variablesProviderContextFactory,
            variables = listOf(
                FromArgumentVariable("objVar", "x"),
                FromQueryFieldVariable("queryVar", "baz")
            ),
        )

        // Should create both object and query selection sets
        // Variables are shared across both selection sets since they come from the same resolver
        assertEquals(setOf("objVar", "queryVar"), selections.first?.variablesResolvers?.variableNames)
        assertEquals(setOf("objVar", "queryVar"), selections.second?.variablesResolvers?.variableNames)
    }

    @Test
    fun `createRequiredSelectionSets -- shared variables across both selection sets`() {
        val objectSelections = SelectionsParser.parse("Query", "foo(x: \$shared)")
        val querySelections = SelectionsParser.parse("Query", "bar(y: \$shared)")
        val selections = mkFactory().createRequiredSelectionSets(
            variablesProvider = null,
            objectSelections = objectSelections,
            querySelections = querySelections,
            variablesProviderContextFactory = variablesProviderContextFactory,
            variables = listOf(
                FromArgumentVariable("shared", "x")
            ),
        )

        // Both selection sets should have the same shared variable
        assertEquals(setOf("shared"), selections.first?.variablesResolvers?.variableNames)
        assertEquals(setOf("shared"), selections.second?.variablesResolvers?.variableNames)
    }

    @Test
    fun `createRequiredSelectionSets -- query selections with VariablesProvider`() {
        val objectSelections = SelectionsParser.parse("Query", "foo(x: \$objVar)")
        val querySelections = SelectionsParser.parse("Query", "bar(y: \$queryVar)")
        val selections = mkFactory().createRequiredSelectionSets(
            variablesProvider = VariablesProviderInfo(setOf("objVar", "queryVar")) {
                MockVariablesProvider(
                    mapOf(
                        "objVar" to 42,
                        "queryVar" to "test"
                    )
                )
            },
            objectSelections = objectSelections,
            querySelections = querySelections,
            variablesProviderContextFactory = variablesProviderContextFactory,
            variables = emptyList(),
        )

        // Variables should be resolved from VariablesProvider for both selection sets
        // Each selection set gets all VariablesProvider variables (not filtered by usage)
        assertEquals(setOf("objVar", "queryVar"), selections.first?.variablesResolvers?.variableNames)
        assertEquals(setOf("objVar", "queryVar"), selections.second?.variablesResolvers?.variableNames)
    }

    @Test
    fun `createRequiredSelectionSets -- fromQueryField variables`() {
        val objectSelections = SelectionsParser.parse("Query", "obj(x: \$objVar)")
        val querySelections = SelectionsParser.parse("Query", "query(y: \$queryVar), queryData")
        val selections = mkFactory().createRequiredSelectionSets(
            variablesProvider = null,
            objectSelections = objectSelections,
            querySelections = querySelections,
            variablesProviderContextFactory = variablesProviderContextFactory,
            variables = listOf(
                FromArgumentVariable("objVar", "x"),
                FromQueryFieldVariable("queryVar", "queryData") // Variable sourced from query field
            ),
        )

        // Should create both selection sets with shared variables
        assertEquals(setOf("objVar", "queryVar"), selections.first?.variablesResolvers?.variableNames)
        assertEquals(setOf("objVar", "queryVar"), selections.second?.variablesResolvers?.variableNames)
    }

    @Test
    fun `createRequiredSelectionSets -- mixed variable sources integration test`() {
        val objectSelections = SelectionsParser.parse("Query", "obj(x: \$objVar, z: \$argVar), objData")
        val querySelections = SelectionsParser.parse("Query", "query(y: \$queryVar, z: \$argVar), queryData")

        // Test integration of all three variable types together
        val selections = mkFactory().createRequiredSelectionSets(
            variablesProvider = null,
            objectSelections = objectSelections,
            querySelections = querySelections,
            variablesProviderContextFactory = variablesProviderContextFactory,
            variables = listOf(
                FromObjectFieldVariable("objVar", "objData"),
                FromQueryFieldVariable("queryVar", "queryData"),
                FromArgumentVariable("argVar", "someArg")
            ),
        )

        // All variables should be available to both selection sets
        assertEquals(setOf("objVar", "queryVar", "argVar"), selections.first?.variablesResolvers?.variableNames)
        assertEquals(setOf("objVar", "queryVar", "argVar"), selections.second?.variablesResolvers?.variableNames)
    }

    // ============================================================================
    // FromQueryFieldVariable Edge Case Tests
    // ============================================================================

    @Test
    fun `createRequiredSelectionSets -- fromQueryField and fromArgument combination validation`() {
        // Test that we can have variables with different sources in the same resolver
        val objectSelections = SelectionsParser.parse("Query", "obj(x: \$argVar)")
        val querySelections = SelectionsParser.parse("Query", "query(y: \$queryVar), baz")

        assertDoesNotThrow {
            mkFactory().createRequiredSelectionSets(
                variablesProvider = null,
                objectSelections = objectSelections,
                querySelections = querySelections,
                variablesProviderContextFactory = variablesProviderContextFactory,
                variables = listOf(
                    FromArgumentVariable("argVar", "someArg"),
                    FromQueryFieldVariable("queryVar", "baz")
                )
            )
        }
    }

    @Test
    fun `createRequiredSelectionSets -- validation error for empty fromQueryField path`() {
        val querySelections = SelectionsParser.parse("Query", "foo(var: \$emptyVar)")
        val exception = assertThrows<IllegalArgumentException> {
            mkFactory().createRequiredSelectionSets(
                variablesProvider = null,
                objectSelections = null,
                querySelections = querySelections,
                variablesProviderContextFactory = variablesProviderContextFactory,
                variables = listOf(FromQueryFieldVariable("emptyVar", "")),
            )
        }
        assertEquals("Path for variable `emptyVar` is empty", exception.message)
    }

    @Test
    fun `createRequiredSelectionSets -- fromQueryField without queryValueFragment should fail`() {
        // Variable depends on query field but no queryValueFragment is provided
        assertThrows<IllegalStateException> {
            mkFactory().createRequiredSelectionSets(
                variablesProvider = null,
                objectSelections = SelectionsParser.parse("Query", "obj(x: \$queryVar)"),
                querySelections = null, // No query selections provided
                variablesProviderContextFactory = variablesProviderContextFactory,
                variables = listOf(
                    FromQueryFieldVariable("queryVar", "baz")
                ),
            )
        }
    }

    @Test
    fun `createRequiredSelectionSets -- fromQueryField path not in queryValueFragment should fail`() {
        // Variable depends on 'baz' but queryValueFragment only selects 'foo'
        assertThrows<IllegalArgumentException> {
            mkFactory().createRequiredSelectionSets(
                variablesProvider = null,
                objectSelections = SelectionsParser.parse("Query", "obj(x: \$queryVar)"),
                querySelections = SelectionsParser.parse("Query", "foo"), // Only selects 'foo', not 'baz'
                variablesProviderContextFactory = variablesProviderContextFactory,
                variables = listOf(
                    FromQueryFieldVariable("queryVar", "baz") // But variable needs 'baz'
                ),
            )
        }
    }
}
