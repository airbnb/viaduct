package viaduct.graphql.schema

import graphql.schema.GraphQLSchema
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.SchemaPrinter
import graphql.schema.idl.UnExecutableSchemaGenerator
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.next
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
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
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.graphql.SchemaSize
import viaduct.arbitrary.graphql.graphQLSchema
import viaduct.graphql.schema.binary.readBSchema
import viaduct.graphql.schema.binary.writeBSchema
import viaduct.graphql.schema.graphqljava.GJSchemaRaw
import viaduct.graphql.schema.graphqljava.toGraphQLSchema

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
open class ViaductSchemaBenchmark {
    private val schemaSize = (System.getenv()["BENCHMARK_SCHEMA_SIZE"] ?: "1000").toInt()

    private lateinit var textSchemaFile: File
    private lateinit var binarySchemaFile: File
    private lateinit var schemaSdl: String
    private lateinit var binarySchemaBytes: ByteArray

    @Setup
    fun setup() {
        // Generate a test schema using Arb with configurable size
        val cfg = Config.default + (SchemaSize to schemaSize)
        val rs = RandomSource.seeded(42L) // Use fixed seed for reproducibility
        val graphQLSchema = Arb.graphQLSchema(cfg).next(rs)

        // Print the schema to SDL text
        schemaSdl = SchemaPrinter().print(graphQLSchema)

        // Create temporary files
        textSchemaFile = File.createTempFile("schema", ".graphqls")
        binarySchemaFile = File.createTempFile("schema", ".bschema")

        // Write text schema to file
        textSchemaFile.writeText(schemaSdl)

        // Parse the text schema to get a ViaductSchema, then write binary version
        val viaductSchema = GJSchemaRaw.fromFiles(listOf(textSchemaFile))
        val binaryOut = ByteArrayOutputStream()
        writeBSchema(viaductSchema, binaryOut)
        binarySchemaBytes = binaryOut.toByteArray()
        binarySchemaFile.writeBytes(binarySchemaBytes)

        // Also write to fixed locations for analysis
        File("/tmp/test-schema.graphqls").writeText(schemaSdl)
        File("/tmp/test-schema.bschema").writeBytes(binarySchemaBytes)

        println("Setup complete (schemaSize=$schemaSize):")
        println("  Text schema size: ${schemaSdl.length} bytes")
        println("  Binary schema size: ${binarySchemaBytes.size} bytes")
        println("  Compression ratio: ${"%.2f".format(binarySchemaBytes.size.toDouble() / schemaSdl.length)}")
        println("  Files written to: /tmp/test-schema.graphqls and /tmp/test-schema.bschema")
    }

    @TearDown
    fun tearDown() {
        textSchemaFile.delete()
        binarySchemaFile.delete()
    }

    @Benchmark
    fun readTestSchemaTextSDLToGJSchemaRaw(bh: Blackhole) {
        val rawSchema = GJSchemaRaw.fromFiles(listOf(textSchemaFile))
        bh.consume(rawSchema)
    }

    @Benchmark
    fun readTestSchemaSDLTextToGraphQLSchema(bh: Blackhole) {
        val registry = SchemaParser().parse(schemaSdl)
        val schema: GraphQLSchema = UnExecutableSchemaGenerator.makeUnExecutableSchema(registry)
        bh.consume(schema)
    }

    @Benchmark
    fun readTestSchemaBinSDLToViaductSchema(bh: Blackhole) {
        val schema: ViaductSchema = readBSchema(ByteArrayInputStream(binarySchemaBytes))
        bh.consume(schema)
    }

    /**
     * Benchmark reading a binary schema, constructing a ViaductSchema, then converting
     * to a graphql-java GraphQLSchema. This measures the full round-trip from binary
     * to a usable GraphQLSchema.
     * Measures both time and memory consumption (when run with -prof gc).
     */
    @Benchmark
    fun readBinarySchemaToGraphQLSchema(bh: Blackhole) {
        val viaductSchema: ViaductSchema = readBSchema(ByteArrayInputStream(binarySchemaBytes))
        // Compute scalars needed (excluding String and Boolean which are always added)
        val scalarsNeeded = viaductSchema.types.values
            .filterIsInstance<ViaductSchema.Scalar>()
            .filter { it.name !in setOf("String", "Boolean") }
            .map { it.name }
            .toSet()
        val schema: GraphQLSchema = viaductSchema.toGraphQLSchema(scalarsNeeded = scalarsNeeded)
        bh.consume(schema)
    }
}
