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
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.ResolutionObserver
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

class CheckerParentReadingResolverDemandTest : ResolutionDispatcherResource {
    @Test
    fun `checker input reaching a parent-reading resolver supplies its parent demand with dynamic construction demand`() {
        runCase(false)
    }

    @Test
    fun `checker input reaching a parent-reading resolver supplies its parent demand with precomputed construction demand`() {
        runCase(true)
    }

    private fun runCase(precomputed: Boolean) {
        val derived = mutableListOf<Int>()
        val observations = mutableListOf<ResolverInvocationObservation>()
        val fixture = TestWorld.fromDSL(
            """
            extend type Query {
              consumer: Int @resolver(script: "1")
              root: Root @resolver(script: "{'label': 1, 'child': {:}}")
            }
            type Root { label: Int child: Child }
            type Child {
              parent: Root @parent
              derived: Int @resolver(of: "parent { label }", script: "42")
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
        )
        val operation = SharedOperationContext.create(
            fixture.newAssumptions(usePrecomputedConstructionDemand = precomputed),
            resolutionObserver = object : ResolutionObserver {
                override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
                    observations += observation
                }
            },
        )
        val root = fixture.schema.requireObjectField("Root", "label").containingDef
        val result = try {
            operation.resolveWithTestDispatcher(fixture.schemas.fragmentFrom("fragment F on Query { consumer }").subselections)
        } catch (failure: Exception) {
            val demand = observations.singleOrNull { it.field.name == "root" }?.suppliedDemand?.merge(root)?.keys()?.map { it.field.name }
            throw AssertionError("precomputed=$precomputed producerDemand=$demand derived=$derived", failure)
        }
        val supplied = requireNotNull(observations.single { it.field.name == "root" }.suppliedDemand).merge(root)
        assertTrue(supplied.keys().any { it.field.name == "label" }, "precomputed=$precomputed demand=${supplied.keys()}")
        assertEquals(listOf(42), derived, "precomputed=$precomputed")
        val consumerKey = ObjectEngineResult.GroundKey.of(fixture.schema.requireObjectField("Query", "consumer"), emptyMap())
        assertEquals(CheckerResult.Success, result.getCell(consumerKey).fieldCheckerResult.get())
    }
}
