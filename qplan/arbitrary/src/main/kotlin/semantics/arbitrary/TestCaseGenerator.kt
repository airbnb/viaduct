package semantics.arbitrary

import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.PropertyTesting
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import model.testing.TestWorld
import kotlin.random.Random

const val RESOLVER_TEST_SEED_PROPERTY = "resolver.property.seed"
const val RESOLVER_TEST_PROFILE_PROPERTY = "resolver.property.profile"
const val RESOLVER_TEST_CASE_PROPERTY = "resolver.property.case"
const val RESOLVER_TEST_SIZE_PROPERTY = "resolver.property.size"

data class ResolverTestCaseCoordinate(
    val schemaIndex: Int,
    val registryIndex: Int,
    val queryIndex: Int,
) {
    init {
        require(schemaIndex > 0)
        require(registryIndex > 0)
        require(queryIndex > 0)
    }

    fun summary(): String = "$schemaIndex:$registryIndex:$queryIndex"
}

/** Stable replay coordinates for one case in an `S x R x Q` generated product. */
data class ResolverTestCoordinates(
    val profile: String,
    val seed: Long,
    val schemaIndex: Int,
    val registryIndex: Int,
    val queryIndex: Int,
) {
    init {
        require(profile.isNotBlank())
        require(schemaIndex > 0)
        require(registryIndex > 0)
        require(queryIndex > 0)
    }

    fun summary(): String =
        "profile=$profile seed=$seed S=$schemaIndex R=$registryIndex Q=$queryIndex"

    fun replaySeedArgument(): String = "-PresolverPropertySeed=$seed"
}

data class ResolverTestCase(
    val schema: ArbitrarySchema,
    val registry: ArbitraryRegistry,
    val query: ArbitraryQuery,
    val coordinates: ResolverTestCoordinates? = null,
) {
    fun failureContext(): String =
        """
        |Generated resolver test case
        |Coordinates: ${coordinates?.summary() ?: "not tracked"}
        |Replay seed: ${coordinates?.replaySeedArgument() ?: "not tracked"}
        |
        |Schema:
        |${schema.sdl.prependIndent("  ")}
        |
        |Registry:
        |${registry.toString().prependIndent("  ")}
        |
        |Query:
        |${query.source.prependIndent("  ")}
        """.trimMargin()
}

/** Coordinates and execution count shared by aggregate assertions after one generated run. */
data class ResolverTestRun(
    val profile: String,
    val seed: Long,
    val attemptedCases: Int,
    val counts: TestCaseCount,
    val selectedCase: ResolverTestCaseCoordinate?,
    val sizeOverridden: Boolean,
) {
    init {
        require(profile.isNotBlank())
        require(attemptedCases > 0)
    }

    val expectedCases: Int
        get() =
            if (selectedCase == null) {
                counts.schemas * counts.registriesPerSchema * counts.queriesPerSchema
            } else {
                1
            }

    fun assertAggregate(
        condition: Boolean,
        message: String,
    ) {
        if (selectedCase != null) return
        if (!condition) {
            throw AssertionError(
                """
                |$message
                |Coordinates: profile=$profile seed=$seed S=all R=all Q=all
                |Size: ${counts.summary()}
                |Replay seed: -PresolverPropertySeed=$seed
                """.trimMargin(),
            )
        }
    }
}

data class ResolverTestBatch(
    val schema: ArbitrarySchema,
    val registries: List<ArbitraryRegistry>,
    val queries: List<ArbitraryQuery>,
) {
    val cases: Sequence<ResolverTestCase>
        get() =
            registries.asSequence().flatMap { registry ->
                queries.asSequence().map { query ->
                    ResolverTestCase(schema, registry, query)
                }
            }
}

/**
 * Generates one schema together with independent registry and query samples for its full product.
 *
 * Kotest's outer property iteration count is `S`; [counts] supplies `R` and `Q`.
 */
