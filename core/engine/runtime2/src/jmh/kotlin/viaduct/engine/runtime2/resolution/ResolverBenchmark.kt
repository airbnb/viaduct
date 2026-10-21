package viaduct.engine.runtime2.resolution

import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import jdk.jfr.Configuration
import jdk.jfr.Recording
import kotlinx.coroutines.ExecutorCoroutineDispatcher
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
import org.openjdk.jmh.annotations.Timeout
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.BenchmarkParams
import org.openjdk.jmh.infra.Blackhole
import org.openjdk.jmh.infra.IterationParams
import org.openjdk.jmh.runner.IterationType
import viaduct.engine.runtime2.benchmark.CurrentProfileBenchmarkSupport
import viaduct.engine.runtime2.benchmark.DEFAULT_OVERHEAD_LOOP_COUNT
import viaduct.engine.runtime2.benchmark.ResolverBenchmarkSubject

private const val PROFILE_OUTPUT_PROPERTY = "resolutionOverheadProfileOutput"
private const val PROFILE_RECORDING_NAME = "resolution-overhead-measurement"

@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
@Fork(1)
@Timeout(time = 10, timeUnit = TimeUnit.MINUTES)
open class ResolverBenchmark {
    @JvmField
    @Param("$DEFAULT_OVERHEAD_LOOP_COUNT")
    var loopCount: Int = DEFAULT_OVERHEAD_LOOP_COUNT

    @JvmField
    @Param("false", "true")
    var precomputedConstructionDemand: Boolean = false

    private lateinit var resolverDispatcher: ExecutorCoroutineDispatcher

    private val support =
        CurrentProfileBenchmarkSupport(
            usePrecomputedConstructionDemand = { precomputedConstructionDemand },
            subject = ResolverBenchmarkSubject { operation, _, selections ->
                operation.resolve(selections, resolverDispatcher)
            },
        )

    private var profileRecording: Recording? = null
    private val allocationBean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    private val runtimeAllocations = mutableListOf<Long>()
    private var allocationStart: Long? = null

    @Setup(Level.Trial)
    fun prepareTrial() {
        resolverDispatcher =
            ResolutionDispatcherFactory.create(configuredResolutionThreadCount())
    }

    @Setup(Level.Invocation)
    fun prepareOverheadInvocation(
        parameters: BenchmarkParams,
        iterationParams: IterationParams,
    ) {
        if (parameters.benchmark.endsWith(".overhead")) {
            support.prepareOverheadInvocation(loopCount)
            startOverheadProfile(iterationParams)
            if (iterationParams.type == IterationType.MEASUREMENT) {
                allocationStart = allocationBean.totalThreadAllocatedBytes
            }
        }
    }

    @TearDown(Level.Invocation)
    fun stopOverheadProfile() {
        allocationStart?.let { start ->
            runtimeAllocations += allocationBean.totalThreadAllocatedBytes - start
            allocationStart = null
        }
        profileRecording?.let { recording ->
            profileRecording = null
            recording.stop()
            recording.close()
        }
    }

    @TearDown(Level.Trial)
    fun reportOverheadStatistics(parameters: BenchmarkParams) {
        try {
            if (parameters.benchmark.endsWith(".overhead")) {
                support.reportOverheadStatistics()
                if (runtimeAllocations.isNotEmpty()) {
                    println("Runtime allocated bytes per batch: $runtimeAllocations")
                }
            }
        } finally {
            resolverDispatcher.close()
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Warmup(iterations = 3, time = 1)
    @Measurement(iterations = 5, time = 1)
    fun registryStartup(): Any = support.registryStartup()

    @Benchmark
    fun full(): Int = support.full()

    @Benchmark
    fun overhead(blackhole: Blackhole): Int = support.overhead(blackhole)

    private fun startOverheadProfile(iterationParams: IterationParams) {
        val output = System.getProperty(PROFILE_OUTPUT_PROPERTY) ?: return
        if (iterationParams.type != IterationType.MEASUREMENT) {
            return
        }

        val destination = Path.of(output).toAbsolutePath()
        destination.parent?.let(Files::createDirectories)
        Files.deleteIfExists(destination)
        profileRecording =
            Recording(Configuration.getConfiguration("profile")).apply {
                name = PROFILE_RECORDING_NAME
                setDestination(destination)
                start()
            }
    }
}
