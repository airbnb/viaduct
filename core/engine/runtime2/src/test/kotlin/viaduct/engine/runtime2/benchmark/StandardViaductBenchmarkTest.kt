package viaduct.engine.runtime2.benchmark

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.InlineFragment
import graphql.language.IntValue
import graphql.parser.Parser
import graphql.schema.GraphQLFieldsContainer
import graphql.schema.GraphQLTypeUtil
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import graphql.validation.Validator
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockCheckerExecutor
import viaduct.engine.api.mocks.MockCheckerExecutorFactory
import viaduct.engine.api.mocks.MockExecutorCodeInjector
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.createSchema
import viaduct.engine.api.mocks.createSchemaWithWiring
import viaduct.engine.api.mocks.invoke
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.runtime2.arbitrary.PrecomputedResolverOutputs
import viaduct.engine.runtime2.arbitrary.ResolverBenchmarkCorpus
import viaduct.engine.runtime2.arbitrary.ResolverBenchmarkQueryCorpus
import viaduct.service.api.ExecutionInput
import viaduct.service.api.ExecutionResult
import viaduct.service.api.SchemaId
import viaduct.service.api.spi.FlagManager
import viaduct.service.runtime.SchemaConfiguration
import viaduct.service.runtime.StandardViaduct

class StandardViaductBenchmarkTest {
    private val loader = object : ClassLoader(StandardViaductBenchmarkTest::class.java.classLoader) {
        override fun getResourceAsStream(name: String) = super.getResourceAsStream(name) ?: Files.newInputStream(Path.of("src/jmh/resources", name))
    }

    @Test
    fun `strict validation rejects errors and mismatches while exploratory validation reports them`() {
        val expected = listOf(mapOf<String, Any?>("data" to mapOf("value" to 1)))
        val different = listOf(mapOf<String, Any?>("data" to mapOf("value" to 2)))
        val errors = listOf(mapOf<String, Any?>("data" to null, "errors" to listOf(mapOf("message" to "Expected failure"))))
        assertEquals(emptyList<Int>(), validateBenchmarkResponses("matching", expected, expected, strict = true))
        assertEquals(listOf(0), validateBenchmarkResponses("different", different, expected, strict = false))
        assertEquals(emptyList<Int>(), validateBenchmarkResponses("errors", errors, strict = false))
        assertThrows<IllegalStateException> { validateBenchmarkResponses("different", different, expected, strict = true) }
        assertThrows<IllegalStateException> { validateBenchmarkResponses("errors", errors, strict = true) }
        assertThrows<IllegalArgumentException> { validateBenchmarkResponses("incomplete", emptyList(), expected, strict = false) }
    }

    @Test
    fun `getter diagnostics preserve resolver inputs results and failures`() {
        val schema = createSchema("extend type Query { value: Int }")
        val coordinate = "Query" to "value"
        lateinit var executionContext: EngineExecutionContext
        val executor = MockFieldUnbatchedResolverExecutor(resolverId = "getter-diagnostic") { arguments, objectValue, queryValue, _, context ->
            executionContext = context
            require(arguments["fail"] != true) { "Expected resolver failure" }
            (objectValue.get("value") as Int) + (queryValue.get("value") as Int)
        }
        val counts = BenchmarkCallbackCounts()
        val observed = EngineTestModule(schema, listOf(coordinate to executor)).countCallbacks(counts)
            .fieldResolverExecutors.single().second

        assertEquals(3, observed.invoke(schema, coordinate, objectValue = mapOf("value" to 1), queryValue = mapOf("value" to 2)))
        val emptyInput = ResolvedEngineObjectData(schema.schema.queryType, emptyMap())
        val selector = FieldResolverExecutor.Selector(
            arguments = mapOf("fail" to true),
            selections = null,
            syncObjectValueGetter = { emptyInput },
            syncQueryValueGetter = { emptyInput },
        )
        val failure = assertThrows<IllegalArgumentException> {
            runTest {
                observed.batchResolve(listOf(selector), executionContext).getValue(selector).getOrThrow()
            }
        }
        assertEquals("Expected resolver failure", failure.message)
        assertEquals(2, counts.fields.get())
        assertEquals(2, counts.fieldBreakdown().getValue("Query.value").values.sum())
        assertEquals(2, counts.objectValueGetters.get())
        assertEquals(2, counts.queryValueGetters.get())
        counts.reset()
        assertEquals(0, counts.snapshot().values.sum())
        assertEquals(emptyMap<String, Any>(), counts.fieldBreakdown())
    }

