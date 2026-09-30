package semantics.resolver26

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import semantics.arbitrary.GeneratedTypeCheckerMode
import semantics.arbitrary.TestCaseCount
import semantics.arbitrary.checkResolverTestCases
import viaduct.graphql.schema.ViaductSchema

class TypeCheckerStressCoverageTest {
    @Test
    fun `the multithreaded stress distribution includes type checkers`() =
        runBlocking {
            val campaign = ResolverMultithreadedStressTest()
            var foundTypeChecker = false

            checkResolverTestCases(
                counts = TestCaseCount(1, 1, 1),
                config = campaign.typeCheckerConfig,
                typeCheckerMode = campaign.typeCheckerMode(GeneratedTypeCheckerMode.MIXED),
                profile = "resolver26-multithreaded-type-checker-coverage",
                seed = 2026093001L,
            ) { testWorld, _ ->
                val world = testWorld.newAssumptions(selectiveResolvers = true)
                foundTypeChecker =
                    world.schema.types.values
                        .filterIsInstance<ViaductSchema.Object>()
                        .any { type -> world.resolverRegistry.typeChecker(type) != null }
            }

            assertTrue(
                foundTypeChecker,
                "Resolver26MultithreadedStress generated no type checker",
            )
        }
}
