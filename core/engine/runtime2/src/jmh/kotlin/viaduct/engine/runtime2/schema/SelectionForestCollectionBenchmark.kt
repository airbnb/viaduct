package viaduct.engine.runtime2.schema

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.execution.RawVariables
import graphql.execution.ValuesResolver
import graphql.language.FragmentDefinition
import graphql.language.OperationDefinition
import graphql.parser.Parser
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import graphql.validation.QueryComplexityLimits
import graphql.validation.Validator
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import viaduct.engine.runtime2.model.Selection
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.collect
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.toSelectionForest
import viaduct.graphql.schema.ViaductSchema

/** Cold construction and projection collection at every nonempty level of the schema-4 query. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 30)
@Measurement(iterations = 3, time = 60)
@Fork(value = 2, jvmArgsAppend = ["-Xms2g", "-Xmx8g", "-XX:+UseG1GC"])
open class SelectionForestCollectionBenchmark {
    private val sites = mutableListOf<CollectionSite>()

    @Setup(Level.Trial)
    fun setup() {
        val schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(SchemaParser().parse(resource("large-schema-4.graphqls.gz")))
        val document = Parser.parse(resource("large-schema-4-query.graphql.gz"))
        val errors = Validator().validateDocument(schema, document, { true }, Locale.ENGLISH, QueryComplexityLimits.NONE)
        check(errors.isEmpty()) { errors.take(3).joinToString { it.message } }
        val operation = document.getDefinitionsOfType(OperationDefinition::class.java).single()
        val fragments = document.getDefinitionsOfType(FragmentDefinition::class.java).associateBy { it.name }
        val context = GraphQLContext.newContext().build()
        val variables: CoercedVariables = ValuesResolver.coerceVariableValues(schema, operation.variableDefinitions, RawVariables.of(emptyMap<String, Any>()), context, Locale.ENGLISH)
        val lowered = ViaductAndGJSchema.fromGraphQLSchema(schema).loweredSchema
        val source = GJSelectionParser(schema, lowered, emptyMap()).selectionsFrom(operation, variables, context, Locale.ENGLISH, fragments)
        sites.clear()
        addSites(source, requireNotNull(lowered.queryTypeDef))
        check(sites.sumOf { it.selections.size } == 187_215)
        var constructionGroups = 0L
        var projectionGroups = 0L
        sites.forEach { site ->
            site.types.forEach { type ->
                val fresh = site.selections.toSelectionForest()
                constructionGroups += fresh.merge(type).size
                projectionGroups += fresh.collect(type).size
            }
        }
        println("Collection workload: sites=${sites.size}, typePairs=${sites.sumOf { it.types.size }}, constructionGroups=$constructionGroups, projectionGroups=$projectionGroups")
    }

    @Benchmark
    fun mergeAndCollect(blackhole: Blackhole) {
        sites.forEach { site ->
            site.types.forEach { type ->
                // Fresh wrappers prevent either collector's cache from hiding first-use costs.
                // The child source graphs are shared; only the current level is collected here.
                val fresh = site.selections.toSelectionForest()
                blackhole.consume(fresh.merge(type))
                blackhole.consume(fresh.collect(type))
            }
        }
    }

    private fun addSites(
        forest: SelectionForest,
        parent: ViaductSchema.CompositeTypeDef
    ) {
        if (forest.isEmpty()) return
        val selections = buildList { forest.forEach(::add) }
        sites += CollectionSite(selections, parent.possibleObjectTypes)
        selections.forEach { selection ->
            val child = selection.key.field.type.baseTypeDef
            if (child is ViaductSchema.CompositeTypeDef) addSites(selection.subselections, child)
        }
    }

    private fun resource(name: String): String =
        GZIPInputStream(requireNotNull(javaClass.getResourceAsStream("/viaduct/engine/runtime2/schema/$name")))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    private class CollectionSite(val selections: List<Selection>, val types: Set<ViaductSchema.Object>)
}
