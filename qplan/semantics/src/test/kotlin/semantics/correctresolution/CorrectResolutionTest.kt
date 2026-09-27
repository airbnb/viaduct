package semantics.correctresolution

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import model.Arguments
import model.EngineErrorData
import model.EngineObjectDataEntry
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.emptyFragmentOf
import model.engineObjectDataOf
import model.materializedEngineObjectDataOf
import model.engineResultOf
import model.fragmentFrom
import model.materializeSelectionForestOf
import model.merge
import model.objectOf
import model.registry.ResolutionExecutionContext
import model.registry.FieldChecker
import model.registry.ResolverFragmentTemplates
import model.requireObjectField
import model.requireQueryTypeDef
import model.requireType
import model.selectionForestOf
import model.ObjectSelectionForest
import model.testing.fieldResolverOf
import model.testing.selectiveFieldResolverOf
import viaduct.graphql.schema.ViaductSchema
import model.testing.TestWorld
import semantics.resolver26.resolve
import semantics.resolver26.Resolver26DispatcherResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import semantics.shared.SharedOperationContext
import semantics.shared.ResolverInvocationObservation
import semantics.shared.OEROccurrence
import semantics.shared.SharedOERContext
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext

class CorrectResolutionTest : Resolver26DispatcherResource {
    @Test
    fun `direct resolve invocation and invocation through correctness replay do not cause invocation observations`() = runBlocking {
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
        val fragment = world.fragmentFrom("fragment Main on Query { value }")
        val root = operation.resolveWithTestDispatcher(fragment.subselections)
        assertEquals(1, events.size)
        repeat(2) { assertTrue(root.correctResolution(operation, fragment)) }
        val field = world.schema.requireObjectField("Query", "value")
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
                    val user = schema.requireObjectField("Query", "user")
                    mapOf(
                        user to
                            selectiveFieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                function = { _, _, selections ->
                                    val fields = mutableSetOf<String>()
                                    selections.forEach { selection -> fields += selection.key.field.name }
                                    observedFields += fields
                                    schema.objectOf("User") {
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
            world
                .fragmentFrom(
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
        val world = TestWorld.fromSDL(SCHEMA_SDL).assumptions
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
                    val source = schema.requireObjectField("Query", "source")
                    val extra = schema.requireObjectField("Query", "extra")
                    val consumer = schema.requireObjectField("Query", "consumer")
                    mapOf(
                        source to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 7 },
                        extra to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 9 },
                        consumer to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
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
        val consumer = world.schema.requireObjectField("Query", "consumer")
        val consumerKey = ObjectEngineResult.GroundKey.of(consumer, emptyMap())
        val selections =
            world
                .fragmentFrom(
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
                    world
                        .fragmentFrom("fragment Scope on Query { source }")
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
        val world =
            TestWorld.fromSDL(
                schemaSDL = "type Query { checked: Int! objectSource: Int! }",
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireObjectField("Query", "checked") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 11 },
                        schema.requireObjectField("Query", "objectSource") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 7 },
                    )
                },
                fieldCheckers = { schema ->
                    val checked = schema.requireObjectField("Query", "checked")
                    mapOf(
                        checked to
                            FieldChecker.of(
                                field = checked,
                                queryType = schema.requireQueryTypeDef(),
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
            ).assumptions
        val checked = world.schema.requireObjectField("Query", "checked")
        val checkedKey = ObjectEngineResult.GroundKey.of(checked, emptyMap())
        val selections =
            world
                .fragmentFrom("fragment Query on Query { checked }")
                .subselections
                .merge(world.schema.requireQueryTypeDef())

        fun result(
            includeObjectSource: Boolean,
            includeCheckerSlot: Boolean = true,
        ): ObjectEngineResult =
            ObjectEngineResult.of(world.schema.requireQueryTypeDef(), mutable = true).apply {
                reserveCell(checkedKey).apply {
                    setValue(11)
                    if (includeCheckerSlot) setFieldCheckerResult(CheckerResult.Success)
                }
                if (includeObjectSource) {
                    setCellValue(
                        ObjectEngineResult.GroundKey.of(
                            world.schema.requireObjectField("Query", "objectSource"),
                            emptyMap(),
                        ),
                        7,
                    )
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
        val world =
            TestWorld
                .fromSDL(
                    schemaSDL = "type Query { checked: Int! }",
                    fieldResolvers = { schema ->
                        val checked = schema.requireObjectField("Query", "checked")
                        mapOf(
                            checked to
                                fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 11 },
                        )
                    },
                    fieldCheckers = { schema ->
                        val checked = schema.requireObjectField("Query", "checked")
                        mapOf(
                            checked to
                                FieldChecker.of(checked, schema.requireQueryTypeDef()) { _, _, _ ->
                                    checkerCalls += 1
                                    CheckerResult.Success
                                },
                        )
                    },
                ).assumptions
        val checked = world.schema.requireObjectField("Query", "checked")
        val checkedKey = ObjectEngineResult.GroundKey.of(checked, emptyMap())
        val query = world.fragmentFrom("fragment Query on Query { checked }")

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
        val world =
            TestWorld
                .fromSDL(
                    schemaSDL = "type Query { source: Int! checked: Int! }",
                    fieldResolvers = { schema ->
                        mapOf(
                            schema.requireObjectField("Query", "source") to
                                fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 7 },
                            schema.requireObjectField("Query", "checked") to
                                fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 11 },
                        )
                    },
                    fieldCheckers = { schema ->
                        val checked = schema.requireObjectField("Query", "checked")
                        mapOf(
                            checked to
                                FieldChecker.of(
                                    field = checked,
                                    queryType = schema.requireQueryTypeDef(),
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
                ).assumptions
        val checked = world.schema.requireObjectField("Query", "checked")
        val checkedKey = ObjectEngineResult.GroundKey.of(checked, emptyMap())
        val result =
            ObjectEngineResult.of(
                type = world.schema.requireQueryTypeDef(),
                values = mapOf(checkedKey to 11),
                fieldCheckerResults = mapOf(checkedKey to CheckerResult.Success),
            )
        val occurrenceId = ResolverOccurrenceId.at(result, listOf(checkedKey))
        val query = world.fragmentFrom("fragment Query on Query { checked }")

        fun operation(vararg queryValues: Int): SharedOperationContext<*> {
            val observer = CorrectnessCheckerObserver()
            queryValues.forEach { value ->
                observer.onCheckerQueryFragmentPrepared(
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
                        schema.requireObjectField("Query", "denied") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 1 },
                        schema.requireObjectField("Query", "consumer") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment Input on Query { failure: denied }",
                                ),
                            ) { _, _ -> 3 },
                    )
                },
                fieldCheckers = { schema ->
                    val denied = schema.requireObjectField("Query", "denied")
                    mapOf(
                        denied to
                            FieldChecker.of(denied, schema.requireQueryTypeDef()) { _, _, _ ->
                                denial
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val denied = world.schema.requireObjectField("Query", "denied")
        val consumer = world.schema.requireObjectField("Query", "consumer")
        val deniedKey = ObjectEngineResult.GroundKey.of(denied, emptyMap())
        val consumerKey = ObjectEngineResult.GroundKey.of(consumer, emptyMap())
        val result =
            ObjectEngineResult.of(world.schema.requireQueryTypeDef(), mutable = true).apply {
                reserveCell(deniedKey).apply {
                    setValue(1)
                    setFieldCheckerResult(denial)
                }
                setCellValue(consumerKey, 3)
                freeze()
            }
        val fragment = world.fragmentFrom("fragment Query on Query { consumer }")
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
        val world =
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
            ).assumptions
        val operation = SharedOperationContext.create(world)
        val consumer = world.schema.requireObjectField("Query", "consumer")
        val source = world.schema.requireObjectField("Query", "source")
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
            setValue(99)
        }
        result.reserveCell(consumerKey).apply {
            setValue(99)
        }
        result.freeze()
        val selections =
            world
                .fragmentFrom(
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
        val world =
            TestWorld
                .fromSDL(
                    """
                    type Query { profile: Profile! }
                    type Profile { value: Int! }
                    """.trimIndent(),
                    fieldResolvers = { schema ->
                        mapOf(
                            schema.requireObjectField("Profile", "value") to
                                fieldResolverOf(schema.emptyFragmentOf("Profile")) { _, _ ->
                                    error("A non-Query root must be rejected before replay")
                                },
                        )
                    },
                ).assumptions
        val result = world.engineResultOf("Profile") { "value" resolvesTo 7 }
        val query = world.fragmentFrom("fragment ignored on Query { profile { value } }")

        assertFalse(result.correctResolution(SharedOperationContext.create(world), query))
    }

    @Test
    fun `missing client selection is rejected before replaying existing fields`() {
        val world =
            TestWorld
                .fromSDL(
                    "type Query { existing: Int! missing: Int! }",
                    fieldResolvers = { schema ->
                        mapOf(
                            schema.requireObjectField("Query", "existing") to
                                fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                    error("Selection validation must precede replay")
                                },
                        )
                    },
                ).assumptions
        val result = world.engineResultOf("Query") { "existing" resolvesTo 7 }
        val query = world.fragmentFrom("fragment ignored on Query { existing missing }")

        assertFalse(result.correctResolution(SharedOperationContext.create(world), query))
    }

    @Test
    fun `missing resolver input is rejected before invoking its relation`() {
        val world =
            TestWorld
                .fromSDL(
                    "type Query { source: Int! consumer: Int! }",
                    fieldResolvers = { schema ->
                        mapOf(
                            schema.requireObjectField("Query", "consumer") to
                                fieldResolverOf(
                                    schema.fragmentFrom("fragment ignored on Query { source }"),
                                ) { _, _ ->
                                    error("Missing resolver demand must be rejected before replay")
                                },
                        )
                    },
                ).assumptions
        val result = world.engineResultOf("Query") { "consumer" resolvesTo 7 }
        val query = world.fragmentFrom("fragment ignored on Query { consumer }")

        assertFalse(result.correctResolution(SharedOperationContext.create(world), query))
    }

    @Test
    fun `closed but corrupted scalar output fails conformance with one replay`() {
        var replays = 0
        val world =
            TestWorld
                .fromSDL(
                    "type Query { value: Int! }",
                    fieldResolvers = { schema ->
                        mapOf(
                            schema.requireObjectField("Query", "value") to
                                fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                    replays += 1
                                    7
                                },
                        )
                    },
                ).assumptions
        val result = world.engineResultOf("Query") { "value" resolvesTo 8 }
        val query = world.fragmentFrom("fragment ignored on Query { value }")

        assertFalse(result.correctResolution(SharedOperationContext.create(world), query))
        assertEquals(1, replays, "Demand and conformance must share their replay")
    }

    @Test
    fun `parent backedge must name the exact containing occurrence`() {
        val world =
            TestWorld
                .fromSDL(
                    """
                    directive @parent on FIELD_DEFINITION
                    type Query { child: Child! }
                    type Child { parent: Query @parent }
                    """.trimIndent(),
                    fieldResolvers = { schema ->
                        mapOf(
                            schema.requireObjectField("Query", "child") to
                                fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                    schema.objectOf("Child")
                                },
                        )
                    },
                ).assumptions
        val operation = SharedOperationContext.create(world)
        val query = world.schema.emptyFragmentOf("Query")

        fun result(correctParent: Boolean): ObjectEngineResult {
            val root =
                ObjectEngineResult.of(
                    world.schema.requireQueryTypeDef(),
                    mutable = true,
                )
            val child =
                ObjectEngineResult.of(
                    world.schema.requireType("Child") as ViaductSchema.Object,
                    mutable = true,
                )
            child.setCellValue(
                ObjectEngineResult.ParentKey.of(
                    world.schema.requireObjectField("Child", "parent"),
                ),
                if (correctParent) root else world.engineResultOf("Query"),
            )
            child.freeze()
            root.setCellValue(
                ObjectEngineResult.GroundKey.of(
                    world.schema.requireObjectField("Query", "child"),
                    emptyMap(),
                ),
                child,
            )
            root.freeze()
            return root
        }

        assertTrue(result(true).correctResolution(operation, query))
        assertFalse(result(false).correctResolution(operation, query))
    }

    @Test
    fun `nested Query results replay independently without poisoning later judgments`() {
        val replays = mutableListOf<String>()
        val world =
            TestWorld
                .fromSDL(
                    "type Query { source: Int! left: Int! right: Int! }",
                    fieldResolvers = { schema ->
                        buildMap {
                            put(
                                schema.requireObjectField("Query", "source"),
                                fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                    replays += "source"
                                    7
                                },
                            )
                            for (name in listOf("left", "right")) {
                                put(
                                    schema.requireObjectField("Query", name),
                                    fieldResolverOf(
                                        objectFragment = schema.emptyFragmentOf("Query"),
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
                ).assumptions
        val query = world.fragmentFrom("fragment ignored on Query { left right }")
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
