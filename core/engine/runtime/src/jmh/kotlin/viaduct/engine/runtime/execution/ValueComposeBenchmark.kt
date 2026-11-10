package viaduct.engine.runtime.execution

import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import viaduct.deferred.RequestParentJobContextElement
import viaduct.engine.runtime.Value

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3, jvmArgsAppend = ["-XX:+UseG1GC", "-Xms2g", "-Xmx2g"])
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
open class ValueComposeBenchmark {
    @Param("sync", "async")
    lateinit var outcome: String

    private lateinit var parent: Job
    private lateinit var context: RequestParentJobContextElement

    @Setup
    fun setup() {
        parent = SupervisorJob()
        context = RequestParentJobContextElement(parent)
    }

    @TearDown
    fun cleanup() {
        try {
            check(!parent.children.any())
        } finally {
            parent.cancel()
        }
    }

    @Benchmark
    fun compose(): Int {
        val previous = context.updateThreadContext(EmptyCoroutineContext)
        return try {
            val source = CompletableDeferred<Int>()
            val input = Value.fromDeferred(source)
            val inner = if (outcome == "async") CompletableDeferred<Int>() else null
            val output = input.thenCompose { value, _ ->
                if (inner == null) Value.fromValue(value!! + 1) else Value.fromDeferred(inner)
            }
            source.complete(1)
            inner?.complete(2)
            output.getCompleted()
        } finally {
            context.restoreThreadContext(EmptyCoroutineContext, previous)
        }
    }
}
