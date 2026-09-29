package semantics.resolver26.inclusion

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import model.emptyFragmentOf
import model.fragmentFrom
import model.requireObjectField
import model.testing.TestWorld
import model.testing.fieldResolverOf

internal fun seedAndT3World(vector: T3Vector): SeedAndT3Fixture {
    val seedApplications = AtomicInteger()
    val providerApplications = AtomicInteger()
    val t3InputAliases = AtomicReference<Set<String>>()
    val world =
        TestWorld.fromSDL(
            schemaSDL =
                """
                type Query {
                  seed: Int!
                  t3: InclusionTester!
                }

                type InclusionTester {
                  i: Int!
                  n: InclusionTester
                }
                """.trimIndent(),
            fieldResolvers = { schema ->
                val seed = schema.requireObjectField("Query", "seed")
                val t3 = schema.requireObjectField("Query", "t3")
                val t3Input = schema.fragmentFrom(T3_INPUT_FRAGMENT, variableField = t3)

                mapOf(
                    seed to
                        fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                            seedApplications.incrementAndGet()
                            SEED_VALUE
                        },
                    t3 to
                        fieldResolverOf(t3Input) { input, _ ->
                            val aliases = input.getSelections().toSet()
                            t3InputAliases.set(aliases)
                            var inputFingerprint = 0
                            if (input.isPresent("left")) {
                                inputFingerprint =
                                    inputFingerprint or
                                    ((input.get("left") as Int) shl LEFT_SHIFT)
                            }
                            if (input.isPresent("right")) {
                                inputFingerprint =
                                    inputFingerprint or
                                    ((input.get("right") as Int) shl RIGHT_SHIFT)
                            }
                            schema.outputChain(
                                valueAt = { depth -> t3DepthTag(depth) or inputFingerprint },
                            )
                        }.withVariablesProvider(T3Vector.variableNames) {
                            providerApplications.incrementAndGet()
                            vector.bindings
                        },
                )
            },
        )
    return SeedAndT3Fixture(
        world = world,
        seedApplications = seedApplications,
        providerApplications = providerApplications,
        t3InputAliases = t3InputAliases,
    )
}