    @Test
    fun `comparison operations preserve compatible response keys and explicit aliases`() {
        val schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(
            SchemaParser().parse(
                """
            type Query { object: Object! }
            type Object { value(arg: Int!): String! }
                """.trimIndent()
            )
        )
        val source = "fragment Generated on Query { object { same: value(arg: 1) ... on Object { same: value(arg: 1) different: value(arg: 2) } } }"
        val parser = Parser()
        val original = parser.parseDocument("query Benchmark { ...Generated }\n$source")
        val validator = Validator()
        assertEquals(emptyList<Any>(), validator.validateDocument(schema, original, Locale.ROOT))
        val operation = benchmarkOperation(source)
        assertEquals(operation, benchmarkOperation(source))
        val document = parser.parseDocument(operation)
        assertEquals(emptyList<Any>(), validator.validateDocument(schema, document, Locale.ROOT))
        val fragment = document.definitions.filterIsInstance<FragmentDefinition>().single()
        assertEquals(
            mapOf("queryFields" to 4, "queryLeaves" to 3, "queryArguments" to 3, "queryAliases" to 3, "queryDepth" to 2),
            StandardViaductBenchmarkDiagnostics.queryMetrics(fragment.selectionSet),
        )
        val objectField = fragment.selectionSet.selections.single() as Field
        assertEquals("object", objectField.name)
        assertEquals(null, objectField.alias)
        val fields = objectField.selectionSet.selections
        val first = fields.first() as Field
        val guarded = fields.last() as InlineFragment
        val second = guarded.selectionSet.selections.first() as Field
        val third = guarded.selectionSet.selections.last() as Field
        assertEquals("Object", guarded.typeCondition.name)
        assertEquals(listOf("value", "value"), listOf(first.name, second.name))
        assertEquals(listOf("same", "same", "different"), listOf(first.alias, second.alias, third.alias))
        assertEquals(listOf(1, 1, 2), listOf(first, second, third).map { (it.arguments.single().value as IntValue).value.toInt() })
    }

    @Test
    fun `comparison corpus has one hundred valid queries and distinct unrelated field names`() {
        val queries = ResolverBenchmarkQueryCorpus.load(BENCHMARK_RESOURCE_PREFIX + "queries.json", loader)
        val corpus = ResolverBenchmarkCorpus.load(BENCHMARK_RESOURCE_PREFIX + "schema.graphqls", BENCHMARK_RESOURCE_PREFIX + "registry.json.gz", loader)
        val schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(SchemaParser().parse(corpus.schemaSDL))
        val duplicateNames = schema.allTypesAsList.filterIsInstance<GraphQLFieldsContainer>().filterNot { it.name.startsWith("__") }
            .flatMap { it.fieldDefinitions }.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        assertEquals(setOf("common", "id"), duplicateNames)
        assertEquals(100, queries.querySources.size)
        var fields = 0
        var aliases = 0

        queries.querySources.forEachIndexed { index, source ->
            val document = Parser().parseDocument(benchmarkOperation(source))
            assertEquals(emptyList<Any>(), Validator().validateDocument(schema, document, Locale.ROOT), "Query $index")
            val fragment = document.definitions.filterIsInstance<FragmentDefinition>().single()
            val metrics = StandardViaductBenchmarkDiagnostics.queryMetrics(fragment.selectionSet)
            fields += metrics.getValue("queryFields")
            aliases += metrics.getValue("queryAliases")
        }
        assertEquals(26252, fields)
        assertEquals(5672, aliases)
    }

