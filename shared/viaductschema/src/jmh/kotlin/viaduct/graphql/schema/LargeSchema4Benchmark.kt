package viaduct.graphql.schema

import graphql.schema.GraphQLSchema
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import viaduct.graphql.schema.binary.readBSchema
import viaduct.graphql.schema.graphqljava.GJSchemaRaw
import viaduct.graphql.schema.graphqljava.toGraphQLSchema

/**
 * Benchmarks for large-schema-4.
 *
 * Uses `large-schema-4.graphqls.zip` which has 50,588 unique string
 * constants (stringValue1, stringValue2, ..., stringValue50588) used as
 * directive argument default values.
 *
 * Note: Schema is stored as a zipped file to reduce repository size.
 * It is automatically unzipped to a temporary file during benchmark setup.
 *
 * See also: [LargeSchema5Benchmark] for the anonymized central schema benchmarks.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
open class LargeSchema4Benchmark {
    private var schemaFound: Boolean = false
    private var benchmarkData: LargeSchemaBenchmarkData? = null

    @Setup
    fun setup() {
        val schema = LargeSchemaBenchmarkHelper.loadZippedSchema("4")
        if (schema == null) {
            println("large-schema-4 not found in classpath. Skipping benchmarks.")
            schemaFound = false
            return
        }

        schemaFound = true
        benchmarkData = LargeSchemaBenchmarkHelper.prepareBenchmarkData(schema, "large-schema-4")
    }

    @TearDown
    fun tearDown() {
        benchmarkData?.cleanup()
    }

    @Benchmark
    fun readLargeSchema4TextSDLToGJSchemaRaw(bh: Blackhole) {
        val data = benchmarkData ?: return
        val rawSchema = GJSchemaRaw.fromFiles(listOf(data.textSchemaFile))
        bh.consume(rawSchema)
    }

    @Benchmark
    fun readLargeSchema4SDLTextToGraphQLSchema(bh: Blackhole) {
        val data = benchmarkData ?: return
        val registry = SchemaParser().parse(data.schemaSdl)
        val schema: GraphQLSchema = UnExecutableSchemaGenerator.makeUnExecutableSchema(registry)
        bh.consume(schema)
    }

    @Benchmark
    fun readLargeSchema4BinToViaductSchema(bh: Blackhole) {
        val data = benchmarkData ?: return
        val schema: ViaductSchema = readBSchema(ByteArrayInputStream(data.binarySchemaBytes))
        bh.consume(schema)
    }

    @Benchmark
    fun readLargeSchema4BinToGraphQLSchema(bh: Blackhole) {
        val data = benchmarkData ?: return
        val viaductSchema: ViaductSchema = readBSchema(ByteArrayInputStream(data.binarySchemaBytes))
        val scalarsNeeded = viaductSchema.types.values
            .filterIsInstance<ViaductSchema.Scalar>()
            .filter { it.name !in setOf("String", "Boolean") }
            .map { it.name }
            .toSet()
        val schema: GraphQLSchema = viaductSchema.toGraphQLSchema(scalarsNeeded = scalarsNeeded)
        bh.consume(schema)
    }
}
