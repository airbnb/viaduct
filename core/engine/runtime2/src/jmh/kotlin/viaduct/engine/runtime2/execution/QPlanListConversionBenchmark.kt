package viaduct.engine.runtime2.execution

import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.DataFetcherExceptionHandlerResult
import graphql.execution.ExecutionStepInfo
import graphql.execution.ResultPath
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.DataFetchingEnvironmentImpl
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.outputType
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.schema.ViaductAndGJSchema
import viaduct.graphql.schema.ViaductSchema

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = ["-Xms512m", "-Xmx512m", "-XX:+UseG1GC", "-Dkotlinx.coroutines.debug=off"])
open class QPlanListConversionBenchmark {
    @Param("0", "1", "10", "100", "1000")
    @JvmField
    var size: Int = 0

    @Param("SCALARS", "NESTED", "OBJECTS", "ERROR")
    @JvmField
    var shape: String = "SCALARS"

    private lateinit var fetcher: DataFetcher<*>
    private lateinit var environment: DataFetchingEnvironment
    private lateinit var requestScope: CoroutineScope

    @Setup(Level.Trial)
    @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE") // Keep the benchmark's request wrapper out of the public API.
    fun setup() {
        val fieldType = when (shape) {
            "NESTED" -> "[[String]]"
            "OBJECTS" -> "[Item]"
            else -> "[String]"
        }
        val sourceSchema = UnExecutableSchemaGenerator.makeUnExecutableSchema(
            SchemaParser().parse("type Query { values: $fieldType } type Item { name: String }"),
        )
        val schema = ViaductAndGJSchema.fromGraphQLSchema(sourceSchema).loweredSchema
        val field = schema.requireObjectField("Query", "values")
        val elementType = field.outputType.unwrapList()!!
        val item = ObjectEngineResult.of(schema.requireType("Item") as ViaductSchema.Object)
        val failure = ErrorEngineResult.of(EngineErrorData.of(IllegalStateException("element failed")))
        val elements = List(size) { index ->
            when (shape) {
                "NESTED" -> ListEngineResult.of(elementType.unwrapList()!!, listOf("first", null, "last"))
                "OBJECTS" -> if (index % 5 == 4) null else item
                "ERROR" -> if (index == size / 2) failure else "value"
                else -> if (index % 5 == 4) null else "value"
            }
        }
        val root = ObjectEngineResult.of(
            schema.requireQueryTypeDef(),
            mapOf(ObjectEngineResult.GroundKey.of(field, emptyMap()) to ListEngineResult.of(elementType, elements)),
        )
        // Run ready coroutine work inline to isolate conversion from dispatcher scheduling.
        requestScope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val handled = CompletableFuture.completedFuture(DataFetcherExceptionHandlerResult.newResult().build())
        val handler = DataFetcherExceptionHandler { handled }
        val executableSchema = QPlanWiringFactory(schema).wire(sourceSchema)
        val fieldDefinition = executableSchema.queryType.getFieldDefinition("values")
        fetcher = executableSchema.codeRegistry.getDataFetcher(executableSchema.queryType, fieldDefinition)
        environment = DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
            .source(QPlanExecutionSource(root, requestScope, handler))
            .arguments(emptyMap())
            .fieldDefinition(fieldDefinition)
            .executionStepInfo(
                ExecutionStepInfo.newExecutionStepInfo()
                    .type(fieldDefinition.type)
                    .path(ResultPath.rootPath().segment("aliased"))
                    .build(),
            )
            .build()
        check((convert() as List<*>).size == size)
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        requestScope.cancel()
    }

    // Exercise the installed production DataFetcher, without executing a GraphQL request.
    @Benchmark
    fun convert(): Any? =
        when (val result = fetcher.get(environment)) {
            is CompletableFuture<*> -> result.join()
            else -> result
        }
}