    @Test
    fun `comparison registry coordinates and required selection paths resolve schema fields`() {
        val mapper = ObjectMapper()

        fun recipe(resource: String): JsonNode = loader.getResourceAsStream(BENCHMARK_RESOURCE_PREFIX + resource)!!.use { mapper.readTree(GZIPInputStream(it)) }
        val registry = recipe("registry.json.gz")
        val schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(SchemaParser().parse(registry["schema"]["sdl"].asText()))

        fun selections(
            nodes: JsonNode,
            owner: String
        ) {
            nodes.forEach { node ->
                val scope = node["typeCondition"].takeUnless { it.isNull }?.asText() ?: owner
                if (node["fieldName"].asText() == "__typename") {
                    assertTrue(node["subselections"].isEmpty)
                    return@forEach
                }
                val field = (schema.getType(scope) as GraphQLFieldsContainer).getFieldDefinition(node["fieldName"].asText())
                assertTrue(field != null, "$scope.${node["fieldName"]}")
                selections(node["subselections"], GraphQLTypeUtil.unwrapAll(field.type).name)
            }
        }
        registry["registry"]["fieldResolvers"].forEach { resolver ->
            listOf("fragment", "queryFragment").forEach { key -> selections(resolver[key]["selections"], resolver[key]["ownerName"].asText()) }
        }
        registry["registry"]["variables"].forEach { variable ->
            if (!variable["selection"].isNull) {
                val owner = if (variable["kind"].asText() == "from-query-field") "Query" else variable["owner"]["typeName"].asText()
                selections(mapper.createArrayNode().add(variable["selection"]), owner)
            }
        }

        fun coordinates(node: JsonNode) {
            if (node.has("typeName") && node.has("fieldName")) {
                val field = (schema.getType(node["typeName"].asText()) as GraphQLFieldsContainer).getFieldDefinition(node["fieldName"].asText())
                assertTrue(field != null, node.toString())
            }
            node.forEach(::coordinates)
        }
        coordinates(registry)
        val corpus = ResolverBenchmarkCorpus.load(BENCHMARK_RESOURCE_PREFIX + "schema.graphqls", BENCHMARK_RESOURCE_PREFIX + "registry.json.gz", loader)
        val checkers = ChinaActiveBenchmarkCheckers(corpus, comparisonMode = true)
        val world = corpus.registry.world(
            schemaSDL = checkers.schemaSDL,
            precomputedResolverOutputs = PrecomputedResolverOutputs(),
            additionalFieldResolvers = checkers::fieldResolvers,
            fieldCheckerFactory = checkers::fieldCheckers,
            typeCheckerFactory = checkers::typeCheckers,
        )
        BenchmarkExecutorAdapter(world, corpus.registry).module()
    }

    @Test
    fun `response metrics count list expansion without adding list nesting to field depth`() {
        val data = mapOf(
            "items" to listOf(mapOf("id" to "a", "label" to null), mapOf("id" to "b", "label" to "B")),
            "flags" to listOf(true, false),
        )
        assertEquals(
            mapOf("responseFields" to 6, "responseObjects" to 3, "responseLists" to 2, "responseLeaves" to 5, "responseNulls" to 1, "responseLargestList" to 2, "responseDepth" to 2),
            StandardViaductBenchmarkDiagnostics.responseMetrics(data),
        )
    }

    @Test
    @Suppress("DEPRECATION")
    fun `checker diagnostics expose raw input checks and alias sharing`() =
        runTest {
            val schema = createSchemaWithWiring("extend type Query { object: Object } type Object { id: String label: String }")
            val input = RequiredSelectionSet(SelectionsParser.parse("Object", "fragment Input on Object { id }"), emptyList(), forChecker = true)
            val module = EngineTestModule(
                schema,
                fieldResolverExecutors = listOf(
                    ("Query" to "object") to MockFieldUnbatchedResolverExecutor(resolverId = "checker-alias-diagnostic") { _, _, _, _, _ ->
                        ResolvedEngineObjectData(schema.schema.getObjectType("Object"), mapOf("id" to "1", "label" to "value"))
                    },
                ),
                checkerExecutors = mapOf(
                    ("Query" to "object") to MockCheckerExecutor(),
                    ("Object" to "label") to MockCheckerExecutor(mapOf("input" to input)),
                    ("Object" to "id") to MockCheckerExecutor(),
                ),
                typeCheckerExecutors = mapOf("Object" to MockCheckerExecutor(mapOf("input" to input))),
            )
            BenchmarkEngine.entries.forEach { engine ->
                val counts = BenchmarkCallbackCounts()
                val observed = module.countCallbacks(counts)
                val viaduct = StandardViaduct.Builder()
                    .withTenantModuleInjectorFactory(MockExecutorCodeInjector(observed.mockExecutorRegistry))
                    .withExecutorRegistryConfigSources(listOf(observed.toModuleConfigSource()))
                    .withCheckerExecutorFactory(MockCheckerExecutorFactory(observed.checkerExecutors, observed.typeCheckerExecutors))
                    .withSchemaConfiguration(SchemaConfiguration.fromSchema(schema))
                    .withFlagManager(object : FlagManager {
                        override fun isEnabled(flag: FlagManager.Flag): Boolean = engine == BenchmarkEngine.NEW && flag == FlagManager.Flags.ENGINE2_ENABLED
                    })
                    .build()
                val result = viaduct.execute(ExecutionInput.create(operationText = "{ first: object { a: label b: label } second: object { label } }"), SchemaId.Base)
                assertEquals(emptyList<Any>(), result.errors)
                assertEquals(mapOf("first" to mapOf("a" to "value", "b" to "value"), "second" to mapOf("label" to "value")), result.getData())
                val fieldCounts = counts.checkerCalls.filter { it["kind"] == "FIELD" }.groupingBy { it["coordinate"] }.eachCount()
                val expectedFields = if (engine == BenchmarkEngine.OLD) {
                    mapOf("Query.object" to 2, "Object.label" to 3, "Object.id" to 2)
                } else {
                    mapOf("Query.object" to 1, "Object.label" to 1)
                }
                assertEquals(expectedFields, fieldCounts, engine.name)
                assertEquals(if (engine == BenchmarkEngine.OLD) 2 else 1, counts.typeCheckers.get(), engine.name)
                if (engine == BenchmarkEngine.OLD) {
                    assertEquals(2, counts.checkerCalls.count { it["oldInputKind"] == "checker" })
                }
                counts.reset()
                assertTrue(counts.checkerCalls.isEmpty())
                assertTrue(counts.oldObjectIds.isEmpty())
            }
        }

