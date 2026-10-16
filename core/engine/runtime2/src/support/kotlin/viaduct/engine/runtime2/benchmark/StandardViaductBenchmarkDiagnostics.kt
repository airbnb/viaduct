package viaduct.engine.runtime2.benchmark

import com.fasterxml.jackson.databind.ObjectMapper
import graphql.language.AstPrinter
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.InlineFragment
import graphql.language.SelectionSet
import graphql.parser.Parser
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.spi.CheckerExecutor
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.runtime.execution.ExecutionParameters

object StandardViaductBenchmarkDiagnostics {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 1..3) { "Expected output directory, optional engine (old or new), and node cache (enabled or disabled)" }
        val destination = Path.of(args[0]).toAbsolutePath()
        val engine = BenchmarkEngine.valueOf(args.getOrElse(1) { "new" }.uppercase(Locale.ROOT))
        val nodeCache = BenchmarkNodeCache.parse(args.getOrElse(2) { "enabled" })
        Files.createDirectories(destination)
        val mapper = ObjectMapper()
        val counts = BenchmarkCallbackCounts()
        val support = StandardViaductBenchmarkSupport(engine, callbackCounts = counts, nodeCache = nodeCache)
        val invocationRows = mutableListOf<Map<String, Any>>()
        val checkerRows = mutableListOf<Map<String, Any>>()
        val rows = support.operations.mapIndexed { index, operation ->
            counts.reset()
            val result = support.executeQuery(index)
            invocationRows += mapOf("index" to index, "fieldResolvers" to counts.fieldBreakdown())
            checkerRows += mapOf("index" to index, "checkers" to counts.checkerCalls.toList())
            val document = Parser().parseDocument(operation)
            val selections = document.definitions.filterIsInstance<FragmentDefinition>().single().selectionSet
            val originalSelections = Parser().parseDocument(support.querySources[index]).definitions.filterIsInstance<FragmentDefinition>().single().selectionSet
            mapOf(
                "index" to index,
                "originalQueryBytes" to support.querySources[index].toByteArray(Charsets.UTF_8).size,
                "queryBytes" to operation.toByteArray(Charsets.UTF_8).size,
                "compactQueryBytes" to AstPrinter.printAstCompact(document).toByteArray(Charsets.UTF_8).size,
                "responseJsonBytes" to mapper.writeValueAsBytes(result.toSpecification()).size,
                "errors" to result.errors.size,
                "originalQueryAliases" to queryMetrics(originalSelections).getValue("queryAliases"),
            ) + queryMetrics(selections) + responseMetrics(result.getData()) + counts.snapshot()
        }
        val summary = rows.first().keys.filter { it != "index" }.associateWith { key ->
            val values = rows.map { it.getValue(key) }.sorted()
            mapOf(
                "min" to values.first(),
                "p50" to values[ceil(values.size * 0.5).toInt() - 1],
                "p90" to values[ceil(values.size * 0.9).toInt() - 1],
                "max" to values.last(),
                "mean" to values.average(),
            )
        }
        val writer = mapper.writerWithDefaultPrettyPrinter()
        writer.writeValue(destination.resolve("workload.json").toFile(), mapOf("engine" to engine.name, "nodeCache" to nodeCache.name, "summary" to summary, "queries" to rows))
        writer.writeValue(destination.resolve("field-resolver-calls.json").toFile(), invocationRows)
        writer.writeValue(destination.resolve("checker-calls.json").toFile(), checkerRows)
        val medianIndex = rows.sortedBy { it.getValue("queryBytes") }[rows.size / 2].getValue("index")
        Files.writeString(destination.resolve("example-query.graphql"), support.operations[medianIndex])
        writer.writeValue(destination.resolve("example-response.json").toFile(), support.executeQuery(medianIndex).toSpecification())
        println(writer.writeValueAsString(summary))
        println("Representative query index: $medianIndex; workload report: $destination")
    }

    internal fun queryMetrics(selections: SelectionSet): Map<String, Int> {
        var fields = 0
        var leaves = 0
        var arguments = 0
        var aliases = 0
        var maximumDepth = 0

        fun visit(
            set: SelectionSet,
            depth: Int
        ) {
            set.selections.forEach { selection ->
                when (selection) {
                    is Field -> {
                        fields++
                        if (selection.alias != null) aliases++
                        arguments += selection.arguments.size
                        maximumDepth = maxOf(maximumDepth, depth + 1)
                        if (selection.selectionSet == null) leaves++ else visit(selection.selectionSet, depth + 1)
                    }
                    is InlineFragment -> visit(selection.selectionSet, depth)
                }
            }
        }
        visit(selections, 0)
        return mapOf("queryFields" to fields, "queryLeaves" to leaves, "queryArguments" to arguments, "queryAliases" to aliases, "queryDepth" to maximumDepth)
    }

    internal fun responseMetrics(data: Any?): Map<String, Int> {
        var fields = 0
        var objects = 0
        var lists = 0
        var leaves = 0
        var nulls = 0
        var largestList = 0
        var maximumDepth = 0

        fun visit(
            value: Any?,
            depth: Int
        ) {
            maximumDepth = maxOf(maximumDepth, depth)
            when (value) {
                is Map<*, *> -> {
                    objects++
                    fields += value.size
                    value.values.forEach { visit(it, depth + 1) }
                }
                is List<*> -> {
                    lists++
                    largestList = maxOf(largestList, value.size)
                    value.forEach { visit(it, depth) }
                }
                null -> nulls++
                else -> leaves++
            }
        }
        visit(data, 0)
        return mapOf(
            "responseFields" to fields,
            "responseObjects" to objects,
            "responseLists" to lists,
            "responseLeaves" to leaves,
            "responseNulls" to nulls,
            "responseLargestList" to largestList,
            "responseDepth" to maximumDepth
        )
    }
}

