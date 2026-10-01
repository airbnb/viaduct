package viaduct.engine.runtime2.resolution

import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import viaduct.engine.runtime2.arbitrary.FieldCoordinate
import viaduct.engine.runtime2.arbitrary.ResolutionOccurrenceApplicationLog
import viaduct.engine.runtime2.arbitrary.ResolutionOccurrenceWitness
import viaduct.engine.runtime2.contract.registeredResolverApplicationIdentityCounts
import viaduct.engine.runtime2.contract.registeredResolverOccurrenceApplicationIdentityCounts
import viaduct.engine.runtime2.contract.selectionValues
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

class ResolverOccurrenceWitnessTest {
    @Test
    fun `occurrence oracle includes one shared query root`() {
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = true,
                schemaSDL =
                    """
                    type Query {
                      source: Int!
                      first: Int!
                      second: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val source = schema.loweredSchema.requireObjectField("Query", "source")
                    val queryFragment =
                        schema.fragmentFrom(
                            "fragment SourceQuery on Query { source }",
                        )
                    mapOf(
                        source to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 7 },
                        schema.loweredSchema.requireObjectField("Query", "first") to
                            fieldResolverOf(
                                objectFragment = schema.loweredSchema.emptyFragmentOf("Query"),
                                queryFragment = queryFragment,
                            ) { _, queryValue, _ ->
                                queryValue.selectionValues().getValue("source")
                            },
                        schema.loweredSchema.requireObjectField("Query", "second") to
                            fieldResolverOf(
                                objectFragment = schema.loweredSchema.emptyFragmentOf("Query"),
                                queryFragment = queryFragment,
                            ) { _, queryValue, _ ->
                                queryValue.selectionValues().getValue("source")
                            },
                    )
                },
            )
        val world = testWorld.assumptions

        val fragment =
            testWorld.schemas.fragmentFrom(
                "fragment QueryResult on Query { first second }",
            )
        val log = ResolutionOccurrenceApplicationLog()

        val recordingObserver = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                log.record(
                    resolverOccurrenceId = observation.resolverOccurrenceId,
                    occurrencePath = observation.occurrencePath,
                    field =
                        FieldCoordinate(
                            observation.field.containingDef.name,
                            observation.field.name,
                        ),
                    arguments = observation.arguments,
                    input = observation.input,
                    suppliedDemand = observation.suppliedDemand,
                )
            }
        }
        val operation = SharedOperationContext.create(world, resolverObserver = recordingObserver)

        val result =
            operation.resolve(
                selections = fragment.subselections,
                coroutineContext = EmptyCoroutineContext,
            )
        val witness = log.snapshot()
        val expected =
            result.registeredResolverOccurrenceApplicationIdentityCounts(operation)

        assertEquals(expected, witness.applicationIdentityCounts())
        assertEquals(3, expected.values.sum())
        assertEquals(
            result.registeredResolverApplicationIdentityCounts(operation),
            witness.applications
                .groupingBy { application -> application.application.identity }
                .eachCount(),
        )
        val queryResults =
            (operation.resolverObserver as CorrectnessResolverObserver)
                .allQueryFragmentResults()
                .values
                .flatten()
        assertEquals(2, queryResults.size)
        assertSame(queryResults.first(), queryResults.last())

        val sourceApplications =
            witness.applications.filter { application ->
                application.application.key.field == FieldCoordinate("Query", "source")
            }
        val sourceApplication = sourceApplications.single()
        assertEquals(
            ResolverOccurrenceId.at(queryResults.first(), sourceApplication.occurrencePath),
            sourceApplication.resolverOccurrenceId,
        )
    }

    @Test
    fun `occurrence oracle rejects duplicate-one omit-one for equal-input list elements`() {
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = true,
                schemaSDL =
                    """
                    type Query {
                      items: [Payload!]!
                    }

                    type Payload {
                      computed: Int!
                      base: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val items = schema.loweredSchema.requireObjectField("Query", "items")
                    checkNotNull(items.type.unwrapList())
                    val baseKey =
                        ObjectEngineResult.GroundKey.of(
                            schema.loweredSchema.requireObjectField("Payload", "base"),
                            emptyMap(),
                        )
                    mapOf(
                        items to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                listOf(
                                    schema.loweredSchema.objectOf("Payload") {
                                        "base" setTo 10
                                    },
                                    schema.loweredSchema.objectOf("Payload") {
                                        "base" setTo 10
                                    },
                                )
                            },
                        schema.loweredSchema.requireObjectField("Payload", "computed") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment PayloadInput on Payload { base }",
                                ),
                            ) { input, _ ->
                                input.selectionValues().getValue(baseKey.field.name)
                            },
                    )
                },
            )
        val world = testWorld.assumptions

        val fragment =
            testWorld.schemas.fragmentFrom(
                "fragment QueryResult on Query { items { computed } }",
            )
        val log = ResolutionOccurrenceApplicationLog()

        val recordingObserver = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                log.record(
                    resolverOccurrenceId = observation.resolverOccurrenceId,
                    occurrencePath = observation.occurrencePath,
                    field =
                        FieldCoordinate(
                            observation.field.containingDef.name,
                            observation.field.name,
                        ),
                    arguments = observation.arguments,
                    input = observation.input,
                    suppliedDemand = observation.suppliedDemand,
                )
            }
        }
        val operation = SharedOperationContext.create(world, resolverObserver = recordingObserver)

        val result: ObjectEngineResult =
            operation.resolve(
                selections = fragment.subselections,
                coroutineContext = EmptyCoroutineContext,
            )
        val witness = log.snapshot()
        val expected =
            result.registeredResolverOccurrenceApplicationIdentityCounts(operation)

        assertEquals(expected, witness.applicationIdentityCounts())

        val computedApplications =
            witness.applications.filter { application ->
                application.application.key.field ==
                    FieldCoordinate("Payload", "computed")
            }
        assertEquals(2, computedApplications.size)
        assertEquals(
            1,
            computedApplications
                .map { application -> application.application.identity }
                .toSet()
                .size,
            "The two list positions must have equal field, arguments, and materialized input",
        )
        val first =
            computedApplications.single { application ->
                ListEngineResult.Index.of(0) in application.occurrencePath
            }
        val second =
            computedApplications.single { application ->
                ListEngineResult.Index.of(1) in application.occurrencePath
            }
        val malformed =
            ResolutionOccurrenceWitness(
                witness.applications.map { application ->
                    if (application == second) first else application
                },
            )

        assertNotEquals(
            expected,
            malformed.applicationIdentityCounts(),
            "Duplicating list position 0 and omitting position 1 must fail the occurrence oracle",
        )
    }
}
