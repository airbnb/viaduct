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
import viaduct.engine.runtime2.model.SelectionForest

/** Fresh AST-to-forest conversion plus full consumption, independent of the forest representation. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 30)
@Measurement(iterations = 3, time = 60)
@Fork(value = 2, jvmArgsAppend = ["-Xms2g", "-Xmx8g", "-XX:+UseG1GC"])
open class SelectionForestUnificationBenchmark {
    private lateinit var parser: GJSelectionParser
    private lateinit var operation: OperationDefinition
    private lateinit var fragments: Map<String, FragmentDefinition>
    private lateinit var variables: CoercedVariables
    private lateinit var context: GraphQLContext

    @Setup(Level.Trial)
    fun setup() {
        val schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(SchemaParser().parse(resource("large-schema-4.graphqls.gz")))
        val document = Parser.parse(resource("large-schema-4-query.graphql.gz"))
        val errors = Validator().validateDocument(schema, document, { true }, Locale.ENGLISH, QueryComplexityLimits.NONE)
        check(errors.isEmpty()) { errors.take(3).joinToString { it.message } }
        operation = document.getDefinitionsOfType(OperationDefinition::class.java).single()
        fragments = document.getDefinitionsOfType(FragmentDefinition::class.java).associateBy { it.name }
        context = GraphQLContext.newContext().build()
        variables = ValuesResolver.coerceVariableValues(schema, operation.variableDefinitions, RawVariables.of(emptyMap<String, Any>()), context, Locale.ENGLISH)
        val lowered = ViaductAndGJSchema.fromGraphQLSchema(schema).loweredSchema
        parser = GJSelectionParser(schema, lowered, emptyMap())
        check(createAndConsume() == 187_215L)
    }

    @Benchmark
    fun createAndConsume(): Long = consume(parser.selectionsFrom(operation, variables, context, Locale.ENGLISH, fragments))

    private fun consume(forest: SelectionForest): Long {
        var count = 0L
        forest.forEach { selection -> count += 1 + consume(selection.subselections) }
        return count
    }

    private fun resource(name: String): String =
        GZIPInputStream(requireNotNull(javaClass.getResourceAsStream("/viaduct/engine/runtime2/schema/$name")))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
}
