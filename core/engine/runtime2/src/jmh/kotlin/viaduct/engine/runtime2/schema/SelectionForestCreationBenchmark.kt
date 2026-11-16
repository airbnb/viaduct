package viaduct.engine.runtime2.schema

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.execution.RawVariables
import graphql.execution.ValuesResolver
import graphql.language.FragmentDefinition
import graphql.language.OperationDefinition
import graphql.parser.Parser
import graphql.schema.GraphQLSchema
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
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.spec.SpecSelection
import viaduct.engine.runtime2.model.spec.flattenForMaterialization
import viaduct.graphql.schema.ViaductSchema

/** Times the four stages of the current runtime2 operation-selection preparation path. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 30)
@Measurement(iterations = 3, time = 60)
@Fork(value = 2, jvmArgsAppend = ["-Xms2g", "-Xmx8g", "-XX:+UseG1GC"])
open class SelectionForestCreationBenchmark {
    @Param("schema2-small", "schema4-small", "schema4-full")
    @JvmField
    var fixture: String = "schema2-small"

    private lateinit var parser: AstSelectionParser
    private lateinit var operation: OperationDefinition
    private lateinit var variables: CoercedVariables
    private lateinit var graphQLContext: GraphQLContext
    private lateinit var fragmentsByName: Map<String, FragmentDefinition>
    private lateinit var decoded: List<SpecSelection>
    private lateinit var materialize: SelectionForest
    private lateinit var loweredSchema: ViaductSchema
    private lateinit var queryType: ViaductSchema.Object

    @Setup(Level.Trial)
    fun setup() {
        val spec = when (fixture) {
            "schema2-small" -> Fixture("large-schema-2.graphqls.gz", "large-schema-2-query.graphql")
            "schema4-small" -> Fixture("large-schema-4.graphqls.gz", "large-schema-4-small-query.graphql")
            "schema4-full" -> Fixture("large-schema-4.graphqls.gz", "large-schema-4-query.graphql.gz")
            else -> error("Unknown fixture: $fixture")
        }
        val schemaText = resourceText(spec.schema)
        val queryText = resourceText(spec.query)
        val sourceSchema: GraphQLSchema =
            UnExecutableSchemaGenerator.makeUnExecutableSchema(SchemaParser().parse(schemaText))
        val document = Parser.parse(queryText)
        // This upstream stress query intentionally exceeds graphql-java's default 100K field limit.
        val validationErrors = Validator().validateDocument(
            sourceSchema,
            document,
            { true },
            Locale.ENGLISH,
            QueryComplexityLimits.NONE,
        )
        check(validationErrors.isEmpty()) {
            "Invalid $fixture fixture: ${validationErrors.take(3).joinToString { it.message }}"
        }
        operation = document.getDefinitionsOfType(OperationDefinition::class.java).single()
        fragmentsByName = document.getDefinitionsOfType(FragmentDefinition::class.java).associateBy { it.name }
        graphQLContext = GraphQLContext.getDefault()
        variables = ValuesResolver.coerceVariableValues(
            sourceSchema,
            operation.variableDefinitions,
            RawVariables.of(emptyMap<String, Any>()),
            graphQLContext,
            Locale.ENGLISH,
        )
        loweredSchema = ViaductAndGJSchema.fromGraphQLSchema(sourceSchema).loweredSchema
        queryType = loweredSchema.requireQueryTypeDef()
        parser = AstSelectionParser(sourceSchema, loweredSchema, emptyMap())
        decoded = decode()
        materialize = flattenForMaterialization(loweredSchema, queryType, decoded)
        check(!materialize.isEmpty()) { "$fixture produced an empty materialization forest" }
        check(!materialize.isEmpty()) { "$fixture produced an empty construction forest" }
        check(!createSelectionForest().isEmpty()) { "$fixture produced an empty operation forest" }
    }

    /** Traverses the already-validated AST, including named fragment expansion and argument coercion. */
    @Benchmark
    fun decodeOperation(): List<SpecSelection> = decode()

    /** Flattens prepared selections, retaining response keys and concrete type applicability. */
    @Benchmark
    fun createMaterializeForest(): SelectionForest = flattenForMaterialization(loweredSchema, queryType, decoded)

    /** Recursively copies a prepared materialization forest into alias-free construction demand. */
    @Benchmark
    fun createConstructionForest(): SelectionForest = materialize

    /** The current full Viaduct conversion from a validated operation to SelectionForest. */
    @Benchmark
    fun createSelectionForest(): SelectionForest = parser.selectionsFrom(operation, variables, graphQLContext, Locale.ENGLISH, fragmentsByName)

    private fun decode(): List<SpecSelection> = parser.decodeOperationSelections(operation, variables, graphQLContext, Locale.ENGLISH, fragmentsByName)

    private data class Fixture(val schema: String, val query: String)

    private fun resourceText(name: String): String {
        val stream = requireNotNull(javaClass.getResourceAsStream("/viaduct/engine/runtime2/schema/$name")) {
            "Missing benchmark resource: $name"
        }
        val decoded = if (name.endsWith(".gz")) GZIPInputStream(stream) else stream
        return decoded.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