internal class BenchmarkCallbackCounts {
    val fields = AtomicInteger()
    val objectValueGetters = AtomicInteger()
    val queryValueGetters = AtomicInteger()
    val nodes = AtomicInteger()
    val fieldCheckers = AtomicInteger()
    val typeCheckers = AtomicInteger()
    val fieldCalls = ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicInteger>>()
    val checkerCalls = ConcurrentLinkedQueue<Map<String, Any?>>()
    val oldObjectIds = ConcurrentHashMap<Any, Int>()
    private val nextObjectId = AtomicInteger()
    val nodeIds: MutableSet<Pair<String, String>> = ConcurrentHashMap.newKeySet()

    fun reset() {
        listOf(fields, objectValueGetters, queryValueGetters, nodes, fieldCheckers, typeCheckers).forEach { it.set(0) }
        nodeIds.clear()
        fieldCalls.clear()
        checkerCalls.clear()
        oldObjectIds.clear()
        nextObjectId.set(0)
    }

    fun oldObjectId(value: Any): Int = oldObjectIds.computeIfAbsent(value) { nextObjectId.incrementAndGet() }

    fun fieldBreakdown(): Map<String, Map<String, Int>> = fieldCalls.toSortedMap().mapValues { (_, arguments) -> arguments.toSortedMap().mapValues { it.value.get() } }

    fun snapshot(): Map<String, Int> =
        mapOf(
            "fieldResolverCallbacks" to fields.get(),
            "objectValueGetterCalls" to objectValueGetters.get(),
            "queryValueGetterCalls" to queryValueGetters.get(),
            "nodeResolverCallbacks" to nodes.get(),
            "distinctNodeIds" to nodeIds.size,
            "repeatedNodeResolverCallbacks" to nodes.get() - nodeIds.size,
            "fieldCheckerCallbacks" to fieldCheckers.get(),
            "typeCheckerCallbacks" to typeCheckers.get(),
        )
}

