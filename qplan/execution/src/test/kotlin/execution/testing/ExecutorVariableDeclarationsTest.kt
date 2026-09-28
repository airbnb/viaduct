@file:Suppress("ForbiddenImport")

package execution.testing

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import model.Arguments
import model.fragmentFrom
import model.registry.ProviderFragment
import model.registry.VariableDefinition
import model.requireObjectField
import model.testing.TestWorld
import model.testing.fieldResolverOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockVariablesResolver
import viaduct.engine.api.mocks.createRSS
import viaduct.engine.api.spi.VariableFromArgumentDefinitions
import viaduct.engine.api.spi.VariableFromFieldDefinitions
import viaduct.engine.api.spi.VariableFromFunctionDefinitions
import viaduct.engine.runtime.mocks.ContextMocks

class ExecutorVariableDeclarationsTest {
    @Test
    fun `compiles distinct sources for identical fragment paths and preserves path dependencies`() {
        val objectSource = "selected: source(x: \$arg) use(x: \$query)"
        val querySource = "selected: source(x: \$arg) use(x: \$object)"
        val executor = object : MockFieldUnbatchedResolverExecutor(
            resolverId = "Query.result",
            objectSelectionSet = rss(objectSource, "arg", "query"),
            querySelectionSet = rss(querySource, "arg", "object"),
            argumentVariables = VariableFromArgumentDefinitions(mapOf("arg" to "input.value")),
        ) {
            override val objectFieldVariables = VariableFromFieldDefinitions(mapOf("object" to "selected"))
            override val queryFieldVariables = VariableFromFieldDefinitions(mapOf("query" to "selected"))
        }
        lateinit var compiled: ExecutorVariableDeclarations
        val world = TestWorld.fromSDL(
            """
            type Query { result(input: Input!): Int source(x: Int!): Int! use(x: Int!): Int! }
            input Input { value: Int! }
            """.trimIndent(),
            fieldResolvers = { schema ->
                val field = schema.requireObjectField("Query", "result")
                val objectFragment = schema.fragmentFrom("fragment _ on Query { $objectSource }", variableField = field)
                val queryFragment = schema.fragmentFrom("fragment _ on Query { $querySource }", variableField = field)
                compiled = executor.compileVariableDeclarations(
                    schema, field, objectFragment, queryFragment, ContextMocks().engineExecutionContext,
                )
                mapOf(field to fieldResolverOf(objectFragment, queryFragment) { _, _, _ -> null })
            },
            variableProviders = { compiled.declarations },
        )
        val field = world.schema.requireObjectField("Query", "result")
        val variables = world.resolverRegistry.resolver(field).variables.entries
            .associate { it.key.variableName to it.value }
        val argument = assertInstanceOf(VariableDefinition.FromArgument::class.java, variables.getValue("arg"))
        assertEquals("input", argument.argument.name)
        assertEquals(listOf("value"), argument.inputPath.map { it.name })
        for ((name, source) in listOf("object" to ProviderFragment.OBJECT, "query" to ProviderFragment.QUERY)) {
            val definition = assertInstanceOf(VariableDefinition.FromField::class.java, variables.getValue(name))
            assertEquals(source, definition.providerFragment)
            assertEquals(listOf("selected"), definition.responsePath)
            assertEquals("source", definition.path.single().field.name)
        }
    }

