package semantics.resolver26

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
import org.openjdk.jmh.infra.Blackhole
import org.openjdk.jmh.infra.IterationParams
import org.openjdk.jmh.runner.IterationType
import semantics.benchmark.DEFAULT_PROPERTY_TEST_LOOP_COUNT
import semantics.benchmark.PropertyTestBenchmarkSupport
import semantics.benchmark.ResolverBenchmarkSubject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import jdk.jfr.Configuration
import jdk.jfr.Recording
import kotlinx.coroutines.ExecutorCoroutineDispatcher

private const val PROFILE_OUTPUT_PROPERTY = "propertyTestProfileOutput"
private const val PROFILE_RECORDING_NAME = "property-test-measurement"

@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@Fork(1)
@Timeout(time = 10, timeUnit = TimeUnit.MINUTES)
open class PropertyTestBenchmark {
    @JvmField
    @Param("$DEFAULT_PROPERTY_TEST_LOOP_COUNT")
    var loopCount: Int = DEFAULT_PROPERTY_TEST_LOOP_COUNT

    private lateinit var resolverDispatcher: ExecutorCoroutineDispatcher

    private val support =
        PropertyTestBenchmarkSupport(
            subject = ResolverBenchmarkSubject { operation, _, selections ->
                operation.resolve(selections, resolverDispatcher)
            },
        )

    private var profileRecording: Recording? = null

    @Setup(Level.Trial)
    @Suppress("TooGenericExceptionCaught") // Release the dispatcher even if setup throws an Error.
    fun prepareTrial() {
        val dispatcher =
            ResolutionDispatcherFactory.create(configuredResolutionThreadCount())
        resolverDispatcher = dispatcher
        try {
            support.prepareTrial()
        } catch (throwable: Throwable) {
            dispatcher.close()
            throw throwable
        }
    }

    @TearDown(Level.Trial)
    fun closeTrial() {
        resolverDispatcher.close()
    }

    @Setup(Level.Iteration)
    fun startMeasurementProfile(iterationParams: IterationParams) {
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

    @TearDown(Level.Iteration)
    fun stopMeasurementProfile() {
        profileRecording?.let { recording ->
            profileRecording = null
            recording.stop()
            recording.close()
        }
    }

    @Benchmark
    fun propertyTest(blackhole: Blackhole): Int =
        support.propertyTest(
            loopCount = loopCount,
            blackhole = blackhole,
            profilePhases = profileRecording != null,
        )
}