internal fun EngineTestModule.countCallbacks(counts: BenchmarkCallbackCounts): EngineTestModule {
    val inputKinds = mutableMapOf<RequiredSelectionSet.Id, String>()

    fun recordInput(rss: RequiredSelectionSet?) {
        if (rss == null || inputKinds.containsKey(rss.id)) return
        inputKinds[rss.id] = if (rss.forChecker) "checker" else "resolver"
        rss.variablesResolvers.forEach { recordInput(it.requiredSelectionSet) }
    }
    fieldResolverExecutors.forEach { (_, executor) ->
        recordInput(executor.objectSelectionSet)
        recordInput(executor.querySelectionSet)
    }
    (checkerExecutors.values + typeCheckerExecutors.values).forEach { executor ->
        executor.requiredSelectionSets.values.forEach(::recordInput)
    }

    fun checker(
        executor: CheckerExecutor,
        count: AtomicInteger,
        coordinate: String,
    ): CheckerExecutor =
        object : CheckerExecutor by executor {
            override suspend fun execute(
                arguments: Map<String, Any?>,
                objectDataMap: Map<String, EngineObjectData.Sync>,
                context: EngineExecutionContext,
                checkerType: CheckerExecutor.CheckerType,
            ): CheckerResult {
                count.incrementAndGet()
                val oldParameters = context.executionHandle as? ExecutionParameters
                val oldPlanId = oldParameters?.queryPlan?.requiredSelectionSetId
                counts.checkerCalls.add(
                    mapOf(
                        "kind" to checkerType.name,
                        "coordinate" to coordinate,
                        "arguments" to arguments,
                        "attribution" to context.fieldScope.attribution.type?.name,
                        "attributionName" to context.fieldScope.attribution.name,
                        "oldPath" to oldParameters?.path?.toString(),
                        "oldInputKind" to oldParameters?.let { if (oldPlanId == null) "operation" else inputKinds[oldPlanId] ?: "unknown" },
                        "oldOwnerId" to oldParameters?.let { counts.oldObjectId(it.currentObjectEngineResult) },
                        "oldField" to oldParameters?.field?.fieldName,
                        "oldFieldArguments" to oldParameters?.executionStepInfo?.arguments,
                    ),
                )
                return executor.execute(arguments, objectDataMap, context, checkerType)
            }
        }
    return EngineTestModule(
        fullSchema,
        fieldResolverExecutors.map { (coordinate, executor) ->
            coordinate to object : FieldResolverExecutor by executor {
                override suspend fun batchResolve(
                    selectors: List<FieldResolverExecutor.Selector>,
                    context: EngineExecutionContext,
                ): Map<FieldResolverExecutor.Selector, Result<Any?>> {
                    counts.fields.addAndGet(selectors.size)
                    val calls = counts.fieldCalls.computeIfAbsent("${coordinate.first}.${coordinate.second}") { ConcurrentHashMap() }
                    selectors.forEach { selector ->
                        calls.computeIfAbsent(selector.arguments.toSortedMap().toString()) { AtomicInteger() }.incrementAndGet()
                    }
                    val observed = selectors.map { selector ->
                        FieldResolverExecutor.Selector(
                            arguments = selector.arguments,
                            selections = selector.selections,
                            syncObjectValueGetter = {
                                counts.objectValueGetters.incrementAndGet()
                                selector.syncObjectValueGetter()
                            },
                            syncQueryValueGetter = {
                                counts.queryValueGetters.incrementAndGet()
                                selector.syncQueryValueGetter()
                            },
                        )
                    }
                    val results = executor.batchResolve(observed, context)
                    return selectors.zip(observed).mapNotNull { (original, wrapped) ->
                        results[wrapped]?.let { original to it }
                    }.toMap()
                }
            }
        },
        nodeResolverExecutors.map { (type, executor) ->
            type to object : NodeResolverExecutor by executor {
                override suspend fun resolve(
                    selectors: List<NodeResolverExecutor.Selector>,
                    context: EngineExecutionContext,
                ): Map<NodeResolverExecutor.Selector, Result<EngineObjectData>> {
                    counts.nodes.addAndGet(selectors.size)
                    selectors.forEach { counts.nodeIds.add(type to it.id) }
                    return executor.resolve(selectors, context)
                }
            }
        },
        checkerExecutors.mapValues { (coordinate, executor) -> checker(executor, counts.fieldCheckers, "${coordinate.first}.${coordinate.second}") },
        typeCheckerExecutors.mapValues { (type, executor) -> checker(executor, counts.typeCheckers, type) },
    )
}
