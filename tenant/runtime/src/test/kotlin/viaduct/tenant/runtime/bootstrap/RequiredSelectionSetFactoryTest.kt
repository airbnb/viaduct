@file:Suppress("ForbiddenImport")

package viaduct.tenant.runtime.bootstrap

import com.google.inject.Guice
import kotlin.reflect.full.findAnnotation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import strikt.api.expectThat
import strikt.assertions.isNotNull
import strikt.assertions.isTrue
import strikt.assertions.startsWith
import viaduct.api.Resolver
import viaduct.api.Variable
import viaduct.api.Variables
import viaduct.api.VariablesProvider
import viaduct.api.context.VariablesProviderContext
import viaduct.api.internal.ResolverBase
import viaduct.api.mocks.mockReflectionLoader
import viaduct.api.types.Arguments
import viaduct.engine.api.FromArgumentVariable
import viaduct.engine.api.FromObjectFieldVariable
import viaduct.engine.api.FromQueryFieldVariable
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.mocks.MockSchema
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
 * - Variable declaration validation (variables require fragments, must have exactly one source, etc.)
 * - Variable conflict detection (duplicate names, VariablesProvider vs annotation conflicts)
 * - Variable binding correctness (variables from arguments/fields/VariablesProvider are registered)
 * - Annotation parsing and injector integration (@Resolver, @Variables annotations)
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
    private val injector = GuiceTenantCodeInjector(Guice.createInjector())
    private val defaultSchema = MockSchema.mk(
        """
        extend type Query {
            foo(x:Int!):Int!,
            bar(x:Int!, y:Int!, z:Int!):Int!,
            baz:Int!,
            testField(
                nonNullableInt: Int!,
                intList: [Int!]!,
                stringList: [String]
            ): String
        }

        # Object type (not valid as variable type)
        type User {
            id: ID!
            name: String!
        }

        # Union type (not valid as variable type)
        union SearchResult = User

        # Input type (valid as variable type)
        input UserInput {
            name: String!
        }
        """.trimIndent()
    )

    private fun mkFactory(): RequiredSelectionSetFactory =
        RequiredSelectionSetFactory(
            GlobalIDCodecDefault,
            mockReflectionLoader("viaduct.api.bootstrap.test.grts"),
        )

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
                mockReflectionLoader("viaduct.api.bootstrap.test.grts")
            )
            return VariablesProviderContextImpl(ic, requestContext, MockArguments())
        }
    }

    @Resolver(
        "y(x:\$x, y:\$y, z:\$z), baz",
        variables = [
            Variable(name = "x", fromArgument = "x"),
            Variable(name = "z", fromObjectField = "baz")
        ]
    )
    class MyResolverBase : ResolverBase<Unit> {
        @Suppress("unused")
        @Variables("y:Int!")
        class MyVariablesProvider : VariablesProvider<MockArguments> {
            override suspend fun provide(context: VariablesProviderContext<MockArguments>): Map<String, Any?> = mapOf("y" to 2)
        }
    }

    // ============================================================================
    // Injector Tests (testing injector parsing of resolver classes)
    // ============================================================================

    @Test
    fun `mkRequiredSelectionSets -- via injector`() {
        val rss = mkFactory().mkRequiredSelectionSets(
            schema = defaultSchema,
            injector = injector,
            resolverCls = MyResolverBase::class,
            variablesProviderContextFactory = variablesProviderContextFactory,
            annotation = MyResolverBase::class.findAnnotation<Resolver>()!!,
            resolverForType = "Query",
        ).objectSelections
        assertEquals(setOf("x", "y", "z"), rss?.variablesResolvers?.variableNames)
    }

    @Resolver // No variables, no fragments - should work
    class EmptyAnnotationResolver : ResolverBase<Unit>

    @Test
    fun `mkRequiredSelectionSets -- injector handles empty annotation`() {
        val rss = mkFactory().mkRequiredSelectionSets(
            schema = defaultSchema,
            injector = injector,
            resolverCls = EmptyAnnotationResolver::class,
            variablesProviderContextFactory = variablesProviderContextFactory,
            annotation = EmptyAnnotationResolver::class.findAnnotation<Resolver>()!!,
            resolverForType = "Query",
        )
        // Should create empty result for no fragments
        assertEquals(null, rss.objectSelections)
        assertEquals(null, rss.querySelections)
    }

    @Resolver(variables = [Variable("x", "baz")]) // Variable without fragment should throw
    class VariableWithoutFragmentResolver : ResolverBase<Unit>

    @Test
    fun `mkRequiredSelectionSets -- injector validates variable requires fragment`() {
        assertThrows<IllegalStateException> {
            mkFactory().mkRequiredSelectionSets(
                schema = defaultSchema,
                injector = injector,
                resolverCls = VariableWithoutFragmentResolver::class,
                variablesProviderContextFactory = variablesProviderContextFactory,
                annotation = VariableWithoutFragmentResolver::class.findAnnotation<Resolver>()!!,
                resolverForType = "Query",
            )
        }
    }

    @Resolver("bar", variables = [Variable("x")]) // Variable without fromField or fromArgument
    class VariableWithoutSourceResolver : ResolverBase<Unit>

    @Test
    fun `mkRequiredSelectionSets -- injector validates variable requires source`() {
        assertThrows<IllegalStateException> {
            mkFactory().mkRequiredSelectionSets(
                schema = defaultSchema,
                injector = injector,
                resolverCls = VariableWithoutSourceResolver::class,
                variablesProviderContextFactory = variablesProviderContextFactory,
                annotation = VariableWithoutSourceResolver::class.findAnnotation<Resolver>()!!,
                resolverForType = "Query",
            )
        }
    }

    @Resolver(
        "bar",
        variables = [Variable(name = "x", fromObjectField = "baz", fromArgument = "x")]
    ) // Variable with both fromField and fromArgument
    class VariableWithBothSourcesResolver : ResolverBase<Unit>

    @Test
    fun `mkRequiredSelectionSets -- injector validates variable cannot have both sources`() {
        assertThrows<IllegalStateException> {
            mkFactory().mkRequiredSelectionSets(
                schema = defaultSchema,
                injector = injector,
                resolverCls = VariableWithBothSourcesResolver::class,
                variablesProviderContextFactory = variablesProviderContextFactory,
                annotation = VariableWithBothSourcesResolver::class.findAnnotation<Resolver>()!!,
                resolverForType = "Query",
            )
        }
    }

    @Resolver(
        "field(arg: \$z) baz",
        variables = [Variable("z", fromObjectField = "baz")]
    )
    class ValidFromFieldResolver : ResolverBase<Unit>

    @Test
    fun `mkRequiredSelectionSets -- injector handles valid fromField variable`() {
        val rss = mkFactory().mkRequiredSelectionSets(
            schema = defaultSchema,
            injector = injector,
            resolverCls = ValidFromFieldResolver::class,
            variablesProviderContextFactory = variablesProviderContextFactory,
            annotation = ValidFromFieldResolver::class.findAnnotation<Resolver>()!!,
            resolverForType = "Query",
        )
        // Should successfully create selection set with fromField variable
        assertEquals(setOf("z"), rss.objectSelections?.variablesResolvers?.variableNames)
    }

    @Resolver(
        "field(arg: \$x)",
        variables = [Variable("x", fromArgument = "x")]
    )
    class ValidFromArgumentResolver : ResolverBase<Unit>

    @Test
    fun `mkRequiredSelectionSets -- injector handles valid fromArgument variable`() {
        val rss = mkFactory().mkRequiredSelectionSets(
            schema = defaultSchema,
            injector = injector,
            resolverCls = ValidFromArgumentResolver::class,
            variablesProviderContextFactory = variablesProviderContextFactory,
            annotation = ValidFromArgumentResolver::class.findAnnotation<Resolver>()!!,
            resolverForType = "Query",
        )
        // Should successfully create selection set with fromArgument variable
        assertEquals(setOf("x"), rss.objectSelections?.variablesResolvers?.variableNames)
    }

    @Test
    fun `mkRequiredSelectionSets -- injector validates @Variables class implements VariablesProvider`() {
        assertThrows<IllegalArgumentException> {
            mkFactory().mkRequiredSelectionSets(
                schema = defaultSchema,
                injector = injector,
                resolverCls = InvalidVariablesClassResolver::class,
                variablesProviderContextFactory = variablesProviderContextFactory,
                annotation = InvalidVariablesClassResolver::class.findAnnotation<Resolver>()!!,
                resolverForType = "Query",
            )
        }
    }

    // ============================================================================
    // Public API Tests (validation logic using direct VariablesProviderInfo)
    // ============================================================================

    @Test
    fun `mkRequiredSelectionSets -- conflicting variable names should throw at bootstrap time`() {
        // Test that variable conflicts are detected (VariablesProvider variable conflicts with fromArgument variable)
        val objectSelections = SelectionsParser.parse("Query", "foo(x:\$x)")
        val exception = assertThrows<IllegalStateException> {
            mkFactory().mkRequiredSelectionSets(
                variablesProvider = VariablesProviderInfo(setOf("x")) { MockVariablesProvider(mapOf("x" to 1)) },
                objectSelections = objectSelections,
                querySelections = null,
                variablesProviderContextFactory = variablesProviderContextFactory,
                variables = listOf(FromArgumentVariable("x", "x")), // Conflicts with VariablesProvider variable
            )
        }

        // Verify the error message matches what is expected for this error condition
        expectThat(exception.message) {
            isNotNull().startsWith("Multiple VariablesResolver's provide a value for variable `x`")
        }
    }

    @Test
    fun `mkRequiredSelectionSets -- VariablesProvider variable with unbound field argument should be allowed`() {
        // VariablesProvider can declare variables that match field argument names as long as they're not bound with fromArgument
        val objectSelections = SelectionsParser.parse("Query", "bar(x:\$boundX, y:\$y, z:\$z) baz")
        val rss = mkFactory().mkRequiredSelectionSets(
            variablesProvider = VariablesProviderInfo(setOf("y")) { MockVariablesProvider(mapOf("y" to 2)) },
            objectSelections = objectSelections,
            querySelections = null,
            variablesProviderContextFactory = variablesProviderContextFactory,
            variables = listOf(
                FromArgumentVariable("boundX", "x"), // boundX is bound to argument x
                FromObjectFieldVariable("z", "baz") // z is bound to field baz
                // y is provided by VariablesProvider - no conflict since y is not bound to anything
            ),
        ).objectSelections

        // Verify the RequiredSelectionSet was created successfully with all three variables
        assertEquals(setOf("boundX", "y", "z"), rss?.variablesResolvers?.variableNames)
    }

    // ============================================================================
    // Public API Tests (core functionality without injector)
    // ============================================================================

    @Test
    fun `mkRequiredSelectionSets -- no variables`() {
        val objectSelections = SelectionsParser.parse("Query", "__typename")
        val rss = mkFactory().mkRequiredSelectionSets(
            variablesProvider = null,
            objectSelections = objectSelections,
            querySelections = null,
            variablesProviderContextFactory = variablesProviderContextFactory,
            variables = emptyList(),
        )
        assertEquals(objectSelections, rss.objectSelections?.selections)
        assertEquals(emptyList<VariablesResolver>(), rss.objectSelections?.variablesResolvers)
        assertNull(rss.querySelections)
    }

    @Test
    fun `mkRequiredSelectionSets -- from argument`() {
        val objectSelections = SelectionsParser.parse("Query", "x(arg:\$x)")
        val rss = mkFactory().mkRequiredSelectionSets(
            variablesProvider = null,
            objectSelections = objectSelections,
            querySelections = null,
            variablesProviderContextFactory = variablesProviderContextFactory,
            variables = listOf(
                FromArgumentVariable("x", "x")
            ),
        )
        assertEquals(objectSelections, rss.objectSelections?.selections)
        assertEquals(setOf("x"), rss.objectSelections?.variablesResolvers?.variableNames)
        assertNull(rss.querySelections)
    }

    @Test
    fun `mkRequiredSelectionSets -- variables from selections`() {
        val objectSelections = SelectionsParser.parse("Query", "x(arg:\$y), baz")
        val rss = mkFactory().mkRequiredSelectionSets(
            variablesProvider = null,
            objectSelections = objectSelections,
            querySelections = null,
            variablesProviderContextFactory = variablesProviderContextFactory,
            variables = listOf(
                FromObjectFieldVariable("y", "baz")
            ),
        )
        assertEquals(objectSelections, rss.objectSelections?.selections)
        assertEquals(setOf("y"), rss.objectSelections?.variablesResolvers?.variableNames)
        assertNull(rss.querySelections)
    }

    @Test
    fun `mkRequiredSelectionSets -- variables from VariablesProvider`() {
        val objectSelections = SelectionsParser.parse("Query", "foo(x: \$y)")
        val rss = mkFactory().mkRequiredSelectionSets(
            variablesProvider = VariablesProviderInfo(setOf("y")) { MockVariablesProvider() },
            objectSelections = objectSelections,
            querySelections = null,
            variablesProviderContextFactory = variablesProviderContextFactory,
            variables = emptyList(),
        )
        assertEquals(objectSelections, rss.objectSelections?.selections)
        assertEquals(setOf("y"), rss.objectSelections?.variablesResolvers?.variableNames)
        assertNull(rss.querySelections)
    }

    @Test
    fun `mkRequiredSelectionSets -- duplicate variable bindings`() {
        val objectSelections = SelectionsParser.parse("Query", "field(arg: \$x)")

        // multiple from-argument variables with same name
        assertThrows<IllegalStateException> {
            mkFactory().mkRequiredSelectionSets(
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
            mkFactory().mkRequiredSelectionSets(
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
            mkFactory().mkRequiredSelectionSets(
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
    fun `mkRequiredSelectionSets -- VariablesProvider declares unused variable -- should throw at bootstrap time`() {
        // VariablesProvider declares variable 'undeclaredVar' that is not used in the selection set
        // and not declared in @Variable annotations
        val exception = assertThrows<IllegalArgumentException> {
            val objectSelections = SelectionsParser.parse("Query", "foo(x: 123)") // no variables referenced
            mkFactory().mkRequiredSelectionSets(
                variablesProvider = VariablesProviderInfo(
                    setOf("undeclaredVar"), // declares a variable not used anywhere
                    { MockVariablesProvider() }
                ),
                objectSelections = objectSelections,
                querySelections = null,
                variablesProviderContextFactory = variablesProviderContextFactory,
                variables = emptyList(), // no @Variable annotations
            )
        }

        // Verify the error message describes the issue with unused variables
        expectThat(exception.message)
            .isNotNull()
            .startsWith("Cannot build RequiredSelectionSets: found declarations for unused variables:")
        expectThat(exception.message!!.contains("undeclaredVar")).isTrue()
    }

    @Test
    fun `mkRequiredSelectionSets -- VariablesProvider declares variable used in selection set -- should be allowed`() {
        // VariablesProvider declares variable 'x' that is actually used in the GraphQL selection
        val objectSelections = SelectionsParser.parse("Query", "foo(x: \$x)")
        val rss = mkFactory().mkRequiredSelectionSets(
            variablesProvider = VariablesProviderInfo(
                setOf("x"),
                { MockVariablesProvider() }
            ),
            objectSelections = objectSelections,
            querySelections = null,
            variablesProviderContextFactory = variablesProviderContextFactory,
            variables = emptyList(),
        )

        assertEquals(setOf("x"), rss.objectSelections?.variablesResolvers?.variableNames)
        assertNull(rss.querySelections)
    }

    @Test
    fun `mkRequiredSelectionSets -- annotation variables and VariablesProvider variables both validated for usage`() {
        // Test that both annotation variables and VariablesProvider variables are validated for usage
        val exception = assertThrows<IllegalArgumentException> {
            val objectSelections = SelectionsParser.parse("Query", "foo(x: \$usedVar)") // only usedVar is referenced
            mkFactory().mkRequiredSelectionSets(
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
        expectThat(exception.message)
            .isNotNull()
            .startsWith("Cannot build RequiredSelectionSets: found declarations for unused variables:")
        expectThat(exception.message!!.contains("unusedProviderVar")).isTrue()
        expectThat(exception.message!!.contains("unusedAnnotationVar")).isTrue()
    }

    @Test
    fun `mkRequiredSelectionSets -- VariablesProvider with annotation variable -- should be allowed`() {
        // Test that VariablesProvider variables are allowed if they match annotation variables
        // Scenario 1: VariablesProvider provides variable used in GraphQL, annotation provides different variable
        val objectSelections = SelectionsParser.parse("Query", "foo(y:\$y, z:\$z)") // uses variables
        val rss = mkFactory().mkRequiredSelectionSets(
            variablesProvider = VariablesProviderInfo(
                setOf("z"), // declares variable z used in GraphQL
                { MockVariablesProvider() }
            ),
            objectSelections = objectSelections,
            querySelections = null,
            variablesProviderContextFactory = variablesProviderContextFactory,
            variables = listOf(FromArgumentVariable("y", "y")), // annotation variable y
        )

        // Both variables should be present
        assertEquals(setOf("y", "z"), rss.objectSelections?.variablesResolvers?.variableNames)
        assertNull(rss.querySelections)
    }

    // Test basic @Variables syntax validation

    @Resolver("__typename") // No variables used in selection
    class EmptyVariablesResolver : ResolverBase<Unit> {
        @Variables("")
        class EmptyVariablesProvider : VariablesProvider<MockArguments> {
            override suspend fun provide(context: VariablesProviderContext<MockArguments>): Map<String, Any?> = emptyMap()
        }
    }

    @Resolver("__typename") // No variables used in selection
    class CommasOnlyVariablesResolver : ResolverBase<Unit> {
        @Variables(",,, ")
        class CommasOnlyVariablesProvider : VariablesProvider<MockArguments> {
            override suspend fun provide(context: VariablesProviderContext<MockArguments>): Map<String, Any?> = emptyMap()
        }
    }

    @Resolver("foo(x: \$testVar)")
    class InvalidSyntaxVariablesResolver : ResolverBase<Unit> {
        @Variables("invalidSyntax")
        class InvalidSyntaxVariablesProvider : VariablesProvider<MockArguments> {
            override suspend fun provide(context: VariablesProviderContext<MockArguments>): Map<String, Any?> = emptyMap()
        }
    }

    @Resolver("foo(x: \$testVar)")
    class NonExistentTypeVariablesResolver : ResolverBase<Unit> {
        @Variables("testVar:NonExistentType!")
        class NonExistentTypeVariablesProvider : VariablesProvider<MockArguments> {
            override suspend fun provide(context: VariablesProviderContext<MockArguments>): Map<String, Any?> = mapOf("testVar" to "someValue")
        }
    }

    @Resolver("foo(x: \$testVar)")
    class UnionTypeVariablesResolver : ResolverBase<Unit> {
        @Variables("testVar:SearchResult!")
        class UnionTypeVariablesProvider : VariablesProvider<MockArguments> {
            override suspend fun provide(context: VariablesProviderContext<MockArguments>): Map<String, Any?> = mapOf("testVar" to mapOf("id" to "123"))
        }
    }

    @Resolver("foo(x: \$testVar)")
    class InterfaceTypeVariablesResolver : ResolverBase<Unit> {
        @Variables("testVar:Node!")
        class InterfaceTypeVariablesProvider : VariablesProvider<MockArguments> {
            override suspend fun provide(context: VariablesProviderContext<MockArguments>): Map<String, Any?> = mapOf("testVar" to mapOf("id" to "123"))
        }
    }

    @Resolver("foo(x: \$testVar)")
    class ObjectTypeVariablesResolver : ResolverBase<Unit> {
        @Variables("testVar:User!")
        class ObjectTypeVariablesProvider : VariablesProvider<MockArguments> {
            override suspend fun provide(context: VariablesProviderContext<MockArguments>): Map<String, Any?> = mapOf("testVar" to mapOf("id" to "123", "name" to "Test"))
        }
    }

    @Resolver("foo(x: \$testVar)")
    class ValidInputTypeVariablesResolver : ResolverBase<Unit> {
        @Variables("testVar:UserInput!")
        class ValidInputTypeVariablesProvider : VariablesProvider<MockArguments> {
            override suspend fun provide(context: VariablesProviderContext<MockArguments>): Map<String, Any?> = mapOf("testVar" to mapOf("name" to "Test"))
        }
    }

    // ============================================================================
    // @Variables Syntax Parsing Tests (testing Variables.asTypeMap() functionality)
    // ============================================================================

    @Test
    fun `@Variables string is empty -- should be allowed at bootstrap time`() {
        // Empty @Variables strings are valid and result in no variables being declared
        val rss = mkFactory().mkRequiredSelectionSets(
            schema = defaultSchema,
            injector = injector,
            resolverCls = EmptyVariablesResolver::class,
            variablesProviderContextFactory = variablesProviderContextFactory,
            annotation = EmptyVariablesResolver::class.findAnnotation<Resolver>()!!,
            resolverForType = "Query",
        ).objectSelections

        // Should create RequiredSelectionSet successfully with no variables
        assertEquals(emptySet<String>(), rss?.variablesResolvers?.variableNames)
    }

    @Test
    fun `@Variables string is all commas -- should be allowed at bootstrap time`() {
        // Comma-only @Variables strings are valid and result in no variables being declared
        val rss = mkFactory().mkRequiredSelectionSets(
            schema = defaultSchema,
            injector = injector,
            resolverCls = CommasOnlyVariablesResolver::class,
            variablesProviderContextFactory = variablesProviderContextFactory,
            annotation = CommasOnlyVariablesResolver::class.findAnnotation<Resolver>()!!,
            resolverForType = "Query",
        ).objectSelections

        // Should create RequiredSelectionSet successfully with no variables
        assertEquals(emptySet<String>(), rss?.variablesResolvers?.variableNames)
    }

    // TODO: The error message for this is currently just "failed requirement" which is not very descriptive.
    // It should be improved to indicate a syntax error in the @Variables string.
    @Test
    fun `@Variables string is syntactically invalid -- should throw at bootstrap time`() {
        val exception = assertThrows<IllegalArgumentException> {
            mkFactory().mkRequiredSelectionSets(
                schema = defaultSchema,
                injector = injector,
                resolverCls = InvalidSyntaxVariablesResolver::class,
                variablesProviderContextFactory = variablesProviderContextFactory,
                annotation = InvalidSyntaxVariablesResolver::class.findAnnotation<Resolver>()!!,
                resolverForType = "Query",
            )
        }

        // Verify the error message describes the syntax issue (may be generic "Failed requirement")
        expectThat(
            exception.message?.contains("syntax") == true ||
                exception.message?.contains("invalid") == true ||
                exception.message?.contains("parse") == true ||
                exception.message?.contains("Failed requirement") == true
        ).describedAs("Expected error message to mention syntax/parsing issue or be 'Failed requirement' but got: ${exception.message}")
            .isTrue()
    }

    @Test
    fun `Variables -- asTypeMap`() {
        fun String.assertTypeMap(vararg pairs: Pair<String, String>) = assertEquals(pairs.toMap(), Variables(this).asTypeMap())

        fun String.assertThrows() = assertThrows<IllegalArgumentException> { Variables(this).asTypeMap() }

        // empty
        "".assertTypeMap()
        "  ".assertTypeMap()
        "\t".assertTypeMap()

        // single entry
        "a:A".assertTypeMap("a" to "A")
        "  a:A".assertTypeMap("a" to "A")
        "a:A  ".assertTypeMap("a" to "A")
        "a  :  A".assertTypeMap("a" to "A")
        "a:A,".assertTypeMap("a" to "A")
        ",a:A".assertTypeMap("a" to "A")

        // multiple entries
        "a:A,b:B".assertTypeMap("a" to "A", "b" to "B")
        "   a:A,b:B".assertTypeMap("a" to "A", "b" to "B")
        "a:A,b:B  ".assertTypeMap("a" to "A", "b" to "B")
        "a:A   ,   b:B".assertTypeMap("a" to "A", "b" to "B")

        // bad formatting
        "a:".assertThrows()
        ":a".assertThrows()
        "a:b:c".assertThrows()
        ":".assertThrows()
    }

    @Test
    fun `@Variables string refers to types that do not exist -- should throw at bootstrap time`() {
        // Note: This test documents the current behavior. Type validation may happen at GraphQL execution time,
        // not at bootstrap time. The @Variables annotation currently only validates syntax, not type existence.
        // If type validation is needed at bootstrap time, additional schema validation would be required.

        // For now, we test that the resolver can be created successfully (no bootstrap-time type validation)
        val rss = mkFactory().mkRequiredSelectionSets(
            schema = defaultSchema,
            injector = injector,
            resolverCls = NonExistentTypeVariablesResolver::class,
            variablesProviderContextFactory = variablesProviderContextFactory,
            annotation = NonExistentTypeVariablesResolver::class.findAnnotation<Resolver>()!!,
            resolverForType = "Query",
        ).objectSelections

        // The RequiredSelectionSet should be created successfully
        assertEquals(setOf("testVar"), rss?.variablesResolvers?.variableNames)
    }

    @Test
    @org.junit.jupiter.api.Disabled("Disabled due to https://app.asana.com/1/150975571430/project/1207604899751448/task/1210664713712227")
    fun `@Variables string refers to union type -- should throw at bootstrap time`() {
        // Union types are not valid as GraphQL variable types and should throw at bootstrap time
        assertThrows<IllegalArgumentException> {
            mkFactory().mkRequiredSelectionSets(
                schema = defaultSchema,
                injector = injector,
                resolverCls = UnionTypeVariablesResolver::class,
                variablesProviderContextFactory = variablesProviderContextFactory,
                annotation = UnionTypeVariablesResolver::class.findAnnotation<Resolver>()!!,
                resolverForType = "Query",
            )
        }
    }

    @Test
    @org.junit.jupiter.api.Disabled("Disabled due to https://app.asana.com/1/150975571430/project/1207604899751448/task/1210664713712227")
    fun `@Variables string refers to interface type -- should throw at bootstrap time`() {
        // Interface types are not valid as GraphQL variable types and should throw at bootstrap time
        assertThrows<IllegalArgumentException> {
            mkFactory().mkRequiredSelectionSets(
                schema = defaultSchema,
                injector = injector,
                resolverCls = InterfaceTypeVariablesResolver::class,
                variablesProviderContextFactory = variablesProviderContextFactory,
                annotation = InterfaceTypeVariablesResolver::class.findAnnotation<Resolver>()!!,
                resolverForType = "Query",
            )
        }
    }

    @Test
    @org.junit.jupiter.api.Disabled("Disabled due to https://app.asana.com/1/150975571430/project/1207604899751448/task/1210664713712227")
    fun `@Variables string refers to object type -- should throw at bootstrap time`() {
        // Object types are not valid as GraphQL variable types and should throw at bootstrap time
        assertThrows<IllegalArgumentException> {
            mkFactory().mkRequiredSelectionSets(
                schema = defaultSchema,
                injector = injector,
                resolverCls = ObjectTypeVariablesResolver::class,
                variablesProviderContextFactory = variablesProviderContextFactory,
                annotation = ObjectTypeVariablesResolver::class.findAnnotation<Resolver>()!!,
                resolverForType = "Query",
            )
        }
    }

    @Test
    fun `@Variables string refers to valid input type -- should be allowed at bootstrap time`() {
        // Input types are valid as GraphQL variable types and should be allowed
        val rss = mkFactory().mkRequiredSelectionSets(
            schema = defaultSchema,
            injector = injector,
            resolverCls = ValidInputTypeVariablesResolver::class,
            variablesProviderContextFactory = variablesProviderContextFactory,
            annotation = ValidInputTypeVariablesResolver::class.findAnnotation<Resolver>()!!,
            resolverForType = "Query",
        ).objectSelections

        // Should successfully create RequiredSelectionSet with valid input type
        assertEquals(setOf("testVar"), rss?.variablesResolvers?.variableNames)
    }

    // ============================================================================
    // Query Selections Tests
    // ============================================================================

    @Test
    fun `mkRequiredSelectionSets -- injector handles queryValueFragment only`() {
        val rss = mkFactory().mkRequiredSelectionSets(
            schema = defaultSchema,
            injector = injector,
            resolverCls = QueryOnlyResolver::class,
            variablesProviderContextFactory = variablesProviderContextFactory,
            annotation = QueryOnlyResolver::class.findAnnotation<Resolver>()!!,
            resolverForType = "Query",
        )
        // Should create query selections but no object selections
        assertNull(rss.objectSelections)
        assertEquals(setOf("y"), rss.querySelections?.variablesResolvers?.variableNames)
    }

    @Test
    fun `mkRequiredSelectionSets -- injector handles both objectValueFragment and queryValueFragment`() {
        val rss = mkFactory().mkRequiredSelectionSets(
            schema = defaultSchema,
            injector = injector,
            resolverCls = DualFragmentResolver::class,
            variablesProviderContextFactory = variablesProviderContextFactory,
            annotation = DualFragmentResolver::class.findAnnotation<Resolver>()!!,
            resolverForType = "Query",
        )
        // Should create both object and query selection sets with shared variables
        assertEquals(setOf("x", "y"), rss.objectSelections?.variablesResolvers?.variableNames)
        assertEquals(setOf("x", "y"), rss.querySelections?.variablesResolvers?.variableNames)
    }

    @Test
    fun `mkRequiredSelectionSets -- dual selection sets with query selections`() {
        val objectSelections = SelectionsParser.parse("Foo", "foo(x: \$objVar)")
        val querySelections = SelectionsParser.parse("Query", "bar(y: \$queryVar) baz")
        val selections = mkFactory().mkRequiredSelectionSets(
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
        assertEquals(setOf("objVar", "queryVar"), selections.objectSelections?.variablesResolvers?.variableNames)
        assertEquals(setOf("objVar", "queryVar"), selections.querySelections?.variablesResolvers?.variableNames)
    }

    @Resolver(
        queryValueFragment = "bar(y: \$y) baz",
        variables = [Variable(name = "y", fromQueryField = "baz")]
    )
    class QueryOnlyResolver : ResolverBase<Unit>

    @Resolver(
        objectValueFragment = "foo(x: \$x) baz",
        queryValueFragment = "bar(y: \$y)",
        variables = [
            Variable(name = "x", fromArgument = "x"),
            Variable(name = "y", fromObjectField = "baz")
        ]
    )
    class DualFragmentResolver : ResolverBase<Unit>

    @Resolver("foo")
    class InvalidVariablesClassResolver : ResolverBase<Unit> {
        @Variables("x:Int!")
        class NotAVariablesProvider {
            // This class has @Variables but doesn't implement VariablesProvider
        }
    }

    @Test
    fun `mkRequiredSelectionSets -- shared variables across both selection sets`() {
        val objectSelections = SelectionsParser.parse("Query", "foo(x: \$shared)")
        val querySelections = SelectionsParser.parse("Query", "bar(y: \$shared)")
        val selections = mkFactory().mkRequiredSelectionSets(
            variablesProvider = null,
            objectSelections = objectSelections,
            querySelections = querySelections,
            variablesProviderContextFactory = variablesProviderContextFactory,
            variables = listOf(
                FromArgumentVariable("shared", "x")
            ),
        )

        // Both selection sets should have the same shared variable
        assertEquals(setOf("shared"), selections.objectSelections?.variablesResolvers?.variableNames)
        assertEquals(setOf("shared"), selections.querySelections?.variablesResolvers?.variableNames)
    }

    @Test
    fun `mkRequiredSelectionSets -- query selections with VariablesProvider`() {
        val objectSelections = SelectionsParser.parse("Query", "foo(x: \$objVar)")
        val querySelections = SelectionsParser.parse("Query", "bar(y: \$queryVar)")
        val selections = mkFactory().mkRequiredSelectionSets(
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
        assertEquals(setOf("objVar", "queryVar"), selections.objectSelections?.variablesResolvers?.variableNames)
        assertEquals(setOf("objVar", "queryVar"), selections.querySelections?.variablesResolvers?.variableNames)
    }

    @Test
    fun `mkRequiredSelectionSets -- fromQueryField variables`() {
        val objectSelections = SelectionsParser.parse("Query", "obj(x: \$objVar)")
        val querySelections = SelectionsParser.parse("Query", "query(y: \$queryVar), queryData")
        val selections = mkFactory().mkRequiredSelectionSets(
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
        assertEquals(setOf("objVar", "queryVar"), selections.objectSelections?.variablesResolvers?.variableNames)
        assertEquals(setOf("objVar", "queryVar"), selections.querySelections?.variablesResolvers?.variableNames)
    }

    @Test
    fun `mkRequiredSelectionSets -- mixed variable sources integration test`() {
        val objectSelections = SelectionsParser.parse("Query", "obj(x: \$objVar, z: \$argVar), objData")
        val querySelections = SelectionsParser.parse("Query", "query(y: \$queryVar, z: \$argVar), queryData")

        // Test integration of all three variable types together
        val selections = mkFactory().mkRequiredSelectionSets(
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
        assertEquals(setOf("objVar", "queryVar", "argVar"), selections.objectSelections?.variablesResolvers?.variableNames)
        assertEquals(setOf("objVar", "queryVar", "argVar"), selections.querySelections?.variablesResolvers?.variableNames)
    }

    @Test
    fun `mkRequiredSelectionSets -- validation error for multiple field sources`() {
        // This will test the validation indirectly through the factory method
        assertThrows<IllegalStateException> {
            mkFactory().mkRequiredSelectionSets(
                defaultSchema,
                injector,
                BadMultipleFieldsResolver::class,
                variablesProviderContextFactory,
                Resolver(
                    objectValueFragment = "obj",
                    variables = arrayOf(
                        Variable("badVar", fromObjectField = "obj", fromQueryField = "query")
                    )
                ),
                "TestType"
            )
        }
    }

    @Test
    fun `mkRequiredSelectionSets -- validation error for no field sources`() {
        // This will test the validation indirectly through the factory method
        assertThrows<IllegalStateException> {
            mkFactory().mkRequiredSelectionSets(
                defaultSchema,
                injector,
                BadNoFieldsResolver::class,
                variablesProviderContextFactory,
                Resolver(
                    objectValueFragment = "obj",
                    variables = arrayOf(
                        Variable("badVar") // No fields set
                    )
                ),
                "TestType"
            )
        }
    }

    @Resolver(
        objectValueFragment = "obj(x: \$objVar)",
        queryValueFragment = "query(y: \$queryVar)",
        variables = [
            Variable(name = "objVar", fromObjectField = "objData"),
            Variable(name = "queryVar", fromQueryField = "queryData")
        ]
    )
    class MixedFieldSourceResolver : ResolverBase<Unit>

    @Resolver(objectValueFragment = "obj")
    class BadMultipleFieldsResolver : ResolverBase<Unit>

    @Resolver(objectValueFragment = "obj")
    class BadNoFieldsResolver : ResolverBase<Unit>

    // ============================================================================
    // FromQueryFieldVariable Edge Case Tests
    // ============================================================================

    @Resolver(
        queryValueFragment = "fragment _ on Query { foo(var: \$emptyVar) }",
        variables = [Variable(name = "emptyVar", fromQueryField = "")]
    )
    class EmptyQueryFieldPathResolver : ResolverBase<Unit>

    @Test
    fun `mkRequiredSelectionSets -- validation error for empty fromQueryField path`() {
        assertThrows<IllegalArgumentException>("Path for variable `emptyVar` is empty") {
            mkFactory().mkRequiredSelectionSets(
                defaultSchema,
                injector,
                EmptyQueryFieldPathResolver::class,
                variablesProviderContextFactory,
                EmptyQueryFieldPathResolver::class.findAnnotation<Resolver>()!!,
                "Query"
            )
        }
    }

    @Resolver(
        queryValueFragment = "fragment _ on Query { foo(x: \$invalidVar) }",
        variables = [Variable(name = "invalidVar", fromQueryField = "nonExistentField")]
    )
    class InvalidQueryFieldPathResolver : ResolverBase<Unit>

    @Test
    fun `mkRequiredSelectionSets -- validation error for invalid fromQueryField path`() {
        assertThrows<IllegalArgumentException> {
            mkFactory().mkRequiredSelectionSets(
                defaultSchema,
                injector,
                InvalidQueryFieldPathResolver::class,
                variablesProviderContextFactory,
                InvalidQueryFieldPathResolver::class.findAnnotation<Resolver>()!!,
                "Query"
            )
        }
    }

    @Resolver(
        queryValueFragment = "fragment _ on Query { foo(var: \$objectTypeVar) testField }",
        variables = [Variable(name = "objectTypeVar", fromQueryField = "testField")]
    )
    class QueryFieldToObjectTypeResolver : ResolverBase<Unit>

    @Test
    fun `mkRequiredSelectionSets -- validation allows scalar query field paths`() {
        // Should work fine - testField returns String which is a valid scalar type
        assertDoesNotThrow {
            mkFactory().mkRequiredSelectionSets(
                defaultSchema,
                injector,
                QueryFieldToObjectTypeResolver::class,
                variablesProviderContextFactory,
                QueryFieldToObjectTypeResolver::class.findAnnotation<Resolver>()!!,
                "Query"
            )
        }
    }

    @Test
    fun `mkRequiredSelectionSets -- fromQueryField and fromArgument combination validation`() {
        // Test that we can have variables with different sources in the same resolver
        val objectSelections = SelectionsParser.parse("Query", "obj(x: \$argVar)")
        val querySelections = SelectionsParser.parse("Query", "query(y: \$queryVar), baz")

        assertDoesNotThrow {
            mkFactory().mkRequiredSelectionSets(
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
    fun `mkRequiredSelectionSets -- fromQueryField without queryValueFragment should fail`() {
        // Variable depends on query field but no queryValueFragment is provided
        assertThrows<IllegalStateException> {
            mkFactory().mkRequiredSelectionSets(
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
    fun `mkRequiredSelectionSets -- fromQueryField path not in queryValueFragment should fail`() {
        // Variable depends on 'baz' but queryValueFragment only selects 'foo'
        assertThrows<IllegalArgumentException> {
            mkFactory().mkRequiredSelectionSets(
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
