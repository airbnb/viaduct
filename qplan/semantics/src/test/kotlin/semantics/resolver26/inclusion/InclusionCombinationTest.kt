@file:Suppress("ForbiddenImport")

package semantics.resolver26.inclusion

import semantics.resolver26.Resolver26DispatcherResource

import kotlinx.coroutines.runBlocking
import model.Fragment
import model.ObjectEngineResult
import model.fragmentFrom
import model.merge
import model.requireObjectField
import model.requireQueryTypeDef
import model.testing.TestWorld
import semantics.correctresolution.correctResolution
import semantics.resolver26.resolve
import semantics.shared.SharedOperationContext
import semantics.correctresolution.CorrectnessResolverObserver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InclusionCombinationTest : Resolver26DispatcherResource {
    @Test
    fun `seed alternatives combine include and skip conditions for t3`() {
        T3Vector.all.forEach { vector ->
            val fixture = seedAndT3World(vector)
            val world = fixture.world.assumptions
            val query = fixture.world.fullChainQuery("t3")
            val operation =
                SharedOperationContext.create(
                    world = world,
                    resolverObserver = CorrectnessResolverObserver(),
                )
            val result = operation.resolveWithTestDispatcher(query.subselections)
            val oracle = T3Oracle(vector)
            val message = vector.toString()

            assertEquals(1, fixture.providerApplications.get(), message)
            assertEquals(oracle.inputAliases, fixture.t3InputAliases.get(), message)
            assertEquals(if (oracle.seedActive) 1 else 0, fixture.seedApplications.get(), message)
            assertActivations(
                fixture.world,
                result,
                mapOf("t3" to true, "seed" to oracle.seedActive),
                message,
            )
            assertFingerprintChain(
                fixture.world,
                result,
                "t3",
                oracle::fingerprintAt,
                message,
            )
            assertTrue(
                result.correctResolution(
                    operation,
                    query.subselections.merge(fixture.world.schema.requireQueryTypeDef()),
                ),
                message,
            )
        }
    }

    @Test
    fun `t3 alternatives combine with nested conditions for t2`() {
        val fixture = inclusionCombinationWorld()
        val world = fixture.world.assumptions
        val query = fixture.world.fullChainQuery("t2")
        val aliasCombinationsByDepth =
            List(CHAIN_DEPTH) { linkedSetOf<Set<String>>() }

        T2CombinationVector.all.forEach { vector ->
            fixture.start(vector)
            val operation =
                SharedOperationContext.create(
                    world = world,
                    resolverObserver = CorrectnessResolverObserver(),
                )
            val result = operation.resolveWithTestDispatcher(query.subselections)
            val oracle = T2Oracle(vector)
            val message = vector.toString()
            repeat(CHAIN_DEPTH) { depth ->
                aliasCombinationsByDepth[depth] += oracle.contributingAliasesAt(depth)
            }

            assertEquals(1, fixture.t2Applications.get(), message)
            assertEquals(1, fixture.t2ProviderApplications.get(), message)
            assertEquals(oracle.t2Input, fixture.t2Input.get(), message)
            assertEquals(if (oracle.t3Active) 1 else 0, fixture.t3Applications.get(), message)
            assertEquals(if (oracle.t3Active) 1 else 0, fixture.t3ProviderApplications.get(), message)
            assertEquals(
                if (oracle.t3Active) oracle.t3Input else null,
                fixture.t3Input.get(),
                message,
            )
            assertEquals(if (oracle.seedActive) 1 else 0, fixture.seedApplications.get(), message)
            assertActivations(
                fixture.world,
                result,
                mapOf(
                    "t2" to true,
                    "t3" to oracle.t3Active,
                    "seed" to oracle.seedActive,
                ),
                message,
            )
            assertFingerprintChain(
                fixture.world,
                result,
                "t2",
                oracle::fingerprintAt,
                message,
            )
            assertTrue(
                result.correctResolution(
                    operation,
                    query.subselections.merge(fixture.world.schema.requireQueryTypeDef()),
                ),
                message,
            )
        }

        aliasCombinationsByDepth.forEachIndexed { depth, combinations ->
            assertEquals(
                setOf(
                    emptySet(),
                    setOf("first"),
                    setOf("second"),
                    setOf("first", "second"),
                ),
                combinations,
                "depth=$depth",
            )
        }
    }

    @Test
    fun `direct and transitive t3 alternatives combine for t1`() {
        val fixture = inclusionCombinationWorld()
        val world = fixture.world.assumptions
        val query = fixture.world.fullChainQuery("t1")
        var witnessedDirectOnlyWithExcludedT2T3Aliases = false
        var witnessedIndirectActivationWithExcludedDirectAliases = false
        var witnessedDirectAndIndirectActivation = false

        T1CombinationVector.all.forEach { vector ->
            fixture.start(vector)
            val operation =
                SharedOperationContext.create(
                    world = world,
                    resolverObserver = CorrectnessResolverObserver(),
                )
            val result = operation.resolveWithTestDispatcher(query.subselections)
            val oracle = T1Oracle(vector)
            val message = vector.toString()
            witnessedDirectOnlyWithExcludedT2T3Aliases =
                witnessedDirectOnlyWithExcludedT2T3Aliases ||
                    (oracle.directT3Active &&
                        oracle.t2Active &&
                        !oracle.indirectT3Active &&
                        oracle.t2Input.topLevelAliases().isEmpty())
            witnessedIndirectActivationWithExcludedDirectAliases =
                witnessedIndirectActivationWithExcludedDirectAliases ||
                    (oracle.indirectT3Active &&
                        !oracle.directT3Active &&
                        setOf("directFirst", "directSecond")
                            .intersect(oracle.t1Input.topLevelAliases())
                            .isEmpty())
            witnessedDirectAndIndirectActivation =
                witnessedDirectAndIndirectActivation ||
                    (oracle.directT3Active && oracle.indirectT3Active)

            assertEquals(1, fixture.t1Applications.get(), message)
            assertEquals(1, fixture.t1ProviderApplications.get(), message)
            assertEquals(oracle.t1Input, fixture.t1Input.get(), message)
            assertEquals(if (oracle.t2Active) 1 else 0, fixture.t2Applications.get(), message)
            assertEquals(
                if (oracle.t2Active) 1 else 0,
                fixture.t2ProviderApplications.get(),
                message,
            )
            assertEquals(
                if (oracle.t2Active) oracle.t2Input else null,
                fixture.t2Input.get(),
                message,
            )
            assertEquals(if (oracle.t3Active) 1 else 0, fixture.t3Applications.get(), message)
            assertEquals(
                if (oracle.t3Active) 1 else 0,
                fixture.t3ProviderApplications.get(),
                message,
            )
            assertEquals(
                if (oracle.t3Active) oracle.t3Input else null,
                fixture.t3Input.get(),
                message,
            )
            assertEquals(if (oracle.seedActive) 1 else 0, fixture.seedApplications.get(), message)
            assertActivations(
                fixture.world,
                result,
                mapOf(
                    "t1" to true,
                    "t2" to oracle.t2Active,
                    "t3" to oracle.t3Active,
                    "seed" to oracle.seedActive,
                ),
                message,
            )
            assertFingerprintChain(
                fixture.world,
                result,
                "t1",
                oracle::fingerprintAt,
                message,
            )
            assertTrue(
                result.correctResolution(
                    operation,
                    query.subselections.merge(fixture.world.schema.requireQueryTypeDef()),
                ),
                message,
            )
        }

        assertTrue(witnessedDirectOnlyWithExcludedT2T3Aliases)
        assertTrue(witnessedIndirectActivationWithExcludedDirectAliases)
        assertTrue(witnessedDirectAndIndirectActivation)
    }

    private fun TestWorld.fullChainQuery(fieldName: String): Fragment =
        assumptions.fragmentFrom(
            """
            fragment Result on Query {
              $fieldName {
                i
                n {
                  i
                  n {
                    i
                    n { i }
                  }
                }
              }
            }
            """.trimIndent(),
        )

    private fun assertActivations(
        world: TestWorld,
        result: ObjectEngineResult,
        expected: Map<String, Boolean>,
        message: String,
    ) {
        expected.forEach { (fieldName, expectedActive) ->
            val field = world.schema.requireObjectField("Query", fieldName)
            val cell = result.getCell(ObjectEngineResult.GroundKey.of(field, emptyMap()))
            assertEquals(
                expectedActive,
                runBlocking { cell.fetchActivated() },
                "$message field=$fieldName",
            )
        }
    }

    private fun assertFingerprintChain(
        world: TestWorld,
        result: ObjectEngineResult,
        fieldName: String,
        expectedAt: (Int) -> Int,
        message: String,
    ) {
        val field = world.schema.requireObjectField("Query", fieldName)
        var level =
            assertIs<ObjectEngineResult>(
                result
                    .getCell(ObjectEngineResult.GroundKey.of(field, emptyMap()))
                    .getValue()
                    .get(),
                message,
            )
        repeat(CHAIN_DEPTH) { depth ->
            val i = world.schema.requireObjectField("InclusionTester", "i")
            assertEquals(
                expectedAt(depth),
                level.getCell(ObjectEngineResult.GroundKey.of(i, emptyMap())).getValue().get(),
                "$message depth=$depth",
            )
            if (depth < CHAIN_DEPTH - 1) {
                val n = world.schema.requireObjectField("InclusionTester", "n")
                level =
                    assertIs(
                        level
                            .getCell(ObjectEngineResult.GroundKey.of(n, emptyMap()))
                            .getValue()
                            .get(),
                        "$message depth=$depth",
                    )
            }
        }
    }
}
