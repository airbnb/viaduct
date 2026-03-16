@file:Suppress("ForbiddenImport", "FunctionNaming")

package viaduct.api.internal

import graphql.schema.GraphQLObjectType
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.MockType
import viaduct.api.reflect.Type
import viaduct.api.types.NodeObject
import viaduct.apiannotations.InternalApi
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.mocks.createEngineSelectionSet
import viaduct.engine.api.mocks.createSchema
import viaduct.engine.api.select.SelectionsParser

/**
 * JMH benchmark for Conv building with and without selection sets.
 *
 * Demonstrates the pathological cost of [DefaultGRTConvFactory] and [EngineValueConv] when
 * `selectionSet == null` on a large schema. This is the root cause of the
 * production OOM in ExploreActivityListingCustomTypeResolver, where
 * `GRTDomain.forType()` triggers unbounded Conv building.
 *
 * Run with: bazel run //projects/viaduct/oss/tenant/api/src/jmh/kotlin/viaduct/api/internal:conv_build_benchmark
 */
@OptIn(InternalApi::class)
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 0, jvmArgs = ["-Xmx4g", "-Xms1g"])
@Warmup(iterations = 1)
@Measurement(iterations = 3)
open class ConvBuildBenchmark {
    private lateinit var schema: ViaductSchema
    private lateinit var internalCtx: InternalContext
    private lateinit var rootType: GraphQLObjectType
    private lateinit var interiorType: GraphQLObjectType
    private lateinit var rootBoundedSelectionSet: EngineSelectionSet

    @Setup
    fun setup() {
        val sdl = ConvBuildBenchmark::class.java
            .getResourceAsStream("/schemas/extra-large-3-schema.graphqls")!!
            .bufferedReader()
            .use { it.readText() }
        schema = createSchema(sdl)

        val lenientReflectionLoader = object : ReflectionLoader {
            override fun reflectionFor(name: String): Type<*> = MockType.mkNodeObject(name)

            override fun getGRTKClassFor(name: String): KClass<*> = NodeObject::class
        }

        internalCtx = MockInternalContext(schema, reflectionLoader = lenientReflectionLoader)

        rootType = schema.schema.queryType

        interiorType = schema.schema.allTypesAsList
            .filterIsInstance<graphql.schema.GraphQLObjectType>()
            .filter { it != schema.schema.queryType && it != schema.schema.mutationType }
            .maxByOrNull { it.fieldDefinitions.size }!!

        val scalarField = rootType.fieldDefinitions.first { fd ->
            graphql.schema.GraphQLTypeUtil.unwrapAll(fd.type) is graphql.schema.GraphQLScalarType
        }
        rootBoundedSelectionSet = createEngineSelectionSet(
            SelectionsParser.parse(rootType.name, scalarField.name),
            schema,
            emptyMap()
        )

        println(
            "Schema: ${schema.schema.allTypesAsList.size} types, " +
                "root: ${rootType.name} (${rootType.fieldDefinitions.size} fields), " +
                "interior: ${interiorType.name} (${interiorType.fieldDefinitions.size} fields)"
        )
    }

    // === DefaultGRTConvFactory benchmarks (production code path via GRTDomain.forType()) ===

    @Benchmark
    fun grtConv_root_unbounded(blackhole: Blackhole) {
        blackhole.consume(
            DefaultGRTConvFactory.create(internalCtx, rootType, null, KeyMapping.FieldNameToFieldName)
        )
    }

    @Benchmark
    fun grtConv_root_bounded(blackhole: Blackhole) {
        blackhole.consume(
            DefaultGRTConvFactory.create(internalCtx, rootType, rootBoundedSelectionSet, KeyMapping.SelectionToSelection)
        )
    }

    @Benchmark
    fun grtConv_interior_unbounded(blackhole: Blackhole) {
        blackhole.consume(
            DefaultGRTConvFactory.create(internalCtx, interiorType, null, KeyMapping.FieldNameToFieldName)
        )
    }

    // === EngineValueConv benchmarks (for comparison) ===

    @Benchmark
    fun engineValueConv_root_unbounded(blackhole: Blackhole) {
        blackhole.consume(
            EngineValueConv(schema, rootType, null)
        )
    }

    @Benchmark
    fun engineValueConv_root_bounded(blackhole: Blackhole) {
        blackhole.consume(
            EngineValueConv(schema, rootType, rootBoundedSelectionSet)
        )
    }
}