internal fun inclusionCombinationWorld(): InclusionCombinationFixture {
    val currentVector =
        AtomicReference(T1CombinationVector.from(T2CombinationVector.all.first()))
    val seedApplications = AtomicInteger()
    val t3Applications = AtomicInteger()
    val t2Applications = AtomicInteger()
    val t1Applications = AtomicInteger()
    val t3ProviderApplications = AtomicInteger()
    val t2ProviderApplications = AtomicInteger()
    val t1ProviderApplications = AtomicInteger()
    val t3Input = AtomicReference<InputSnapshot?>(null)
    val t2Input = AtomicReference<InputSnapshot?>(null)
    val t1Input = AtomicReference<InputSnapshot?>(null)
    val world =
        TestWorld.fromSDL(
            schemaSDL =
                """
                type Query {
                  seed: Int!
                  t3: InclusionTester!
                  t2: InclusionTester!
                  t1: InclusionTester!
                }

                type InclusionTester {
                  i: Int!
                  n: InclusionTester
                }
                """.trimIndent(),
            fieldResolvers = { schema ->
                val seed = schema.requireObjectField("Query", "seed")
                val t3 = schema.requireObjectField("Query", "t3")
                val t2 = schema.requireObjectField("Query", "t2")
                val t1 = schema.requireObjectField("Query", "t1")
                val t3Fragment = schema.fragmentFrom(T3_INPUT_FRAGMENT, variableField = t3)
                val t2Fragment = schema.fragmentFrom(T2_INPUT_FRAGMENT, variableField = t2)
                val t1Fragment = schema.fragmentFrom(T1_INPUT_FRAGMENT, variableField = t1)

                mapOf(
                    seed to
                        fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                            seedApplications.incrementAndGet()
                            SEED_VALUE
                        },
                    t3 to
                        fieldResolverOf(t3Fragment) { input, _ ->
                            t3Applications.incrementAndGet()
                            val snapshot = input.snapshot()
                            t3Input.set(snapshot)
                            var inputFingerprint = 0
                            if (input.isPresent("left")) {
                                inputFingerprint =
                                    inputFingerprint or
                                    ((input.get("left") as Int) shl LEFT_SHIFT)
                            }
                            if (input.isPresent("right")) {
                                inputFingerprint =
                                    inputFingerprint or
                                    ((input.get("right") as Int) shl RIGHT_SHIFT)
                            }
                            schema.outputChain(
                                valueAt = { depth -> t3DepthTag(depth) or inputFingerprint },
                            )
                        }.withVariablesProvider(T3Vector.variableNames) {
                            t3ProviderApplications.incrementAndGet()
                            currentVector.get().t3.bindings
                        },
                    t2 to
                        fieldResolverOf(t2Fragment) { input, _ ->
                            t2Applications.incrementAndGet()
                            val snapshot = input.snapshot()
                            t2Input.set(snapshot)
                            schema.outputChain(
                                valueAt = { depth -> t2FingerprintFromInput(input, depth) },
                            )
                        }.withVariablesProvider(T2Vector.variableNames) {
                            t2ProviderApplications.incrementAndGet()
                            currentVector.get().t2.bindings
                        },
                    t1 to
                        fieldResolverOf(t1Fragment) { input, _ ->
                            t1Applications.incrementAndGet()
                            val snapshot = input.snapshot()
                            t1Input.set(snapshot)
                            schema.outputChain(
                                valueAt = { depth -> t1FingerprintFromInput(input, depth) },
                            )
                        }.withVariablesProvider(T1Vector.variableNames) {
                            t1ProviderApplications.incrementAndGet()
                            currentVector.get().t1.bindings
                        },
                )
            },
        )
    return InclusionCombinationFixture(
        world = world,
        currentVector = currentVector,
        seedApplications = seedApplications,
        t3Applications = t3Applications,
        t2Applications = t2Applications,
        t1Applications = t1Applications,
        t3ProviderApplications = t3ProviderApplications,
        t2ProviderApplications = t2ProviderApplications,
        t1ProviderApplications = t1ProviderApplications,
        t3Input = t3Input,
        t2Input = t2Input,
        t1Input = t1Input,
    )
}

internal data class SeedAndT3Fixture(
    val world: TestWorld,
    val seedApplications: AtomicInteger,
    val providerApplications: AtomicInteger,
    val t3InputAliases: AtomicReference<Set<String>>,
)

/** Mutable case state for sequential exhaustive execution; one case must finish before start. */
internal data class InclusionCombinationFixture(
    val world: TestWorld,
    val currentVector: AtomicReference<T1CombinationVector>,
    val seedApplications: AtomicInteger,
    val t3Applications: AtomicInteger,
    val t2Applications: AtomicInteger,
    val t1Applications: AtomicInteger,
    val t3ProviderApplications: AtomicInteger,
    val t2ProviderApplications: AtomicInteger,
    val t1ProviderApplications: AtomicInteger,
    val t3Input: AtomicReference<InputSnapshot?>,
    val t2Input: AtomicReference<InputSnapshot?>,
    val t1Input: AtomicReference<InputSnapshot?>,
) {
    fun start(vector: T2CombinationVector) {
        start(T1CombinationVector.from(vector))
    }

    fun start(vector: T1CombinationVector) {
        currentVector.set(vector)
        seedApplications.set(0)
        t3Applications.set(0)
        t2Applications.set(0)
        t1Applications.set(0)
        t3ProviderApplications.set(0)
        t2ProviderApplications.set(0)
        t1ProviderApplications.set(0)
        t3Input.set(null)
        t2Input.set(null)
        t1Input.set(null)
    }
}
