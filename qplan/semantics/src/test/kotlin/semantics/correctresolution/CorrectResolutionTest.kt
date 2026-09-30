@file:Suppress("ForbiddenImport")

package semantics.correctresolution

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import model.Arguments
import model.EngineErrorData
import model.EngineObjectDataEntry
import model.ObjectEngineResult
import model.ObjectSelectionForest
import model.ResolverOccurrenceId
import model.emptyFragmentOf
import model.engineObjectDataOf
import model.engineResultOf
import model.fragmentFrom
import model.materializeSelectionForestOf
import model.materializedEngineObjectDataOf
import model.merge
import model.objectOf
import model.registry.FieldCheckerResolver
import model.registry.ResolutionExecutionContext
import model.registry.ResolverFragmentTemplates
import model.registry.ResolverTarget
import model.requireObjectField
import model.requireQueryTypeDef
import model.requireType
import model.selectionForestOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.selectiveFieldResolverOf
import semantics.resolver26.Resolver26DispatcherResource
import semantics.shared.OEROccurrence
import semantics.shared.ResolverInvocationObservation
import semantics.shared.SharedOERContext
import semantics.shared.SharedOperationContext
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.graphql.schema.ViaductSchema

class CorrectResolutionTest : Resolver26DispatcherResource {
    @Test
    fun `direct resolve invocation and invocation through correctness replay do not cause invocation observations`() =
        runBlocking {
            val testWorld = TestWorld.fromDSL("extend type Query { value: Int @resolver(result: 7) }")
            val events = CopyOnWriteArrayList<ResolverInvocationObservation>()
            val observer = object : CorrectnessResolverObserver() {
                override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                    super.onResolverInvocation(observation)
                    events += observation
                }
            }
            val world = testWorld.assumptions
            val operation = SharedOperationContext.create(world, resolverObserver = observer)
            val fragment = testWorld.schemas.fragmentFrom("fragment Main on Query { value }")
            val root = operation.resolveWithTestDispatcher(fragment.subselections)
            assertEquals(1, events.size)
            repeat(2) { assertTrue(root.correctResolution(operation, fragment)) }
            val field = testWorld.schema.requireObjectField("Query", "value")
            world.resolverRegistry.resolver(field)(
                input = engineObjectDataOf(world.schema.requireQueryTypeDef()),
                queryValue = engineObjectDataOf(world.schema.requireQueryTypeDef()),
                arguments = Arguments.Resolved.of(field, emptyMap()),
                selections = selectionForestOf(),
                selectiveResolvers = world.selectiveResolvers,
                executionContext = ResolutionExecutionContext.Unsupported,
            )
            assertEquals(1, events.size)
        }

    @Test
    fun `correctness reapplies a selective resolver with completed output demand`() {
        val observedFields = mutableListOf<Set<String>>()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type User {
                      name: String!
                      age: Int!
                    }

                    type Query {
                      user: User!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val user = schema.loweredSchema.requireObjectField("Query", "user")
                    mapOf(
                        user to
                            selectiveFieldResolverOf(
                                objectFragment = schema.loweredSchema.emptyFragmentOf("Query"),
                                function = { _, _, selections ->
                                    val fields = mutableSetOf<String>()
                                    selections.forEach { selection -> fields += selection.key.field.name }
                                    observedFields += fields
                                    schema.loweredSchema.objectOf("User") {
                                        if ("name" in fields) "name" setTo "Ada"
                                        if ("age" in fields) "age" setTo 37
                                    }
                                },
                            ),
                    )
                },
            )
        val world = testWorld.assumptions
        val operation = SharedOperationContext.create(world, resolverObserver = CorrectnessResolverObserver())
        val selections =
            testWorld.schemas.fragmentFrom(
                """
                    fragment ignored on Query {
                      user {
                        name
                      }
                    }
                """.trimIndent(),
            ).subselections
        val result = operation.resolveWithTestDispatcher(selections)
        val querySelections = selections.merge(world.schema.requireQueryTypeDef())

        assertTrue(result.correctResolution(operation, querySelections))
        assertEquals(listOf(setOf("name"), setOf("name")), observedFields)

        // A second judgment and each standalone predicate must reapply independently.
        assertTrue(result.correctResolution(operation, querySelections))
        assertEquals(3, observedFields.size)
        assertTrue(result.isClosedUnderResolverDemand(operation))
        assertEquals(4, observedFields.size)
        assertTrue(result.conformsToResolvers(operation))
        assertEquals(5, observedFields.size)
    }

    @Test
    fun `selections must be rooted at Query`() {
        val worldFixture = TestWorld.fromSDL(SCHEMA_SDL)
        val world = worldFixture.assumptions
        val result = world.engineResultOf("Query")
        val operation = SharedOperationContext.create(world)
        val profileSelections =
            ObjectSelectionForest.of(
                type = world.schema.requireType("Profile") as ViaductSchema.Object,
                selections = emptyList(),
            )

        assertFailsWith<IllegalArgumentException> {
            result.correctResolution(operation, profileSelections)
        }
    }

    @Test
    fun `resolver query fragment witness participates in correctness`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      source: Int!
                      extra: Int!
                      consumer: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val source = schema.loweredSchema.requireObjectField("Query", "source")
                    val extra = schema.loweredSchema.requireObjectField("Query", "extra")
                    val consumer = schema.loweredSchema.requireObjectField("Query", "consumer")
                    mapOf(
                        source to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 7 },
                        extra to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 9 },
                        consumer to
                            fieldResolverOf(
                                objectFragment = schema.loweredSchema.emptyFragmentOf("Query"),
                                queryFragment =
                                    schema.fragmentFrom(
                                        """
                                        fragment ignored on Query {
                                          aliased: source
                                        }
                                        """.trimIndent(),
                                    ),
                            ) { _, queryValue, _ ->
                                queryValue.get("aliased")
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val consumer = testWorld.schema.requireObjectField("Query", "consumer")
        val consumerKey = ObjectEngineResult.GroundKey.of(consumer, emptyMap())
        val selections =
            testWorld.schemas.fragmentFrom(
                """
                    fragment ignored on Query {
                      consumer
                    }
                """.trimIndent(),
            ).subselections
                .merge(world.schema.requireQueryTypeDef())
        val result =
            world.engineResultOf("Query") {
                "consumer" resolvesTo 7
            }
        val occurrenceId = ResolverOccurrenceId.at(result, listOf(consumerKey))
        val missingObservation = SharedOperationContext.create(world)

        assertFalse(result.correctResolution(missingObservation, selections))

        val reusedPrimaryRoot =
            SharedOperationContext.create(world, resolverObserver = CorrectnessResolverObserver())
        reusedPrimaryRoot.resolverObserver.onQueryFragmentPrepared(occurrenceId, result)
        assertFalse(result.correctResolution(reusedPrimaryRoot, selections))

        val incorrectObservation =
            SharedOperationContext.create(world, resolverObserver = CorrectnessResolverObserver())
        incorrectObservation.resolverObserver.onQueryFragmentPrepared(
            occurrenceId,
            world.engineResultOf("Query") {
                "source" resolvesTo 8
            },
        )
        assertFalse(result.correctResolution(incorrectObservation, selections))

        val correctObservation =
            SharedOperationContext.create(world, resolverObserver = CorrectnessResolverObserver())
        correctObservation.resolverObserver.onQueryFragmentPrepared(
            occurrenceId,
            world.engineResultOf("Query") {
                "source" resolvesTo 7
            },
        )
        assertTrue(result.correctResolution(correctObservation, selections))

        val unexplainedExtraCell =
            SharedOperationContext.create(world, resolverObserver = CorrectnessResolverObserver())
        val queryResult =
            world.engineResultOf("Query") {
                "source" resolvesTo 7
                "extra" resolvesTo 9
            }
        unexplainedExtraCell.resolverObserver.onQueryFragmentPrepared(occurrenceId, queryResult)
        unexplainedExtraCell.resolverObserver.onQueryOERPrepared(
            SharedOERContext(
                occurrence = OEROccurrence(queryResult, emptyList(), queryResult),
                source = engineObjectDataOf(world.schema.requireQueryTypeDef()),
                closedValueSelections =
                    testWorld.schemas.fragmentFrom("fragment Scope on Query { source }")
                        .subselections
                        .merge(world.schema.requireQueryTypeDef()),
            ),
            queryOERDepth = 1,
        )
        assertFalse(result.correctResolution(unexplainedExtraCell, selections))

        correctObservation.resolverObserver.onQueryFragmentPrepared(
            occurrenceId,
            world.engineResultOf("Query") {
                "source" resolvesTo 7
            },
        )
        assertFalse(result.correctResolution(correctObservation, selections))
    }

    @Test
    fun `checker object fragments participate in demand closure`() {
        val worldFixture =
            TestWorld.fromSDL(
                schemaSDL = "type Query { checked: Int! objectSource: Int! }",
                fieldResolvers = { schema ->
                    mapOf(
                        schema.loweredSchema.requireObjectField("Query", "checked") to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 11 },
                        schema.loweredSchema.requireObjectField("Query", "objectSource") to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 7 },
                    )
                },
                fieldCheckers = { schema ->
                    val checked = schema.loweredSchema.requireObjectField("Query", "checked")
                    mapOf(
                        checked to
                            FieldCheckerResolver.of(
                                field = checked,
                                queryType = schema.loweredSchema.requireQueryTypeDef(),
                                fragmentTemplates =
                                    mapOf(
                                        "inputs" to
                                            ResolverFragmentTemplates(
                                                objectFragmentTemplate =
                                                    schema
                                                        .fragmentFrom(
                                                            "fragment Input on Query { objectSource }",
                                                        ).materializeSelections,
                                                queryFragmentTemplate = materializeSelectionForestOf(),
                                            ),
                                    ),
                            ) { _, _, _ -> CheckerResult.Success },
                    )
                },
            )
        val world = worldFixture.assumptions
        val checked = worldFixture.schema.requireObjectField("Query", "checked")
        val checkedKey = ObjectEngineResult.GroundKey.of(checked, emptyMap())
        val selections =
            worldFixture.schemas.fragmentFrom("fragment Query on Query { checked }")
                .subselections
                .merge(world.schema.requireQueryTypeDef())

        fun result(
            includeObjectSource: Boolean,
            includeCheckerSlot: Boolean = true,
        ): ObjectEngineResult =
            ObjectEngineResult.of(world.schema.requireQueryTypeDef(), mutable = true).apply {
                reserveCell(checkedKey).apply {
                    value.set(11)
                    fieldCheckerResult.complete(
                        if (includeCheckerSlot) CheckerResult.Success else null,
                    )
                }
                if (includeObjectSource) {
                    reserveCell(
                        ObjectEngineResult.GroundKey.of(
                            world.schema.requireObjectField("Query", "objectSource"),
                            emptyMap(),
                        ),
                    ).apply {
                        value.set(7)
                        fieldCheckerResult.complete(null)
                    }
                }
                freeze()
            }

        assertFalse(
            result(includeObjectSource = false).correctResolution(
                SharedOperationContext.create(world),
                selections,
            ),
        )

        val missingSlotResult =
            result(
                includeObjectSource = true,
                includeCheckerSlot = false,
            )
        assertFalse(
            missingSlotResult.correctResolution(
                SharedOperationContext.create(world),
                selections,
            ),
        )

        val closedObjectResult = result(includeObjectSource = true)
        assertTrue(
            closedObjectResult.correctResolution(
                SharedOperationContext.create(world),
                selections,
            ),
        )
    }

    @Test
    fun `checker result must agree with its replayed relation`() {
        var checkerCalls = 0
        val worldFixture = TestWorld
            .fromSDL(
                schemaSDL = "type Query { checked: Int! }",
                fieldResolvers = { schema ->
                    val checked = schema.loweredSchema.requireObjectField("Query", "checked")
                    mapOf(
                        checked to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 11 },
                    )
                },
                fieldCheckers = { schema ->
                    val checked = schema.loweredSchema.requireObjectField("Query", "checked")
                    mapOf(
                        checked to
                            FieldCheckerResolver.of(checked, schema.loweredSchema.requireQueryTypeDef()) { _, _, _ ->
                                checkerCalls += 1
                                CheckerResult.Success
                            },
                    )
                },
            )
        val world = worldFixture.assumptions
        val checked = worldFixture.schema.requireObjectField("Query", "checked")
        val checkedKey = ObjectEngineResult.GroundKey.of(checked, emptyMap())
        val query = worldFixture.schemas.fragmentFrom("fragment Query on Query { checked }")

        fun result(checkerResult: CheckerResult): ObjectEngineResult =
            ObjectEngineResult.of(
                type = world.schema.requireQueryTypeDef(),
                values = mapOf(checkedKey to 11),
                fieldCheckerResults = mapOf(checkedKey to checkerResult),
            )

        assertFalse(
            result(CorrectnessDenial()).correctResolution(
                SharedOperationContext.create(world),
                query,
            ),
        )
        assertTrue(
            result(CheckerResult.Success).correctResolution(
                SharedOperationContext.create(world),
                query,
            ),
        )
        assertEquals(2, checkerCalls)
    }

    @Test
    fun `checker query fragment witness participates in replay`() {
        val worldFixture = TestWorld
            .fromSDL(
                schemaSDL = "type Query { source: Int! checked: Int! }",
                fieldResolvers = { schema ->
                    mapOf(
                        schema.loweredSchema.requireObjectField("Query", "source") to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 7 },
                        schema.loweredSchema.requireObjectField("Query", "checked") to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 11 },
                    )
                },
                fieldCheckers = { schema ->
                    val checked = schema.loweredSchema.requireObjectField("Query", "checked")
                    mapOf(
                        checked to
                            FieldCheckerResolver.of(
                                field = checked,
                                queryType = schema.loweredSchema.requireQueryTypeDef(),
                                fragmentTemplates =
                                    mapOf(
                                        "input" to
                                            ResolverFragmentTemplates(
                                                objectFragmentTemplate = materializeSelectionForestOf(),
                                                queryFragmentTemplate =
                                                    schema
                                                        .fragmentFrom(
                                                            "fragment Input on Query { querySource: source }",
                                                        ).materializeSelections,
                                            ),
                                    ),
                            ) { _, inputs, _ ->
                                check(inputs.getValue("input").queryValue.get("querySource") == 7)
                                CheckerResult.Success
                            },
                    )
                },
            )
        val world = worldFixture.assumptions
        val checked = worldFixture.schema.requireObjectField("Query", "checked")
        val checkedKey = ObjectEngineResult.GroundKey.of(checked, emptyMap())
        val result =
            ObjectEngineResult.of(
                type = world.schema.requireQueryTypeDef(),
                values = mapOf(checkedKey to 11),
                fieldCheckerResults = mapOf(checkedKey to CheckerResult.Success),
            )
        val occurrenceId = ResolverOccurrenceId.at(result, listOf(checkedKey))
        val query = worldFixture.schemas.fragmentFrom("fragment Query on Query { checked }")

        fun operation(vararg queryValues: Int): SharedOperationContext<*> {
            val observer = CorrectnessCheckerObserver()
            queryValues.forEach { value ->
                observer.onCheckerQueryFragmentPrepared(
                    ResolverTarget.FieldCheckerTarget(checked),
                    occurrenceId,
                    world.engineResultOf("Query") {
                        "source" resolvesTo value
                    },
                )
            }
            return SharedOperationContext.create(world, checkerObserver = observer)
        }

        assertFalse(result.correctResolution(operation(), query))
        assertFalse(result.correctResolution(operation(8), query))
        assertTrue(result.correctResolution(operation(7), query))
        assertFalse(result.correctResolution(operation(7, 7), query))
    }

    @Test
    fun `resolver input observations must contain access errors at the selected location`() {
        val denial = CorrectnessDenial()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = "type Query { denied: Int! consumer: Int! }",
                fieldResolvers = { schema ->
                    mapOf(
                        schema.loweredSchema.requireObjectField("Query", "denied") to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 1 },
                        schema.loweredSchema.requireObjectField("Query", "consumer") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment Input on Query { failure: denied }",
                                ),
                            ) { _, _ -> 3 },
                    )
                },
                fieldCheckers = { schema ->
                    val denied = schema.loweredSchema.requireObjectField("Query", "denied")
                    mapOf(
                        denied to
                            FieldCheckerResolver.of(denied, schema.loweredSchema.requireQueryTypeDef()) { _, _, _ ->
                                denial
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val denied = testWorld.schema.requireObjectField("Query", "denied")
        val consumer = testWorld.schema.requireObjectField("Query", "consumer")
        val deniedKey = ObjectEngineResult.GroundKey.of(denied, emptyMap())
        val consumerKey = ObjectEngineResult.GroundKey.of(consumer, emptyMap())
        val result =
            ObjectEngineResult.of(world.schema.requireQueryTypeDef(), mutable = true).apply {
                reserveCell(deniedKey).apply {
                    value.set(1)
                    fieldCheckerResult.complete(denial)
                }
                reserveCell(consumerKey).apply {
                    value.set(3)
                    fieldCheckerResult.complete(null)
                }
                freeze()
            }
        val fragment = testWorld.schemas.fragmentFrom("fragment Query on Query { consumer }")
        val resolver = world.resolverRegistry.resolver(consumer)
        val occurrenceId = ResolverOccurrenceId.at(result, listOf(consumerKey))
        val inputSelections = resolver.instantiateObjectMaterializationSelections(occurrenceId)

        fun operation(inputValue: Any): SharedOperationContext<*> {
            val observer = CorrectnessResolverObserver()
            val emptyInput = engineObjectDataOf(world.schema.requireQueryTypeDef())
            val emptySelections = world.emptyFragmentOf("Query").materializeSelections
            observer.onResolverInvocation(
                ResolverInvocationObservation(
                    occurrencePath = listOf(deniedKey),
                    field = denied,
                    input = emptyInput,
                    inputSelections = emptySelections,
                    queryValue = emptyInput,
                    queryInputSelections = emptySelections,
                    arguments = Arguments.Resolved.of(denied, emptyMap()),
                    suppliedDemand = null,
                    resolverOccurrenceId = ResolverOccurrenceId.at(result, listOf(deniedKey)),
                ),
            )
            observer.onResolverInvocation(
                ResolverInvocationObservation(
                    occurrencePath = listOf(consumerKey),
                    field = consumer,
                    input =
                        materializedEngineObjectDataOf(
                            world.schema.requireQueryTypeDef(),
                            listOf(EngineObjectDataEntry.of("failure", denied, inputValue)),
                        ),
                    inputSelections = inputSelections,
                    queryValue = engineObjectDataOf(world.schema.requireQueryTypeDef()),
                    queryInputSelections = world.emptyFragmentOf("Query").materializeSelections,
                    arguments = Arguments.Resolved.of(consumer, emptyMap()),
                    suppliedDemand = null,
                    resolverOccurrenceId = occurrenceId,
                ),
            )
            return SharedOperationContext.create(world, resolverObserver = observer)
        }

        assertFalse(result.correctResolution(operation(1), fragment))
        assertTrue(
            result.correctResolution(
                operation(EngineErrorData.of(denial.error)),
                fragment,
            ),
            "Expected matching access-error input evidence",
        )
    }

    @Test
    fun `resolver fromArgument binding must agree with its owning arguments`() {
        val worldFixture =
            TestWorld.fromDSL(
                selectiveResolvers = true,
                schemaSDL =
                    """
                    extend type Query {
                      consumer(seed: Int!): Int!
                        @resolver(
                          of: "source(value: ${'$'}seed)"
                          result: "sum(source)"
                        )
                      source(value: Int!): Int!
                        @resolver(result: "sum(${'$'}value)")
                    }
                    """.trimIndent(),
            )
        val world = worldFixture.assumptions
        val operation = SharedOperationContext.create(world)
        val consumer = worldFixture.schema.requireObjectField("Query", "consumer")
        val source = worldFixture.schema.requireObjectField("Query", "source")
        val consumerKey =
            ObjectEngineResult.GroundKey.of(
                consumer,
                mapOf("seed" to 7),
            )
        val result =
            ObjectEngineResult.of(
                type = world.schema.requireQueryTypeDef(),
                mutable = true,
            )
        val occurrenceId = ResolverOccurrenceId.at(result, listOf(consumerKey))
        val variable = Arguments.Variable.of(consumer, "seed").instantiate(occurrenceId)
        operation.variableBindings.bindVariable(requireNotNull(variable.instanceId), 99)
        val symbolicSourceKey =
            ObjectEngineResult.ObjectKey.of(
                field = source,
                arguments = Arguments.of(source, mapOf("value" to variable)),
            )
        result.reserveCell(symbolicSourceKey).apply {
            value.set(99)
            fieldCheckerResult.complete(null)
        }
        result.reserveCell(consumerKey).apply {
            value.set(99)
            fieldCheckerResult.complete(null)
        }
        result.freeze()
        val selections =
            worldFixture.schemas.fragmentFrom(
                """
                    fragment ignored on Query {
                      consumer(seed: 7)
                    }
                """.trimIndent(),
            ).subselections
                .merge(world.schema.requireQueryTypeDef())

        assertFalse(result.correctResolution(operation, selections))
    }

    @Test
    fun `invalid result root is rejected before resolver replay`() {
        val worldFixture = TestWorld
            .fromSDL(
                """
                    type Query { profile: Profile! }
                    type Profile { value: Int! }
                """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.loweredSchema.requireObjectField("Profile", "value") to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Profile")) { _, _ ->
                                error("A non-Query root must be rejected before replay")
                            },
                    )
                },
            )
        val world = worldFixture.assumptions
        val result = world.engineResultOf("Profile") { "value" resolvesTo 7 }
        val query = worldFixture.schemas.fragmentFrom("fragment ignored on Query { profile { value } }")

        assertFalse(result.correctResolution(SharedOperationContext.create(world), query))
    }

    @Test
    fun `missing client selection is rejected before replaying existing fields`() {
        val worldFixture = TestWorld
            .fromSDL(
                "type Query { existing: Int! missing: Int! }",
                fieldResolvers = { schema ->
                    mapOf(
                        schema.loweredSchema.requireObjectField("Query", "existing") to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                error("Selection validation must precede replay")
                            },
                    )
                },
            )
        val world = worldFixture.assumptions
        val result = world.engineResultOf("Query") { "existing" resolvesTo 7 }
        val query = worldFixture.schemas.fragmentFrom("fragment ignored on Query { existing missing }")

        assertFalse(result.correctResolution(SharedOperationContext.create(world), query))
    }

    @Test
    fun `missing resolver input is rejected before invoking its relation`() {
        val worldFixture = TestWorld
            .fromSDL(
                "type Query { source: Int! consumer: Int! }",
                fieldResolvers = { schema ->
                    mapOf(
                        schema.loweredSchema.requireObjectField("Query", "consumer") to
                            fieldResolverOf(
                                schema.fragmentFrom("fragment ignored on Query { source }"),
                            ) { _, _ ->
                                error("Missing resolver demand must be rejected before replay")
                            },
                    )
                },
            )
        val world = worldFixture.assumptions
        val result = world.engineResultOf("Query") { "consumer" resolvesTo 7 }
        val query = worldFixture.schemas.fragmentFrom("fragment ignored on Query { consumer }")

        assertFalse(result.correctResolution(SharedOperationContext.create(world), query))
    }

    @Test
    fun `closed but corrupted scalar output fails conformance with one replay`() {
        var replays = 0
        val worldFixture = TestWorld
            .fromSDL(
                "type Query { value: Int! }",
                fieldResolvers = { schema ->
                    mapOf(
                        schema.loweredSchema.requireObjectField("Query", "value") to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                replays += 1
                                7
                            },
                    )
                },
            )
        val world = worldFixture.assumptions
        val result = world.engineResultOf("Query") { "value" resolvesTo 8 }
        val query = worldFixture.schemas.fragmentFrom("fragment ignored on Query { value }")

        assertFalse(result.correctResolution(SharedOperationContext.create(world), query))
        assertEquals(1, replays, "Demand and conformance must share their replay")
    }

    @Test
    fun `parent backedge must name the exact containing occurrence`() {
        val worldFixture = TestWorld
            .fromSDL(
                """
                    directive @parent on FIELD_DEFINITION
                    type Query { root: Root! }
                    type Root { child: Child! }
                    type Child { parent: Root @parent }
                """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.loweredSchema.requireObjectField("Query", "root") to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                schema.loweredSchema.objectOf("Root")
                            },
                        schema.loweredSchema.requireObjectField("Root", "child") to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Root")) { _, _ ->
                                schema.loweredSchema.objectOf("Child")
                            },
                    )
                },
            )
        val world = worldFixture.assumptions
        val operation = SharedOperationContext.create(world)
        val query = worldFixture.schema.emptyFragmentOf("Query")

        fun result(correctParent: Boolean): ObjectEngineResult {
            val root =
                ObjectEngineResult.of(
                    world.schema.requireType("Root") as ViaductSchema.Object,
                    mutable = true,
                )
            val child =
                ObjectEngineResult.of(
                    world.schema.requireType("Child") as ViaductSchema.Object,
                    mutable = true,
                )
            val parentKey =
                ObjectEngineResult.ParentKey.of(
                    world.schema.requireObjectField("Child", "parent"),
                )
            child.reserveCell(parentKey).apply {
                value.set(if (correctParent) root else world.engineResultOf("Root"))
                fieldCheckerResult.complete(null)
            }
            child.freeze()
            val childKey =
                ObjectEngineResult.GroundKey.of(
                    world.schema.requireObjectField("Root", "child"),
                    emptyMap(),
                )
            root.setCellValue(childKey, child)
            root.getCell(childKey).fieldCheckerResult.complete(null)
            root.freeze()
            return world.engineResultOf("Query") { "root" resolvesTo root }
        }

        assertTrue(result(true).correctResolution(operation, query))
        assertFalse(result(false).correctResolution(operation, query))
    }

    @Test
    fun `nested Query results replay independently without poisoning later judgments`() {
        val replays = mutableListOf<String>()
        val worldFixture = TestWorld
            .fromSDL(
                "type Query { source: Int! left: Int! right: Int! }",
                fieldResolvers = { schema ->
                    buildMap {
                        put(
                            schema.loweredSchema.requireObjectField("Query", "source"),
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                replays += "source"
                                7
                            },
                        )
                        for (name in listOf("left", "right")) {
                            put(
                                schema.loweredSchema.requireObjectField("Query", name),
                                fieldResolverOf(
                                    objectFragment = schema.loweredSchema.emptyFragmentOf("Query"),
                                    queryFragment =
                                        schema.fragmentFrom(
                                            "fragment ignored on Query { aliased: source }",
                                        ),
                                ) { _, queryValue, _ ->
                                    replays += name
                                    queryValue.get("aliased")
                                },
                            )
                        }
                    }
                },
            )
        val world = worldFixture.assumptions
        val query = worldFixture.schemas.fragmentFrom("fragment ignored on Query { left right }")
        val result =
            world.engineResultOf("Query") {
                "left" resolvesTo 7
                "right" resolvesTo 7
            }

        fun operation(rightValue: Int): SharedOperationContext<*> {
            val operation =
                SharedOperationContext.create(
                    world,
                    resolverObserver = CorrectnessResolverObserver(),
                )
            for (name in listOf("left", "right")) {
                val key =
                    ObjectEngineResult.GroundKey.of(
                        world.schema.requireObjectField("Query", name),
                        emptyMap(),
                    )
                operation.resolverObserver.onQueryFragmentPrepared(
                    ResolverOccurrenceId.at(result, listOf(key)),
                    world.engineResultOf("Query") {
                        "source" resolvesTo if (name == "right") rightValue else 7
                    },
                )
            }
            return operation
        }

        val validOperation = operation(7)
        repeat(2) {
            replays.clear()
            assertTrue(result.correctResolution(validOperation, query))
            assertEquals(
                mapOf("source" to 2, "left" to 1, "right" to 1),
                replays.groupingBy { it }.eachCount(),
            )
        }

        replays.clear()
        assertFalse(result.correctResolution(operation(8), query))
        assertEquals(
            mapOf("source" to 2, "left" to 1),
            replays.groupingBy { it }.eachCount(),
        )

        replays.clear()
        assertTrue(result.correctResolution(validOperation, query))
        assertEquals(
            mapOf("source" to 2, "left" to 1, "right" to 1),
            replays.groupingBy { it }.eachCount(),
        )
    }

    private companion object {
        val SCHEMA_SDL =
            """
            type Profile {
              name: String!
            }

            type Query {
              profile: Profile!
            }
            """.trimIndent()
    }
}

private class CorrectnessDenial : CheckerResult.Error {
    override val error = IllegalStateException("denied")

    override fun isErrorForResolver(ctx: CheckerResultContext): Boolean = true

    override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = this
}
