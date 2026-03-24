package viaduct.engine.runtime.execution

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
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
import org.openjdk.jmh.infra.Blackhole
import viaduct.deferred.completedDeferred
import viaduct.deferred.thenApply
import viaduct.engine.runtime.Value

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1, jvmArgs = ["-XX:+UseG1GC", "-Xms2g", "-Xmx2g"])
@Warmup(iterations = 3, batchSize = 1)
@Measurement(iterations = 3, batchSize = 1)
open class ValueBenchmark {
    enum class Type { Value, Deferred }

    @Param("Value", "Deferred")
    private lateinit var type: Type

    private lateinit var pendingDeferred: Deferred<Int>
    private lateinit var completedDeferred: Deferred<Int>
    private lateinit var pendingValue: Value<Int>
    private lateinit var completedValue: Value<Int>

    // map-ing a deferred modifies the base object to append a child job, which if not cleaned up
    // between invocations will create GC pressure, cause OOMs, and just generally distort this benchmark
    // Ensure that we run this method often-enough that it doesn't distort our measurement.
    @Setup(Level.Iteration)
    fun initState() {
        pendingDeferred = CompletableDeferred()
        completedDeferred = completedDeferred(1)
        pendingValue = Value.fromDeferred(CompletableDeferred())
        completedValue = Value.fromDeferred(completedDeferred(1))
    }

    @Benchmark
    fun `map_pending_deferred`(blackhole: Blackhole) {
        if (type == Type.Deferred) {
            val result = pendingDeferred.thenApply { it + 1 }
            blackhole.consume(result)
        }
    }

    @Benchmark
    fun `map_pending_value`(blackhole: Blackhole) {
        if (type == Type.Value) {
            val result = pendingValue.map { it + 1 }
            blackhole.consume(result)
        }
    }

    @Benchmark
    fun `map_complete_deferred`(blackhole: Blackhole) {
        if (type == Type.Deferred) {
            val result = completedDeferred.thenApply { it + 1 }
            blackhole.consume(result)
        }
    }

    @Benchmark
    fun `map_complete_value`(blackhole: Blackhole) {
        if (type == Type.Value) {
            val result = completedValue.map { it + 1 }
            blackhole.consume(result)
        }
    }
}