    @Test
    fun `both engines execute the same hundred queries and report response differences`() {
        val new = StandardViaductBenchmarkSupport(BenchmarkEngine.NEW, loader)
        val expected = new.querySources.indices.map { new.executeQuery(it).toSpecification() }
        val nop = StandardViaductBenchmarkSupport(BenchmarkEngine.NEW, loader, cycleChecker = BenchmarkCycleChecker.NOP)
        validateBenchmarkResponses("NOP cycle checker response comparison", new.querySources.indices.map { nop.executeQuery(it).toSpecification() }, expected)
        assertEquals(new.operations, nop.operations)
        BenchmarkNodeCache.entries.forEach { nodeCache ->
            val counts = BenchmarkCallbackCounts()
            val old = StandardViaductBenchmarkSupport(BenchmarkEngine.OLD, loader, counts, nodeCache)
            assertEquals(100, old.querySources.size)
            assertEquals(old.operations, new.operations)
            var repeats = 0
            val responses = old.querySources.indices.map { index ->
                counts.reset()
                val result = old.executeQuery(index)
                repeats += counts.snapshot().getValue("repeatedNodeResolverCallbacks")
                result.toSpecification()
            }
            if (nodeCache.enabled) assertEquals(0, repeats) else assertTrue(repeats > 0)
            validateBenchmarkResponses("nodeCache=$nodeCache response comparison", responses, expected)
            println("StandardViaduct nodeCache=$nodeCache repeated node calls: $repeats")
        }
    }

    @Test
    fun `batch execution requires preparation and repeats every query`() {
        val queries = ResolverBenchmarkQueryCorpus.create(
            generationSeed = 0L,
            querySources = listOf(
                "fragment First on Query { __typename }",
                "fragment Second on Query { rootName: __typename }",
            ),
        ).encode()
        // Delegate streams explicitly to preserve the filesystem fallback used outside Bazel.
        val batchLoader = object : ClassLoader(null) {
            override fun getResourceAsStream(name: String) = if (name == BENCHMARK_RESOURCE_PREFIX + "queries.json") queries.byteInputStream() else loader.getResourceAsStream(name)
        }
        val benchmark = StandardViaductBenchmarkSupport(BenchmarkEngine.NEW, batchLoader)
        assertEquals(2, benchmark.querySources.size)
        assertThrows<IllegalStateException> { benchmark.executeBatch { } }
        assertThrows<IllegalArgumentException> { benchmark.prepareInvocation(0) }
        benchmark.prepareInvocation(2)
        val responses = mutableListOf<Map<String, Any?>>()
        assertEquals(
            4,
            benchmark.executeBatch { result ->
                val standardResult = result as? ExecutionResult.Standard
                    ?: error("Expected a standard result, got ${result::class.simpleName}")
                assertTrue(standardResult.errors.isEmpty(), standardResult.errors.toString())
                responses += requireNotNull(standardResult.getData())
            }
        )
        val expected = listOf(mapOf("__typename" to "Query"), mapOf("rootName" to "Query"))
        assertEquals(expected + expected, responses)
    }
}
