package viaduct.engine.runtime2.arbitrary

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.runtime2.contract.RegisteredResolverOccurrence
import viaduct.engine.runtime2.contract.forEachRegisteredResolverOccurrence
import viaduct.engine.runtime2.contract.registeredResolverOccurrenceCounts
import viaduct.engine.runtime2.contract.registeredResolverOccurrences
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireField
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.engineResultOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.graphql.schema.ViaductSchema

class ResolutionWitnessTest {
    @Test
    fun `fingerprints ignore permutations and discriminate semantic input differences`() {
        val world = fingerprintWorld()
        val schema = world.schemas
        val search = schema.loweredSchema.requireField("Query", "search")
        val baseArguments =
            arguments(
                search,
                limit = 3,
                rank = 7,
                tags = listOf(1, 2),
                reverseFieldOrder = false,
            )
        val reorderedArguments =
            arguments(
                search,
                limit = 3,
                rank = 7,
                tags = listOf(1, 2),
                reverseFieldOrder = true,
            )

        assertEquals(
            baseArguments.resolutionFingerprint(search),
            reorderedArguments.resolutionFingerprint(search),
        )
        assertNotEquals(
            baseArguments.resolutionFingerprint(search),
            arguments(search, limit = 4, rank = 7, tags = listOf(1, 2))
                .resolutionFingerprint(search),
            "Distinct top-level arguments must remain distinguishable",
        )
        assertNotEquals(
            baseArguments.resolutionFingerprint(search),
            arguments(search, limit = 3, rank = 8, tags = listOf(1, 2))
                .resolutionFingerprint(search),
            "Distinct nested input values must remain distinguishable",
        )
        assertNotEquals(
            baseArguments.resolutionFingerprint(search),
            arguments(search, limit = 3, rank = 7, tags = listOf(2, 1))
                .resolutionFingerprint(search),
            "Input-list order must remain significant",
        )

        val leftThenRight =
            schema.loweredSchema.objectOf("Query") {
                "left" setTo 1
                "right" setTo 2
            }
        val rightThenLeft =
            schema.loweredSchema.objectOf("Query") {
                "right" setTo 2
                "left" setTo 1
            }
        assertEquals(
            leftThenRight.resolutionFingerprint(),
            rightThenLeft.resolutionFingerprint(),
        )

        val firstThenSecond =
            world.selectionsFrom(
                """
                fragment ignored on Query {
                  first: search(
                    filter: { nested: { rank: 7 }, enabled: true }
                    tags: [1, 2]
                    limit: 3
                  )
                  second: search(
                    filter: { nested: { rank: 8 }, enabled: false }
                    tags: [2, 1]
                    limit: 4
                  )
                }
                """.trimIndent(),
            ).second
        val secondThenFirst =
            world.selectionsFrom(
                """
                fragment ignored on Query {
                  second: search(
                    filter: { enabled: false, nested: { rank: 8 } }
                    limit: 4
                    tags: [2, 1]
                  )
                  first: search(
                    tags: [1, 2]
                    limit: 3
                    filter: { enabled: true, nested: { rank: 7 } }
                  )
                }
                """.trimIndent(),
            ).second
        assertEquals(
            firstThenSecond.resolutionFingerprint(),
            secondThenFirst.resolutionFingerprint(),
        )
    }

    @Test
    fun `recorder preserves exact multiplicity snapshots and bounds`() {
        val world = fingerprintWorld()
        val schema = world.schemas
        val search = schema.loweredSchema.requireField("Query", "search")
        val firstArguments = arguments(search, limit = 3, rank = 7, tags = listOf(1, 2))
        val secondArguments = arguments(search, limit = 4, rank = 7, tags = listOf(1, 2))
        val input =
            schema.loweredSchema.objectOf("Query") {
                "left" setTo 1
                "right" setTo 2
            }
        val sourceField = FieldCoordinate("Query", "search")
        val log = BoundedRecorder<ResolverApplicationRecord>(maxEntries = 3)

        log.record(ResolverApplicationRecord.capture(sourceField, firstArguments, input))
        log.record(ResolverApplicationRecord.capture(sourceField, firstArguments, input))
        log.record(ResolverApplicationRecord.capture(sourceField, secondArguments, input))
        val snapshot = ResolutionWitness(log.snapshot())
        val firstKey = ResolverApplicationKey(sourceField, firstArguments)
        val secondKey = ResolverApplicationKey(sourceField, secondArguments)

        assertEquals(
            mapOf(firstKey to 2, secondKey to 1),
            snapshot.applicationCounts(),
        )
        assertEquals(mapOf(firstKey to 2), snapshot.duplicateApplications())
        assertEquals(
            List(3) { input.resolutionFingerprint() },
            snapshot.applications.map(ResolverApplicationRecord::inputFingerprint),
        )

        val failure = assertThrows<ResolutionWitnessBoundExceededException> {
            log.record(snapshot.applications.first())
        }
        assertEquals("Resolution witness exceeded application bound of 3", failure.message)
        assertEquals(snapshot.applications, log.snapshot())

        log.clear()
        assertTrue(log.snapshot().isEmpty())
        log.record(snapshot.applications.first())
        assertEquals(listOf(snapshot.applications.first()), log.snapshot())
        assertEquals(3, snapshot.applications.size, "Snapshots must survive clearing and reusing the recorder")
    }