fun Arb.Companion.resolverTestBatch(
    counts: TestCaseCount,
    config: Config = Config.default,
): Arb<ResolverTestBatch> =
    Arb.schema(config).flatMap { schema ->
        Arb.bind(
            Arb.list(
                schema.registry(config),
                counts.registriesPerSchema..counts.registriesPerSchema,
            ),
            Arb.list(
                schema.query(config),
                counts.queriesPerSchema..counts.queriesPerSchema,
            ),
        ) { registries, queries ->
            ResolverTestBatch(schema, registries, queries)
        }
    }

/**
 * Runs the full `S x R x Q` product while reusing one assembled world per generated registry.
 *
 * Every invocation chooses an explicit seed and returns it in [ResolverTestRun]. A failure inside
 * [property] includes the seed plus one-based `S`, `R`, and `Q` coordinates.
 *
 * A callback that performs more than one independent resolution must create a fresh semantic
 * operation context for each resolution so request-local bindings are not shared.
 */
@OptIn(ExperimentalKotest::class)
suspend fun checkResolverTestCases(
    counts: TestCaseCount,
    config: Config = Config.default,
    profile: String = "resolver-generated",
    seed: Long? = null,
    fieldCheckerMode: GeneratedFieldCheckerMode = GeneratedFieldCheckerMode.NONE,
    property: suspend (TestWorld, ResolverTestCase) -> Unit,
): ResolverTestRun {
    require(profile.isNotBlank())
    val runSeed = seed ?: configuredResolverTestSeed()
    val execution = configuredResolverTestExecution(counts, profile)
    return executeResolverTestCases(
        execution = execution,
        config = config,
        profile = profile,
        seed = runSeed,
        fieldCheckerMode = fieldCheckerMode,
        property = property,
    )
}

/**
 * Executes an explicitly configured generated product without consulting process properties.
 *
 * Standalone launchers use this entry point. JUnit adapters may construct [execution] from their
 * own annotations, Gradle properties, or [configuredResolverTestExecution].
 */
@OptIn(ExperimentalKotest::class)
suspend fun executeResolverTestCases(
    execution: ResolverTestExecution,
    config: Config = Config.default,
    profile: String = "resolver-generated",
    seed: Long,
    fieldCheckerMode: GeneratedFieldCheckerMode = GeneratedFieldCheckerMode.NONE,
    property: suspend (TestWorld, ResolverTestCase) -> Unit,
): ResolverTestRun {
    require(profile.isNotBlank())
    var attemptedCases = 0
    var failingSchemaIndex: Int? = null
    checkAll(
        PropTestConfig(
            seed = seed,
            iterations = execution.schemaIterations,
        ),
        Arb.resolverTestBatch(execution.counts, config),
    ) { batch ->
        val schemaIndex = failingSchemaIndex ?: successes() + 1
        if (
            execution.selectedCase != null &&
            schemaIndex != execution.selectedCase.schemaIndex
        ) {
            return@checkAll
        }
        batch.registries.forEachIndexed { registryOffset, registry ->
            val registryIndex = registryOffset + 1
            if (
                execution.selectedCase != null &&
                registryIndex != execution.selectedCase.registryIndex
            ) {
                return@forEachIndexed
            }
            val world =
                registry.world(
                    schema = batch.schema,
                    selectiveNodeResolvers = config[SelectiveNodeResolversEnabled],
                    fieldCheckerMode = fieldCheckerMode,
                )
            batch.queries.forEachIndexed query@ { queryOffset, query ->
                val queryIndex = queryOffset + 1
                if (
                    execution.selectedCase != null &&
                    queryIndex != execution.selectedCase.queryIndex
                ) {
                    return@query
                }
                attemptedCases += 1
                val testCase =
                    ResolverTestCase(
                        schema = batch.schema,
                        registry = registry,
                        query = query,
                        coordinates =
                            ResolverTestCoordinates(
                                profile = profile,
                                seed = seed,
                                schemaIndex = schemaIndex,
                                registryIndex = registryIndex,
                                queryIndex = queryIndex,
                            ),
                    )
                try {
                    property(world, testCase)
                } catch (failure: Throwable) {
                    failingSchemaIndex = schemaIndex
                    val counts = execution.counts
                    val size = "${counts.schemas}:${counts.registriesPerSchema}:${counts.queriesPerSchema}"
                    throw AssertionError("${testCase.failureContext()}\nReplay size: -PresolverPropertySize=$size", failure)
                }
            }
        }
    }
    return ResolverTestRun(
        profile = profile,
        seed = seed,
        attemptedCases = attemptedCases,
        counts = execution.counts,
        selectedCase = execution.selectedCase,
        sizeOverridden = execution.sizeOverridden,
    )
}

