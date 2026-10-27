@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.model.registry.FieldValueResolver
import viaduct.engine.runtime2.model.registry.ResolverFragmentTemplates
import viaduct.engine.runtime2.model.registry.ResolverRegistry
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.toCanonicalMaterializeSelectionForest
import viaduct.engine.runtime2.resolution.framework.ResolverReadCycleException
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.schema.operationSelectionsFrom
import viaduct.graphql.schema.ViaductSchema

@OptIn(ExperimentalCoroutinesApi::class)
class ResolverTimeoutTest {
    @Test
    fun `request deadline diagnoses a genuine resolver read cycle`() =
        runTest {
            val (world, operation) = cyclicWorld()
            val failure = assertFailsWith<ResolverReadCycleException> {
                operation.resolveWithTimeout(world.schemas.operationSelectionsFrom("{ first }"), 50)
            }
            assertEquals(50L, testScheduler.currentTime)
            assertIs<TimeoutCancellationException>(failure.cause)
            assertEquals(failure.cycle.first(), failure.cycle.last())
            assertTrue(failure.cycle.any { it.path.last().toString().contains("second") })
        }

    @Test
    fun `acyclic query and mutation timeouts preserve cancellation and clean up work`() =
        runTest {
            for (type in listOf("Query", "Mutation")) {
                var stopped = false
                val world = TestWorld.fromSDL(
                    "type Query { value: Int } type Mutation { value: Int }",
                    fieldResolvers = { schema ->
                        mapOf(
                            schema.loweredSchema.requireObjectField(type, "value") to fieldResolverOf(schema.loweredSchema.emptyFragmentOf(type)) { _, _ ->
                                try {
                                    awaitCancellation()
                                } finally {
                                    stopped = true
                                }
                            }
                        )
                    },
                )
                val query = if (type == "Mutation") "mutation { value }" else "{ value }"
                assertFailsWith<TimeoutCancellationException> {
                    SharedOperationContext.create(world.assumptions).resolveWithTimeout(world.schemas.operationSelectionsFrom(query), 50)
                }
                assertTrue(stopped)
            }
        }

    @Test
    fun `caller timeout is not replaced by a collected cycle`() =
        runTest {
            val (world, operation) = cyclicWorld()
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(10) { operation.resolveWithTimeout(world.schemas.operationSelectionsFrom("{ first }"), 50) }
            }
            assertEquals(10L, testScheduler.currentTime)
        }

    @Test
    fun `resolver-local timeout remains a field failure`() =
        runTest {
            val world = TestWorld.fromSDL(
                "type Query { value: Int }",
                fieldResolvers = { schema ->
                    mapOf(
                        schema.loweredSchema.requireObjectField("Query", "value") to fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                            withTimeout(10) { awaitCancellation() }
                        }
                    )
                },
            )
            val result = SharedOperationContext.create(world.assumptions).resolveWithTimeout(world.schemas.operationSelectionsFrom("{ value }"), 50)
            val error = assertIs<ErrorEngineResult>(result.getCell(result.keys.single()).value.get())
            assertIs<TimeoutCancellationException>(error.errorData.cause)
            assertEquals(10L, testScheduler.currentTime)
        }

    private fun cyclicWorld(): Pair<TestWorld, SharedOperationContext<*>> {
        val world = TestWorld.fromSDL(
            "type Query { first: Int second: Int }",
            fieldResolvers = { schema ->
                mapOf(
                    schema.loweredSchema.requireObjectField("Query", "first") to fieldResolverOf(schema.fragmentFrom("fragment Input on Query { second }")) { _, _ -> 1 },
                    schema.loweredSchema.requireObjectField("Query", "second") to fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 2 },
                )
            },
        )
        val first = world.schema.requireObjectField("Query", "first")
        val second = world.schema.requireObjectField("Query", "second")
        val selfDependent = FieldValueResolver.of(
            field = second,
            fragmentTemplates = ResolverFragmentTemplates(
                objectFragmentTemplate = world.resolverRegistry.resolver(first).objectFragment.toCanonicalMaterializeSelectionForest(),
                queryFragmentTemplate = world.resolverRegistry.resolver(second).queryFragment.toCanonicalMaterializeSelectionForest(),
            ),
            queryType = world.schema.requireQueryTypeDef(),
            function = { _, _, _, _, _ -> 2 },
        )
        // Deliberately bypass assembly-time cycle rejection to exercise runtime deadlock diagnosis.
        val registry = object : ResolverRegistry by world.resolverRegistry {
            override fun resolver(field: ViaductSchema.ObjectField): FieldValueResolver = if (field == second) selfDependent else world.resolverRegistry.resolver(field)
        }
        return world to SharedOperationContext.create(Assumptions.of(world.schema, registry))
    }
}
