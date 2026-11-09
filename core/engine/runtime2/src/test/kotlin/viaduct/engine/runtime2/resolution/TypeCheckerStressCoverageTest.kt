@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import viaduct.engine.runtime2.arbitrary.GeneratedFieldCheckerMode
import viaduct.engine.runtime2.arbitrary.GeneratedTypeCheckerMode
import viaduct.engine.runtime2.propertytest.ResolverTestSuite
import viaduct.engine.runtime2.propertytest.ResolverTestSuiteDefinition
import viaduct.engine.runtime2.propertytest.ResolverTestSuiteVariant
import viaduct.graphql.schema.ViaductSchema

class TypeCheckerStressCoverageTest {
    @Test
    fun `the multithreaded stress suites include type checkers`(): Unit =
        runBlocking {
            val stress = ResolverMultithreadedStressTest()
            val standard =
                ResolverTestSuiteDefinition.forProfile(
                    profile = "resolution-type-checker-mixed",
                    config = stress.typeCheckerConfig,
                    fieldCheckerMode = GeneratedFieldCheckerMode.RUNTIME_MIXED,
                    typeCheckerMode = stress.typeCheckerMode(GeneratedTypeCheckerMode.MIXED),
                )
            val suites =
                ResolverTestSuiteDefinition.entries.filter { definition ->
                    definition.profile == standard.profile &&
                        definition.fieldCheckerMode == standard.fieldCheckerMode &&
                        definition.typeCheckerMode == standard.typeCheckerMode
                }

            assertEquals(ResolverTestSuiteVariant.entries.toSet(), suites.map(ResolverTestSuiteDefinition::variant).toSet())
            suites.forEach { definition ->
                assertTrue(ResolverTestSuite.load(definition).holdsTypeChecker(), "Stored suite ${definition.id} generated no type checker")
            }
        }

    private suspend fun ResolverTestSuite.holdsTypeChecker(): Boolean =
        testCaseIds().any { id ->
            var found = false
            replay(id) { testWorld, _ ->
                val world = testWorld.newAssumptions()
                found =
                    world.schema.types.values
                        .filterIsInstance<ViaductSchema.Object>()
                        .any { type -> world.resolverRegistry.typeChecker(type) != null }
            }
            found
        }
}
