package viaduct.engine.runtime2.resolution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.materializeSelectionForestOf
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.registry.FieldCheckerResolver
import viaduct.engine.runtime2.model.registry.ResolverFragmentTemplates
import viaduct.engine.runtime2.model.registry.TypeCheckerResolver
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.ResolverObserver
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

class UncheckedCheckerParentDemandTest : ResolutionDispatcherResource {
    @Test
    fun `unchecked checker parent demand with dynamic construction demand`() = runCase(false)

    @Test
    fun `unchecked checker parent demand with precomputed construction demand`() = runCase(true)

    private fun runCase(precomputed: Boolean) {
        val seeds = mutableListOf<Int>()
        val derived = mutableListOf<Int>()
        val observations = mutableListOf<ResolverInvocationObservation>()
        val fixture = TestWorld.fromDSL(
            """
            extend type Query {
              consumer: Int @resolver(result: 1)
              root: Root @resolver(result: {seed: 7, label: 1, child: {}})
            }
            type Root { seed: Int label: Int child: Child }
            type Child {
              parent: Root @parent
              derived: Int @resolver(of: "parent { label }", result: 42)
            }
            """.trimIndent(),
            fieldCheckers = { schemas ->
                val consumer = schemas.loweredSchema.requireObjectField("Query", "consumer")
                mapOf(
                    consumer to FieldCheckerResolver.of(
                        consumer,
                        schemas.loweredSchema.requireQueryTypeDef(),
                        mapOf(
                            "input" to ResolverFragmentTemplates(
                                schemas.fragmentFrom("fragment F on Query { root { child { derived } } }").materializeSelections,
                                materializeSelectionForestOf(),
                            )
                        ),
                    ) { _, inputs, _ ->
                        val root = inputs.getValue("input").objectValue.get("root") as EngineObjectData.Sync
                        val child = root.get("child") as EngineObjectData.Sync
                        derived += child.get("derived") as Int
                        CheckerResult.Success
                    }
                )
            },
            typeCheckers = { schemas ->
                val root = schemas.loweredSchema.requireObjectField("Root", "seed").containingDef
                mapOf(
                    root to TypeCheckerResolver.of(
                        root,
                        schemas.loweredSchema.requireQueryTypeDef(),
                        mapOf(
                            "input" to ResolverFragmentTemplates(
                                schemas.fragmentFrom("fragment F on Root { child { parent { seed } } }").materializeSelections,
                                materializeSelectionForestOf(),
                            )
                        ),
                    ) { inputs, _ ->
                        val child = inputs.getValue("input").objectValue.get("child") as EngineObjectData.Sync
                        val parent = child.get("parent") as EngineObjectData.Sync
                        seeds += parent.get("seed") as Int
                        CheckerResult.Success
                    }
                )
            },
        )
        val operation = SharedOperationContext.create(
            fixture.newAssumptions(usePrecomputedConstructionDemand = precomputed),
            resolverObserver = object : ResolverObserver {
                override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                    observations += observation
                }
            },
        )
        val root = fixture.schema.requireObjectField("Root", "seed").containingDef
        val result = try {
            operation.resolveWithTestDispatcher(fixture.schemas.fragmentFrom("fragment F on Query { consumer }").subselections)
        } catch (failure: Exception) {
            val demand = observations.singleOrNull { it.field.name == "root" }?.suppliedDemand?.merge(root)?.keys()?.map { it.field.name }
            throw AssertionError("precomputed=$precomputed producerDemand=$demand checkerSeeds=$seeds derived=$derived", failure)
        }
        val supplied = requireNotNull(observations.single { it.field.name == "root" }.suppliedDemand).merge(root)
        assertTrue(supplied.keys().any { it.field.name == "seed" }, "precomputed=$precomputed demand=${supplied.keys()}")
        assertEquals(listOf(7), seeds, "precomputed=$precomputed")
        assertEquals(listOf(42), derived, "precomputed=$precomputed")
        val consumerKey = ObjectEngineResult.GroundKey.of(fixture.schema.requireObjectField("Query", "consumer"), emptyMap())
        assertEquals(CheckerResult.Success, result.getCell(consumerKey).fieldCheckerResult.get())
    }
}