fun parseResolverTestCase(value: String): ResolverTestCaseCoordinate =
    parseResolverTestDimensions(value, RESOLVER_TEST_CASE_PROPERTY).let { dimensions ->
        ResolverTestCaseCoordinate(
            schemaIndex = dimensions.first,
            registryIndex = dimensions.second,
            queryIndex = dimensions.third,
        )
    }

internal fun parseResolverTestSize(value: String): TestCaseCount =
    parseResolverTestDimensions(value, RESOLVER_TEST_SIZE_PROPERTY).let { dimensions ->
        TestCaseCount(
            schemas = dimensions.first,
            registriesPerSchema = dimensions.second,
            queriesPerSchema = dimensions.third,
        )
    }

data class ResolverTestExecution(
    val counts: TestCaseCount,
    val selectedCase: ResolverTestCaseCoordinate? = null,
    val sizeOverridden: Boolean = false,
) {
    init {
        selectedCase?.let { selected ->
            require(selected.schemaIndex <= counts.schemas) {
                "Selected schema ${selected.schemaIndex} exceeds profile size ${counts.summary()}"
            }
            require(selected.registryIndex <= counts.registriesPerSchema) {
                "Selected registry ${selected.registryIndex} exceeds profile size ${counts.summary()}"
            }
            require(selected.queryIndex <= counts.queriesPerSchema) {
                "Selected query ${selected.queryIndex} exceeds profile size ${counts.summary()}"
            }
        }
    }

    val schemaIterations: Int
        get() = selectedCase?.schemaIndex ?: counts.schemas
}

fun configuredResolverTestExecution(
    defaultCounts: TestCaseCount,
    profile: String,
): ResolverTestExecution {
    System.getProperty(RESOLVER_TEST_PROFILE_PROPERTY)?.let { configuredProfile ->
        require(configuredProfile == profile) {
            "Configured resolver property profile $configuredProfile does not match $profile"
        }
    }
    val configuredCase = System.getProperty(RESOLVER_TEST_CASE_PROPERTY)
    val configuredSize = System.getProperty(RESOLVER_TEST_SIZE_PROPERTY)
    val selectedCase =
        configuredCase
            ?.takeUnless { configured -> configured.equals("all", ignoreCase = true) }
            ?.let(::parseResolverTestCase)
    return ResolverTestExecution(
        counts = configuredSize?.let(::parseResolverTestSize) ?: defaultCounts,
        selectedCase = selectedCase,
        sizeOverridden = configuredSize != null,
    )
}

private fun parseResolverTestDimensions(
    value: String,
    property: String,
): Triple<Int, Int, Int> {
    val parts = value.split(':')
    require(parts.size == 3) {
        "$property must have S:R:Q form with positive integers: $value"
    }
    val dimensions =
        parts.map { part ->
            part.toIntOrNull()
                ?.takeIf { dimension -> dimension > 0 }
                ?: throw IllegalArgumentException(
                    "$property must have S:R:Q form with positive integers: $value",
                )
        }
    return Triple(dimensions[0], dimensions[1], dimensions[2])
}

private fun configuredResolverTestSeed(): Long =
    System
        .getProperty(RESOLVER_TEST_SEED_PROPERTY)
        ?.let { configured ->
            configured.toLongOrNull()
                ?: throw IllegalArgumentException(
                    "$RESOLVER_TEST_SEED_PROPERTY must be a Long: $configured",
                )
        }
        ?: PropertyTesting.defaultSeed
        ?: Random.nextLong()

private fun TestCaseCount.summary(): String =
    "$schemas:$registriesPerSchema:$queriesPerSchema"