    @Test
    fun `recorder restores nested pauses after failure even when full`() {
        val recorder = BoundedRecorder<Int>(maxEntries = 1)
        recorder.record(1)
        val failure = IllegalStateException("comparison failed")
        assertSame(
            failure,
            assertThrows<IllegalStateException> {
                recorder.withoutRecording {
                    assertFalse(recorder.isRecording)
                    assertEquals(
                        7,
                        recorder.withoutRecording {
                            recorder.record(2)
                            7
                        }
                    )
                    assertFalse(recorder.isRecording)
                    recorder.record(3)
                    throw failure
                }
            }
        )
        assertTrue(recorder.isRecording)
        assertEquals(listOf(1), recorder.snapshot())
        assertThrows<ResolutionWitnessBoundExceededException> { recorder.record(4) }
    }

    @Test
    fun `recorder enforces capacity under concurrent writes`() {
        val recorder = BoundedRecorder<Int>(maxEntries = 64)
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val applications = (1..1_000).map { entry ->
                executor.submit<Boolean> {
                    start.await()
                    try {
                        recorder.record(entry)
                        true
                    } catch (_: ResolutionWitnessBoundExceededException) {
                        false
                    }
                }
            }
            start.countDown()
            assertEquals(64, applications.count { it.get() })
            assertEquals(64, recorder.snapshot().size)
            assertEquals(64, recorder.snapshot().toSet().size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `recorder validates capacity and retains selective node overflow label`() {
        for (capacity in listOf(0, -1)) {
            assertThrows<IllegalArgumentException> { BoundedRecorder<Int>(maxEntries = capacity) }
        }
        val recorder = BoundedRecorder<Int>(maxEntries = 1, boundName = "selective-node application")
        recorder.record(1)
        val failure = assertThrows<ResolutionWitnessBoundExceededException> { recorder.record(2) }
        assertEquals("Resolution witness exceeded selective-node application bound of 1", failure.message)
        assertEquals(listOf(1), recorder.snapshot())
    }

    @Test
    fun `result traversal counts nested list occurrences and closure follows demand edges`() {
        val world = traversalWorld()
        val schema = world.schemas

        fun payload(
            scale: Int,
            value: Int,
        ): ObjectEngineResult =
            schema.loweredSchema.engineResultOf("Payload") {
                field("computed", "scale" to scale) resolvesTo value
                "base" resolvesTo value
            }

        val result =
            schema.loweredSchema.engineResultOf("Query") {
                "item" resolvesTo payload(scale = 1, value = 10)
                "items" resolvesTo
                    listOf(
                        payload(scale = 1, value = 20),
                        payload(scale = 2, value = 30),
                    )
                "helper" resolvesTo 40
            }
        val itemKey =
            ResolverApplicationKey(
                FieldCoordinate("Query", "item"),
                Arguments.Resolved.of(schema.loweredSchema.requireField("Query", "item"), emptyMap()),
            )
        val itemsKey =
            ResolverApplicationKey(
                FieldCoordinate("Query", "items"),
                Arguments.Resolved.of(schema.loweredSchema.requireField("Query", "items"), emptyMap()),
            )
        val helperKey =
            ResolverApplicationKey(
                FieldCoordinate("Query", "helper"),
                Arguments.Resolved.of(schema.loweredSchema.requireField("Query", "helper"), emptyMap()),
            )
        val computedOneKey =
            ResolverApplicationKey(
                FieldCoordinate("Payload", "computed"),
                Arguments.Resolved.of(
                    schema.loweredSchema.requireField("Payload", "computed"),
                    mapOf("scale" to 1),
                ),
            )
        val computedTwoKey =
            ResolverApplicationKey(
                FieldCoordinate("Payload", "computed"),
                Arguments.Resolved.of(
                    schema.loweredSchema.requireField("Payload", "computed"),
                    mapOf("scale" to 2),
                ),
            )
        val baseKey =
            ResolverApplicationKey(
                FieldCoordinate("Payload", "base"),
                Arguments.Resolved.of(schema.loweredSchema.requireField("Payload", "base"), emptyMap()),
            )

        assertEquals(
            mapOf(
                itemKey to 1,
                itemsKey to 1,
                helperKey to 1,
                computedOneKey to 2,
                computedTwoKey to 1,
                baseKey to 3,
            ),
            SharedOperationContext.create(world.assumptions).let { resolutionOperation -> result.registeredResolverOccurrenceCounts(resolutionOperation, world.resolverRegistry) },
        )
        val cells =
            SharedOperationContext.create(world.assumptions).let { resolutionOperation -> result.registeredResolverOccurrences(resolutionOperation, world.resolverRegistry) }
        val streamedCells = mutableListOf<RegisteredResolverOccurrence>()
        SharedOperationContext.create(world.assumptions).let { resolutionOperation ->
            result.forEachRegisteredResolverOccurrence(
                operation = resolutionOperation,
                registry = world.resolverRegistry,
                visitOccurrence = streamedCells::add,
            )
        }
        assertEquals(
            cells.groupingBy { cell -> cell }.eachCount(),
            streamedCells.groupingBy { cell -> cell }.eachCount(),
        )
        streamedCells.forEach { cell ->
            val groundKey = cell.occurrencePath.last() as ObjectEngineResult.GroundKey
            assertSame(groundKey.field, cell.field)
        }
        assertTrue(
            cells
                .single { cell -> cell.applicationKey == computedTwoKey }
                .occurrencePath
                .contains(ListEngineResult.Index.of(1)),
            "The second list element must retain its occurrence index",
        )

        val operation =
            schema.fragmentFrom(
                """
                fragment ignored on Query {
                  item { computed(scale: 1) }
                  items { computed(scale: 2) }
                }
                """.trimIndent(),
            )
        val allowed =
            operation.subselections.allowedResolverClosure(world.resolverRegistry)
        assertEquals(
            setOf(
                FieldCoordinate("Query", "item"),
                FieldCoordinate("Query", "items"),
                FieldCoordinate("Payload", "computed"),
            ),
            allowed.directlySelectedFields.mapTo(linkedSetOf(), ::coordinate),
        )
        assertEquals(
            setOf(
                FieldCoordinate("Query", "item"),
                FieldCoordinate("Query", "items"),
                FieldCoordinate("Query", "helper"),
                FieldCoordinate("Payload", "computed"),
                FieldCoordinate("Payload", "base"),
            ),
            allowed.canonicalFields,
        )
        assertTrue(FieldCoordinate("Query", "dead") !in allowed.canonicalFields)

        val log = BoundedRecorder<ResolverApplicationRecord>()
        val queryInput = schema.loweredSchema.objectOf("Query")
        log.record(ResolverApplicationRecord.capture(FieldCoordinate("Query", "item"), itemKey.arguments, queryInput))
        log.record(
            ResolverApplicationRecord.capture(
                FieldCoordinate("Query", "dead"),
                Arguments.Resolved.of(schema.loweredSchema.requireField("Query", "dead"), emptyMap()),
                queryInput,
            ),
        )
        assertEquals(
            listOf(FieldCoordinate("Query", "dead")),
            ResolutionWitness(log.snapshot())
                .unrelatedApplications(allowed)
                .map { application -> application.key.field },
        )
    }

    @Test
    fun `streaming traversal skips fingerprint bounds and preserves result-node bounds`() {
        val world = traversalWorld()
        val result =
            world.schema.engineResultOf("Query") {
                "helper" resolvesTo 1
                "dead" resolvesTo 2
            }
        val fingerprintBounds =
            ResolutionWitnessBounds(maxFingerprintCharacters = 1)

        assertThrows<ResolutionWitnessBoundExceededException> {
            SharedOperationContext.create(world.assumptions).let { resolutionOperation ->
                result.registeredResolverOccurrences(
                    operation = resolutionOperation,
                    registry = world.resolverRegistry,
                    bounds = fingerprintBounds,
                )
            }
        }
        val streamedCells = mutableListOf<RegisteredResolverOccurrence>()
        SharedOperationContext.create(world.assumptions).let { resolutionOperation ->
            result.forEachRegisteredResolverOccurrence(
                operation = resolutionOperation,
                registry = world.resolverRegistry,
                bounds = fingerprintBounds,
                visitOccurrence = streamedCells::add,
            )
        }
        assertEquals(2, streamedCells.size)

        assertThrows<ResolutionWitnessBoundExceededException> {
            SharedOperationContext.create(world.assumptions).let { resolutionOperation ->
                result.forEachRegisteredResolverOccurrence(
                    operation = resolutionOperation,
                    registry = world.resolverRegistry,
                    bounds = ResolutionWitnessBounds(maxResultNodes = 1),
                    visitOccurrence = {},
                )
            }
        }
    }

    @Test
    fun `application count oracle distinguishes value-distinct equal-key list occurrences`() {
        val world = traversalWorld()
        val schema = world.schemas
        val computedField = schema.loweredSchema.requireField("Payload", "computed")
        val computedKey =
            ResolverApplicationKey(
                FieldCoordinate("Payload", "computed"),
                Arguments.Resolved.of(computedField, mapOf("scale" to 1)),
            )

        fun payload(value: Int): ObjectEngineResult =
            schema.loweredSchema.engineResultOf("Payload") {
                field("computed", "scale" to 1) resolvesTo value
                "base" resolvesTo value
            }

        val result =
            schema.loweredSchema.engineResultOf("Query") {
                "items" resolvesTo listOf(payload(10), payload(20))
            }
        assertEquals(
            mapOf(computedKey to 2),
            result
                .let {
                    SharedOperationContext.create(world.assumptions).let { resolutionOperation -> it.registeredResolverOccurrenceCounts(resolutionOperation, world.resolverRegistry) }
                }
                .filterKeys { key -> key == computedKey },
        )
        val firstInput =
            schema.loweredSchema.objectOf("Payload") {
                "base" setTo 10
            }
        val secondInput =
            schema.loweredSchema.objectOf("Payload") {
                "base" setTo 20
            }
        val expected =
            mapOf(
                ResolverApplicationIdentity(
                    computedKey,
                    firstInput.resolutionFingerprint(),
                ) to 1,
                ResolverApplicationIdentity(
                    computedKey,
                    secondInput.resolutionFingerprint(),
                ) to 1,
            )

        val malformedLog = BoundedRecorder<ResolverApplicationRecord>()
        malformedLog.record(ResolverApplicationRecord.capture(computedKey.field, computedKey.arguments, firstInput))
        malformedLog.record(ResolverApplicationRecord.capture(computedKey.field, computedKey.arguments, firstInput))

        assertNotEquals(
            expected,
            ResolutionWitness(malformedLog.snapshot()).applicationIdentityCounts(),
            "Duplicating one list occurrence and omitting another must fail the one-shot oracle",
        )
    }

    @Test
    fun `application count oracle distinguishes equal-input list occurrences`() {
        val world = traversalWorld()
        val schema = world.schemas
        val itemsKey =
            ObjectEngineResult.GroundKey.of(
                schema.loweredSchema.requireObjectField("Query", "items"),
                emptyMap(),
            )
        val computedField = schema.loweredSchema.requireObjectField("Payload", "computed")
        val computedGroundKey =
            ObjectEngineResult.GroundKey.of(
                computedField,
                mapOf("scale" to 1),
            )
        val computedKey =
            ResolverApplicationKey(
                FieldCoordinate("Payload", "computed"),
                Arguments.Resolved.of(computedField, mapOf("scale" to 1)),
            )
        val input =
            schema.loweredSchema.objectOf("Payload") {
                "base" setTo 10
            }
        val firstPath =
            listOf(
                itemsKey,
                ListEngineResult.Index.of(0),
                computedGroundKey,
            )
        val secondPath =
            listOf(
                itemsKey,
                ListEngineResult.Index.of(1),
                computedGroundKey,
            )
        val applicationIdentity =
            ResolverApplicationIdentity(
                computedKey,
                input.resolutionFingerprint(),
            )
        val root = ObjectEngineResult.of(schema.loweredSchema.requireQueryTypeDef())
        val firstOccurrenceId = ResolverOccurrenceId.at(root, firstPath)
        val secondOccurrenceId = ResolverOccurrenceId.at(root, secondPath)
        val expected =
            mapOf(
                ResolverOccurrenceApplicationIdentity(firstOccurrenceId, applicationIdentity) to 1,
                ResolverOccurrenceApplicationIdentity(secondOccurrenceId, applicationIdentity) to 1,
            )
        val validLog = BoundedRecorder<ResolverOccurrenceApplicationRecord>()
        validLog.record(
            ResolverOccurrenceApplicationRecord.capture(
                firstOccurrenceId,
                firstPath,
                computedKey.field,
                computedKey.arguments,
                input,
            ),
        )
        validLog.record(
            ResolverOccurrenceApplicationRecord.capture(
                secondOccurrenceId,
                secondPath,
                computedKey.field,
                computedKey.arguments,
                input,
            ),
        )
        assertEquals(expected, ResolutionOccurrenceWitness(validLog.snapshot()).applicationIdentityCounts())

        val malformedLog = BoundedRecorder<ResolverOccurrenceApplicationRecord>()
        repeat(2) {
            malformedLog.record(
                ResolverOccurrenceApplicationRecord.capture(
                    firstOccurrenceId,
                    firstPath,
                    computedKey.field,
                    computedKey.arguments,
                    input,
                ),
            )
        }

        assertNotEquals(
            expected,
            ResolutionOccurrenceWitness(malformedLog.snapshot()).applicationIdentityCounts(),
            "Duplicating one equal-input occurrence and omitting another must be rejected",
        )
    }

    private fun fingerprintWorld(): TestWorld =
        TestWorld.fromSDL(
            """
            input NestedInput {
              rank: Int!
            }

            input SearchFilter {
              nested: NestedInput!
              enabled: Boolean!
            }

            type Query {
              search(filter: SearchFilter!, tags: [Int!]!, limit: Int!): Int!
              left: Int!
              right: Int!
            }
            """.trimIndent(),
        )

    private fun arguments(
        field: ViaductSchema.Field,
        limit: Int,
        rank: Int,
        tags: List<Int>,
        reverseFieldOrder: Boolean = false,
    ): Arguments.Resolved {
        val nested =
            if (reverseFieldOrder) {
                linkedMapOf<String, Any?>("rank" to rank)
            } else {
                mapOf("rank" to rank)
            }
        val filter =
            if (reverseFieldOrder) {
                linkedMapOf<String, Any?>(
                    "enabled" to true,
                    "nested" to nested,
                )
            } else {
                linkedMapOf<String, Any?>(
                    "nested" to nested,
                    "enabled" to true,
                )
            }
        val fields =
            if (reverseFieldOrder) {
                linkedMapOf<String, Any?>(
                    "limit" to limit,
                    "tags" to tags,
                    "filter" to filter,
                )
            } else {
                linkedMapOf<String, Any?>(
                    "filter" to filter,
                    "tags" to tags,
                    "limit" to limit,
                )
            }
        return Arguments.Resolved.of(field, fields)
    }

    private fun traversalWorld(): TestWorld =
        TestWorld.fromSDL(
            schemaSDL =
                """
                type Query {
                  item: Payload!
                  items: [Payload!]!
                  helper: Int!
                  dead: Int!
                }

                type Payload {
                  computed(scale: Int!): Int!
                  base: Int!
                }
                """.trimIndent(),
            fieldResolvers = { schema ->
                val queryNeedsHelper =
                    schema.fragmentFrom(
                        "fragment ignored on Query { helper }",
                    )
                val payloadNeedsBase =
                    schema.fragmentFrom(
                        "fragment ignored on Payload { base }",
                    )
                mapOf(
                    schema.loweredSchema.requireField("Query", "item") to
                        fieldResolverOf(queryNeedsHelper) { _, _ -> EngineErrorData.of() },
                    schema.loweredSchema.requireField("Query", "items") to
                        fieldResolverOf(queryNeedsHelper) { _, _ -> EngineErrorData.of() },
                    schema.loweredSchema.requireField("Query", "helper") to
                        fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                            EngineErrorData.of()
                        },
                    schema.loweredSchema.requireField("Query", "dead") to
                        fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                            EngineErrorData.of()
                        },
                    schema.loweredSchema.requireField("Payload", "computed") to
                        fieldResolverOf(payloadNeedsBase) { _, _ -> EngineErrorData.of() },
                    schema.loweredSchema.requireField("Payload", "base") to
                        fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Payload")) { _, _ ->
                            EngineErrorData.of()
                        },
                )
            },
        )

    private fun coordinate(field: ViaductSchema.ObjectField): FieldCoordinate = FieldCoordinate(field.containingDef.name, field.name)
}
