@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.runtime2.propertytest.ResolverTestSuite
import viaduct.engine.runtime2.propertytest.ResolverTestSuiteDefinition
import viaduct.engine.runtime2.propertytest.ResolverTestSuiteVariant
import viaduct.graphql.schema.ViaductSchema

class TypeCheckerStressCoverageTest {
    @Test
    fun `the multithreaded stress suites include type checkers`(): Unit =
        runBlocking {
            val standard = ResolverTestSuiteDefinition.RESOLUTION_TYPE_CHECKER_MIXED
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