    @Test
    fun `executes explicit paths with argument and field dependencies`() {
        EngineTestModule(
            """
            extend type Query { source(x: Int!): Int! use(x: Int!): Int! result(input: Input!): Int! }
            input Input { value: Int! }
            """.trimIndent(),
        ) {
            field("Query" to "source") { resolver { fn { args, _, _, _, _ -> (args.getValue("x") as Int) * 2 } } }
            field("Query" to "use") { resolver { fn { args, _, _, _, _ -> args.getValue("x") } } }
            field("Query" to "result") {
                resolverExecutor {
                    object : MockFieldUnbatchedResolverExecutor(
                        resolverId = resolverId,
                        objectSelectionSet = rss("selected: source(x: \$arg) use(x: \$query)", "arg", "query"),
                        querySelectionSet = rss("selected: source(x: \$object) use(x: \$arg)", "object", "arg"),
                        argumentVariables = VariableFromArgumentDefinitions(mapOf("arg" to "input.value")),
                        unbatchedResolveFn = { _, obj, query, _, _ -> (obj.get("use") as Int) + (query.get("use") as Int) },
                    ) {
                        override val objectFieldVariables = VariableFromFieldDefinitions(mapOf("object" to "selected"))
                        override val queryFieldVariables = VariableFromFieldDefinitions(mapOf("query" to "selected"))
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{ a: result(input: {value: 3}) b: result(input: {value: 4}) }")
                .assertJson("{data: {a: 15, b: 20}}")
        }
    }

    @Test
    fun `preserves guards on declarative source paths`() {
        val sourceCalls = AtomicInteger()
        EngineTestModule(
            "extend type Query { source: Int use(x: Int): Int! result(enabled: Boolean!): Int! }",
        ) {
            field("Query" to "source") {
                resolver {
                    fn { _, _, _, _, _ ->
                        sourceCalls.incrementAndGet()
                        9
                    }
                }
            }
            field("Query" to "use") { resolver { fn { args, _, _, _, _ -> args["x"] ?: -1 } } }
            field("Query" to "result") {
                resolverExecutor {
                    object : MockFieldUnbatchedResolverExecutor(
                        resolverId = resolverId,
                        objectSelectionSet = rss("selected: source @include(if: \$enabled) use(x: \$value)", "enabled", "value"),
                        argumentVariables = VariableFromArgumentDefinitions(mapOf("enabled" to "enabled")),
                        unbatchedResolveFn = { _, obj, _, _, _ -> obj.get("use") },
                    ) {
                        override val objectFieldVariables = VariableFromFieldDefinitions(mapOf("value" to "selected"))
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{ shown: result(enabled: true) hidden: result(enabled: false) }")
                .assertJson("{data: {shown: 9, hidden: -1}}")
        }
        assertEquals(1, sourceCalls.get())
    }

    @Test
    fun `invokes direct callback once per occurrence across both fragments`() {
        val calls = AtomicInteger()
        val provider = object : VariableFromFunctionDefinitions, VariablesResolver {
            override val variableNames = setOf("left", "right")
            override suspend fun provideVariables(
                objectData: EngineObjectData.Sync,
                arguments: Map<String, Any?>,
                context: EngineExecutionContext,
            ): Map<String, Any?> {
                calls.incrementAndGet()
                val x = arguments.getValue("x") as Int
                return mapOf("left" to x * 2, "right" to x + 1)
            }
            override suspend fun resolve(ctx: VariablesResolver.ResolveCtx, context: EngineExecutionContext): Map<String, Any?> =
                error("Legacy entry point must not be called")
        }
        EngineTestModule("extend type Query { use(x: Int!): Int! result(x: Int!): Int! }") {
            field("Query" to "use") { resolver { fn { args, _, _, _, _ -> args.getValue("x") } } }
            field("Query" to "result") {
                resolverExecutor {
                    object : MockFieldUnbatchedResolverExecutor(
                        resolverId = resolverId,
                        objectSelectionSet = createRSS("Query", "use(x: \$left)", listOf(provider)),
                        querySelectionSet = createRSS("Query", "use(x: \$right)", listOf(provider)),
                        unbatchedResolveFn = { _, obj, query, _, _ -> (obj.get("use") as Int) + (query.get("use") as Int) },
                    ) {
                        override val variablesFromFunctionProvider = provider
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{ a: result(x: 3) b: result(x: 4) }").assertJson("{data: {a: 10, b: 13}}")
        }
        assertEquals(2, calls.get())
    }

    @Test
    fun `direct callback rejects missing and extra names`(): Unit = runBlocking {
        val schema = TestWorld.fromSDL("type Query { result: Int use(x: Int!): Int! }").schema
        val field = schema.requireObjectField("Query", "result")
        val fragment = schema.fragmentFrom("fragment _ on Query { use(x: \$value) }", variableField = field)
        for (output in listOf(emptyMap(), mapOf("value" to 1, "extra" to 2))) {
            val executor = object : MockFieldUnbatchedResolverExecutor(resolverId = "Query.result") {
                override val variablesFromFunctionProvider = object : VariableFromFunctionDefinitions {
                    override val variableNames = setOf("value")
                    override suspend fun provideVariables(
                        objectData: EngineObjectData.Sync,
                        arguments: Map<String, Any?>,
                        context: EngineExecutionContext,
                    ) = output
                }
            }
            val compiled = executor.compileVariableDeclarations(
                schema, field, fragment, null, ContextMocks().engineExecutionContext,
            )
            val failure = assertThrows<IllegalStateException> { compiled.provider!!(Arguments.Resolved.of(field, emptyMap())) }
            assertTrue(failure.message.orEmpty().contains("exactly its declared names"))
        }
    }

    @Test
    fun `rejects missing explicit declarations even when legacy recipes exist`() {
        val schema = TestWorld.fromSDL("type Query { result(x: Int!): Int use(x: Int!): Int! }").schema
        val field = schema.requireObjectField("Query", "result")
        for (arguments in listOf(emptyMap(), mapOf("arg" to "x"))) {
            val selections = if (arguments.isEmpty()) "use(x: \$value)" else "a: use(x: \$arg) use(x: \$value)"
            val objectFragment = schema.fragmentFrom("fragment _ on Query { $selections }", variableField = field)
            val executor = MockFieldUnbatchedResolverExecutor(
                resolverId = "Query.result",
                objectSelectionSet = rss(selections, "arg", "value"),
                argumentVariables = VariableFromArgumentDefinitions(arguments),
            )
            val failure = assertThrows<IllegalArgumentException> {
                executor.compileVariableDeclarations(schema, field, objectFragment, null, ContextMocks().engineExecutionContext)
            }
            assertTrue(failure.message.orEmpty().contains("Missing explicit variable declarations for Query.result: [value]"))
        }
    }

    private fun rss(selections: String, vararg names: String): RequiredSelectionSet =
        createRSS(
            "Query", selections,
            names.map { name -> MockVariablesResolver(name) { _, _ -> error("Legacy recipe must not run") } },
        )
}
