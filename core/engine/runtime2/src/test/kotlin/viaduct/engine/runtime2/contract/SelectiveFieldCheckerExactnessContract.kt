package viaduct.engine.runtime2.contract

import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import viaduct.engine.api.CheckerResult
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.materializeSelectionForestOf
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.registry.FieldCheckerResolver
import viaduct.engine.runtime2.model.registry.ResolverFragmentTemplates
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.resolution.framework.CheckerInvocationObservation
import viaduct.engine.runtime2.resolution.framework.CheckerKind
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.ResolverObserver
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.framework.instantiateBindings
import viaduct.engine.runtime2.resolvers.instantiateBindings
import viaduct.engine.runtime2.schema.operationSelectionsFrom
import viaduct.graphql.schema.ViaductSchema

/** Resolver23's exact producer-demand and field-checker-application obligations. */
interface SelectiveFieldCheckerExactnessContract {
    val coroutineResolverSubject: CoroutineResolverTestSubject

    @Test
    fun `checker raw demand is exact and restores checks only at value resolver inputs`() {
        listOf(false, true).forEach { rawSelected ->
            val producerDemand = AtomicReference<SelectionForest>()
            val resolverObserver =
                object : ResolverObserver {
                    override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                        if (observation.field.name == "item") {
                            producerDemand.set(observation.suppliedDemand)
                        }
                    }
                }
            val checkerRecorder = CheckerApplicationRecorder()
            val worldFixture = exactnessWorld()
            val world = worldFixture.assumptions
            val operation =
                SharedOperationContext.create(
                    world = world,
                    resolverObserver = resolverObserver,
                    checkerObserver = checkerRecorder,
                )
            val query =
                if (rawSelected) {
                    "{ item { checked(seed: 5) raw } }"
                } else {
                    "{ item { checked(seed: 5) } }"
                }

            val result =
                coroutineResolverSubject.resolve(
                    operation,
                    worldFixture.schemas.operationSelectionsFrom(query),
                )

            val itemType = world.schema.requireType("Item") as ViaductSchema.Object
            assertEquals(
                setOf("checked", "raw", "dependency"),
                assertNotNull(producerDemand.get())
                    .merge(itemType)
                    .instantiateBindings(operation)
                    .groundKeys()
                    .mapTo(linkedSetOf()) { key -> key.field.name },
            )

            val itemKey = groundKey(world.schema.requireObjectField("Query", "item"))
            val checkedField = world.schema.requireObjectField("Item", "checked")
            val rawField = world.schema.requireObjectField("Item", "raw")
            val dependencyField = world.schema.requireObjectField("Item", "dependency")
            val expected =
                buildList {
                    add(
                        CheckerInvocationObservation(
                            checkerKind = CheckerKind.FIELD,
                            logicalQueryRoot = result,
                            occurrencePath = listOf(itemKey, groundKey(checkedField, mapOf("seed" to 5))),
                            arguments = Arguments.Resolved.of(checkedField, mapOf("seed" to 5)),
                            checkedTarget = ResolverTarget.FieldCheckerTarget(checkedField),
                        ),
                    )
                    add(
                        CheckerInvocationObservation(
                            checkerKind = CheckerKind.FIELD,
                            logicalQueryRoot = result,
                            occurrencePath = listOf(itemKey, groundKey(dependencyField)),
                            arguments = Arguments.Resolved.of(dependencyField, emptyMap()),
                            checkedTarget = ResolverTarget.FieldCheckerTarget(dependencyField),
                        ),
                    )
                    if (rawSelected) {
                        add(
                            CheckerInvocationObservation(
                                checkerKind = CheckerKind.FIELD,
                                logicalQueryRoot = result,
                                occurrencePath = listOf(itemKey, groundKey(rawField)),
                                arguments = Arguments.Resolved.of(rawField, emptyMap()),
                                checkedTarget = ResolverTarget.FieldCheckerTarget(rawField),
                            ),
                        )
                    }
                }
            assertTrue(
                checkerRecorder.hasExactlyCheckerApplications(expected),
                "Expected $expected, observed ${checkerRecorder.checkerApplications()}",
            )
        }
    }

    @Test
    fun `checker applications coalesce in one associated Query scope with exact paths`() {
        val checkerRecorder = CheckerApplicationRecorder()
        val worldFixture = sharedQueryScopeWorld()
        val world = worldFixture.assumptions
        val operation = SharedOperationContext.create(world, checkerObserver = checkerRecorder)

        val result =
            coroutineResolverSubject.resolve(
                operation,
                worldFixture.schemas.operationSelectionsFrom(
                    "{ item { first: checked(seed: 1) second: checked(seed: 2) } }",
                ),
            )

        val observations = checkerRecorder.checkerApplications()
        val checkedField = world.schema.requireObjectField("Item", "checked")
        val policyField = world.schema.requireObjectField("Query", "policy")
        val wrapperField = world.schema.requireObjectField("Query", "wrapper")
        val checked = observations.filter { it.checkedCoordinate == checkedField }
        val policies = observations.filter { it.checkedCoordinate == policyField }

        assertEquals(
            setOf(1, 2),
            checked.map { checkNotNull(it.arguments).fieldValues.getValue("seed") }.toSet(),
        )
        assertTrue(checked.all { it.logicalQueryRoot === result })
        assertEquals(1, policies.size)
        assertTrue(policies.none { it.logicalQueryRoot === result })
        assertTrue(policies.all { it.occurrencePath == listOf(groundKey(policyField)) })
        assertTrue(policies.all { it.arguments == Arguments.Resolved.of(policyField, emptyMap()) })
        assertTrue(observations.none { it.checkedCoordinate == wrapperField })
        assertEquals(3, observations.size)
    }

    private fun exactnessWorld(): TestWorld =
        TestWorld.fromSDL(
            selectiveResolvers = true,
            schemaSDL =
                """
                type Query {
                  item: Item!
                }

                type Item {
                  checked(seed: Int!): Int!
                  raw: Int!
                  dependency: Int!
                  extra: Int!
                }
                """.trimIndent(),
            fieldResolvers = { schema ->
                val item = schema.loweredSchema.requireObjectField("Query", "item")
                val checked = schema.loweredSchema.requireObjectField("Item", "checked")
                val raw = schema.loweredSchema.requireObjectField("Item", "raw")
                mapOf(
                    item to
                        fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                            schema.loweredSchema.objectOf("Item") {
                                "dependency" setTo 41
                                "extra" setTo 99
                            }
                        },
                    checked to
                        fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Item")) { _, arguments ->
                            arguments.fieldValues.getValue("seed")
                        },
                    raw to
                        fieldResolverOf(
                            schema.fragmentFrom("fragment Input on Item { dependency }"),
                        ) { input, _ ->
                            (input.selectionValues().getValue("dependency") as Int) + 1
                        },
                )
            },
            fieldCheckers = { schema ->
                val query = schema.loweredSchema.requireQueryTypeDef()
                val checked = schema.loweredSchema.requireObjectField("Item", "checked")
                listOf("checked", "raw", "dependency", "extra").associate { name ->
                    val field = schema.loweredSchema.requireObjectField("Item", name)
                    field to
                        FieldCheckerResolver.of(
                            field = field,
                            queryType = query,
                            fragmentTemplates =
                                if (field == checked) {
                                    mapOf(
                                        "raw" to
                                            ResolverFragmentTemplates(
                                                objectFragmentTemplate =
                                                    schema
                                                        .fragmentFrom("fragment Input on Item { raw }")
                                                        .materializeSelections,
                                                queryFragmentTemplate = materializeSelectionForestOf(),
                                            ),
                                    )
                                } else {
                                    emptyMap()
                                },
                        ) { _, _, _ -> CheckerResult.Success }
                }
            },
        )

    private fun sharedQueryScopeWorld(): TestWorld =
        TestWorld.fromSDL(
            selectiveResolvers = true,
            schemaSDL =
                """
                type Query {
                  item: Item!
                  wrapper: Int!
                  policy: Int!
                }

                type Item {
                  checked(seed: Int!): Int!
                }
                """.trimIndent(),
            fieldResolvers = { schema ->
                val item = schema.loweredSchema.requireObjectField("Query", "item")
                val wrapper = schema.loweredSchema.requireObjectField("Query", "wrapper")
                val policy = schema.loweredSchema.requireObjectField("Query", "policy")
                val checked = schema.loweredSchema.requireObjectField("Item", "checked")
                mapOf(
                    item to
                        fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                            schema.loweredSchema.objectOf("Item")
                        },
                    wrapper to
                        fieldResolverOf(
                            schema.fragmentFrom("fragment Input on Query { policy }"),
                        ) { input, _ ->
                            input.selectionValues().getValue("policy")
                        },
                    policy to fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 7 },
                    checked to
                        fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Item")) { _, arguments ->
                            arguments.fieldValues.getValue("seed")
                        },
                )
            },
            fieldCheckers = { schema ->
                val query = schema.loweredSchema.requireQueryTypeDef()
                val checked = schema.loweredSchema.requireObjectField("Item", "checked")
                listOf(
                    checked to
                        FieldCheckerResolver.of(
                            field = checked,
                            queryType = query,
                            fragmentTemplates =
                                mapOf(
                                    "query" to
                                        ResolverFragmentTemplates(
                                            objectFragmentTemplate = materializeSelectionForestOf(),
                                            queryFragmentTemplate =
                                                schema
                                                    .fragmentFrom("fragment Input on Query { wrapper }")
                                                    .materializeSelections,
                                        ),
                                ),
                        ) { _, _, _ -> CheckerResult.Success },
                    schema.loweredSchema.requireObjectField("Query", "wrapper") to
                        FieldCheckerResolver.of(
                            schema.loweredSchema.requireObjectField("Query", "wrapper"),
                            query,
                        ) { _, _, _ -> CheckerResult.Success },
                    schema.loweredSchema.requireObjectField("Query", "policy") to
                        FieldCheckerResolver.of(
                            schema.loweredSchema.requireObjectField("Query", "policy"),
                            query,
                        ) { _, _, _ -> CheckerResult.Success },
                ).toMap()
            },
        )
}

private fun groundKey(
    field: ViaductSchema.ObjectField,
    arguments: Map<String, Any?> = emptyMap(),
): ObjectEngineResult.GroundKey = ObjectEngineResult.GroundKey.of(field, arguments)
