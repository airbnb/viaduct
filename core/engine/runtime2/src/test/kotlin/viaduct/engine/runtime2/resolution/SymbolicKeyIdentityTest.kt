package viaduct.engine.runtime2.resolution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.correctresolution.correctResolution
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.usedVariables
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.framework.groundedArguments
import viaduct.engine.runtime2.resolution.framework.isContextuallyGrounded
import viaduct.graphql.schema.ViaductSchema

class SymbolicKeyIdentityTest : ResolutionDispatcherResource {
    @Test
    fun `list elements reuse one resolver-owned symbolic child key`() {
        val testWorld =
            TestWorld.fromDSL(
                """
                extend type Query {
                  result(seed: Int!): Int! @resolver(
                    of: "items { child(value: ${'$'}seed) }"
                    script: "var total = 0; for (var item : objectValue.items) { total += item.child; } total"
                  )
                  items: [Item!]! @resolver(script: "[{:}, {:}]")
                }
                type Item { child(value: Int!): Int! @resolver(script: "value") }
                """.trimIndent(),
            )
        val world = testWorld.assumptions
        val operation = SharedOperationContext.create(world, resolutionObserver = CorrectnessResolverObserver())
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "result"),
                mapOf("seed" to 7),
            )
        val itemsKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "items"),
                emptyMap(),
            )
        val fragment =
            testWorld.schemas.fragmentFrom(
                "fragment Query on Query { result(seed: 7) }",
            )

        val resolved =
            operation.resolveWithTestDispatcher(fragment.subselections)
        val items = assertIs<ListEngineResult>(resolved.getCell(itemsKey).value.get())
        val childKeys =
            items.indices.map { index ->
                val item =
                    assertIs<ObjectEngineResult>(items[index].value.get())
                val childKey =
                    item.keys.single { groundKey ->
                        groundKey.field.name == "child"
                    }
                childKey
            }

        assertEquals(14, resolved.getCell(resultKey).value.get())
        assertEquals(1, childKeys.toSet().size)
        assertEquals(
            setOf(ResolverOccurrenceId.at(resolved, listOf(resultKey))),
            childKeys
                .flatMap { key -> key.arguments.usedVariables() }
                .mapNotNullTo(linkedSetOf()) { variable ->
                    variable.instanceId?.resolverOccurrenceId
                },
        )
        assertTrue(
            resolved.correctResolution(operation, fragment),
        )
    }

    @Test
    fun `symbolic cell identity coalesces only equal variable instances`() {
        var frankApplications = 0
        val frankDemandFields = mutableListOf<Set<String>>()
        val testWorld =
            TestWorld.fromDSL(
                """
                extend type Query {
                  result(seed: String!, other: String!): Int! @resolver(
                    of: "ground: frank(arg: \"hi\") { one } seedValue: frank(arg: ${'$'}seed) { two } seedValue: frank(arg: ${'$'}seed) { two } otherValue: frank(arg: ${'$'}other) { one }"
                    script: "objectValue.ground.one + objectValue.seedValue.two"
                  )
                  frank(arg: String!): Payload! @resolver(script: "{'one': 3, 'two': 5}")
                }
                type Payload { one: Int! two: Int! }
                """.trimIndent(),
            )
        val world = testWorld.assumptions
        val operation = SharedOperationContext.create(
            world,
            resolutionObserver = object : CorrectnessResolverObserver() {
                override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = onResolverInvocation@{ observation ->
                    super.onResolverInvocation(observation)
                    if (observation.field.name != "frank") return@onResolverInvocation
                    val demand = observation.suppliedDemand
                    if (demand != null) {
                        frankApplications += 1
                        frankDemandFields +=
                            demand
                                .merge(
                                    world.schema.requireType("Payload") as ViaductSchema.Object,
                                ).groundKeys()
                                .mapTo(linkedSetOf()) { groundKey ->
                                    groundKey.field.name
                                }
                    }
                }
            }
        )
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "result"),
                mapOf(
                    "seed" to "hi",
                    "other" to "hi",
                ),
            )
        val fragment =
            testWorld.schemas.fragmentFrom(
                """fragment Query on Query { result(seed: "hi", other: "hi") }""",
            )

        val resolved =
            operation.resolveWithTestDispatcher(fragment.subselections)
        val frankKeys =
            resolved.keys.filter { objectKey -> objectKey.field.name == "frank" }
        val literalKeys =
            frankKeys.filterIsInstance<ObjectEngineResult.GroundKey>()
        val symbolicKeys =
            frankKeys.filterNot { objectKey -> objectKey is ObjectEngineResult.GroundKey }

        assertEquals(8, resolved.getCell(resultKey).value.get())
        assertEquals(3, frankApplications)
        assertEquals(3, frankKeys.size)
        assertEquals(1, literalKeys.size)
        assertEquals(2, symbolicKeys.size)
        symbolicKeys.forEach { objectKey ->
            assertTrue(objectKey.isContextuallyGrounded(operation))
            assertEquals(
                Arguments.Resolved.of(
                    world.schema.requireObjectField("Query", "frank"),
                    mapOf("arg" to "hi"),
                ),
                objectKey.groundedArguments(operation),
            )
        }
        assertEquals(
            setOf("seed", "other"),
            symbolicKeys.flatMapTo(linkedSetOf()) { key ->
                key.arguments.usedVariables().map(Arguments.Variable::variableName)
            },
        )
        assertEquals(
            mapOf(
                setOf("one") to 2,
                setOf("two") to 1,
            ),
            frankDemandFields.groupingBy { fields -> fields }.eachCount(),
        )
        assertTrue(
            resolved.correctResolution(operation, fragment),
        )
    }

    @Test
    fun `equal grounded calls from different resolver instances remain separate`() {
        val frankArguments = mutableListOf<Arguments.Resolved>()
        val frankDemandFields = mutableListOf<Set<String>>()
        val testWorld =
            TestWorld.fromDSL(
                """
                extend type Query {
                  left(seed: String!): Int! @resolver(of: "frank(arg: ${'$'}seed) { one }", script: "objectValue.frank.one")
                  right(seed: String!): Int! @resolver(of: "frank(arg: ${'$'}seed) { two }", script: "objectValue.frank.two")
                  frank(arg: String!): Payload! @resolver(script: "{'one': 3, 'two': 5}")
                }
                type Payload { one: Int! two: Int! }
                """.trimIndent(),
            )
        val world = testWorld.assumptions
        val operation = SharedOperationContext.create(
            world,
            resolutionObserver = object : CorrectnessResolverObserver() {
                override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = onResolverInvocation@{ observation ->
                    super.onResolverInvocation(observation)
                    if (observation.field.name != "frank") return@onResolverInvocation
                    val arguments = observation.arguments
                    val demand = observation.suppliedDemand
                    if (demand != null) {
                        frankArguments += arguments
                        frankDemandFields +=
                            demand
                                .merge(world.schema.requireType("Payload") as ViaductSchema.Object)
                                .groundKeys()
                                .mapTo(linkedSetOf()) { groundKey ->
                                    groundKey.field.name
                                }
                    }
                }
            }
        )
        val leftKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "left"),
                mapOf("seed" to "hi"),
            )
        val rightKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "right"),
                mapOf("seed" to "hi"),
            )
        val fragment =
            testWorld.schemas.fragmentFrom(
                """
                fragment Query on Query {
                  left(seed: "hi")
                  right(seed: "hi")
                }
                """.trimIndent(),
            )

        val resolved =
            operation.resolveWithTestDispatcher(fragment.subselections)
        val frankKeys =
            resolved.keys.filter { objectKey -> objectKey.field.name == "frank" }

        assertEquals(3, resolved.getCell(leftKey).value.get())
        assertEquals(5, resolved.getCell(rightKey).value.get())
        assertEquals(2, frankKeys.size)
        frankKeys.forEach { objectKey ->
            assertFalse(objectKey is ObjectEngineResult.GroundKey)
            assertTrue(objectKey.isContextuallyGrounded(operation))
            assertEquals(
                Arguments.Resolved.of(
                    world.schema.requireObjectField("Query", "frank"),
                    mapOf("arg" to "hi"),
                ),
                objectKey.groundedArguments(operation),
            )
        }
        assertEquals(
            setOf(
                ResolverOccurrenceId.at(resolved, listOf(leftKey)),
                ResolverOccurrenceId.at(resolved, listOf(rightKey)),
            ),
            frankKeys
                .flatMap { key -> key.arguments.usedVariables() }
                .mapNotNullTo(linkedSetOf()) { variable ->
                    variable.instanceId?.resolverOccurrenceId
                },
        )
        assertEquals(
            listOf(
                Arguments.Resolved.of(
                    world.schema.requireObjectField("Query", "frank"),
                    mapOf("arg" to "hi"),
                ),
                Arguments.Resolved.of(
                    world.schema.requireObjectField("Query", "frank"),
                    mapOf("arg" to "hi"),
                ),
            ),
            frankArguments,
        )
        assertEquals(
            setOf(setOf("one"), setOf("two")),
            frankDemandFields.toSet(),
        )
        assertTrue(
            resolved.correctResolution(operation, fragment),
        )
    }
}
