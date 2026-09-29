package semantics.resolver26

import model.requireType
import model.requireObjectField
import model.Arguments
import model.ListEngineResult
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import viaduct.graphql.schema.ViaductSchema
import model.emptyFragmentOf
import model.fragmentFrom
import semantics.shared.isContextuallyGrounded
import model.merge
import model.objectOf
import semantics.shared.groundedArguments
import model.usedVariables
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromArgument
import semantics.correctresolution.correctResolution
import semantics.contract.selectionValues
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import viaduct.engine.api.EngineObjectData
import semantics.shared.SharedOperationContext
import semantics.correctresolution.CorrectnessResolverObserver

class SymbolicKeyIdentityTest : Resolver26DispatcherResource {
    @Test
    fun `list elements reuse one resolver-owned symbolic child key`() {
        val resultFragment =
            """
            fragment Result on Query {
              items {
                child(value: ${'$'}seed)
              }
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Item {
                      child(value: Int!): Int!
                    }

                    type Query {
                      result(seed: Int!): Int!
                      items: [Item!]!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val result = schema.requireObjectField("Query", "result")
                    val items = schema.requireObjectField("Query", "items")
                    val itemsKey = ObjectEngineResult.GroundKey.of(items, emptyMap())
                    checkNotNull(items.type.unwrapList())
                    val child = schema.requireObjectField("Item", "child")
                    val visibleChildKey = ObjectEngineResult.GroundKey.of(child, mapOf("value" to 7))
                    mapOf(
                        result to
                            fieldResolverOf(schema.fragmentFrom(resultFragment)) { input, _ ->
                                val itemValues =
                                    input.selectionValues().getValue(itemsKey.field.name) as List<*>
                                itemValues.sumOf { value ->
                                        val item = value as EngineObjectData.Sync
                                        val childValue =
                                            item.selectionValues().getValue(
                                                visibleChildKey.field.name,
                                            ) as Int
                                        childValue
                                    }
                            },
                        items to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                listOf(
                                            schema.objectOf("Item"),
                                            schema.objectOf("Item"),
                                        )
                            },
                        child to
                            fieldResolverOf(schema.emptyFragmentOf("Item")) { _, arguments ->
                                arguments.fieldValues.getValue("value") as Int
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.requireObjectField("Query", "result")
                    mapOf(
                        Arguments.Variable.of(result, "seed") to
                            schema.fromArgument(result, "seed"),
                    )
                },
            )
        val world = testWorld.assumptions
        val operation = SharedOperationContext.create(world, resolverObserver = CorrectnessResolverObserver())
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
            world.fragmentFrom(
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
        val resultFragment =
            """
            fragment Result on Query {
              ground: frank(arg: "hi") { one }
              seedValue: frank(arg: ${'$'}seed) { two }
              seedValue: frank(arg: ${'$'}seed) { two }
              otherValue: frank(arg: ${'$'}other) { one }
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Payload {
                      one: Int!
                      two: Int!
                    }

                    type Query {
                      result(seed: String!, other: String!): Int!
                      frank(arg: String!): Payload!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val frank = schema.requireObjectField("Query", "frank")
                    val oneKey =
                        ObjectEngineResult.GroundKey.of(
                            schema.requireObjectField("Payload", "one"),
                            emptyMap(),
                        )
                    val twoKey =
                        ObjectEngineResult.GroundKey.of(
                            schema.requireObjectField("Payload", "two"),
                            emptyMap(),
                        )
                    mapOf(
                        schema.requireObjectField("Query", "result") to
                            fieldResolverOf(schema.fragmentFrom(resultFragment)) { input, _ ->
                                val ground =
                                    input.selectionValues().getValue("ground") as EngineObjectData.Sync
                                val seedValue =
                                    input.selectionValues().getValue("seedValue") as EngineObjectData.Sync
                                val one =
                                    ground.selectionValues().getValue(oneKey.field.name) as Int
                                val two =
                                    seedValue.selectionValues().getValue(twoKey.field.name) as Int
                                one + two
                            },
                        frank to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Payload") {
                                    "one" setTo 3
                                    "two" setTo 5
                                }
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.requireObjectField("Query", "result")
                    mapOf(
                        Arguments.Variable.of(result, "seed") to
                            schema.fromArgument(result, "seed"),
                        Arguments.Variable.of(result, "other") to
                            schema.fromArgument(result, "other"),
                    )
                },
            )
        val world = testWorld.assumptions
        val operation = SharedOperationContext.create(world, resolverObserver = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: semantics.shared.ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                if (observation.field.name != "frank") return
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
        })
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "result"),
                mapOf(
                    "seed" to "hi",
                    "other" to "hi",
                ),
            )
        val fragment =
            world.fragmentFrom(
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
        val leftFragment =
            """fragment Left on Query { frank(arg: ${'$'}seed) { one } }"""
        val rightFragment =
            """fragment Right on Query { frank(arg: ${'$'}seed) { two } }"""
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Payload {
                      one: Int!
                      two: Int!
                    }

                    type Query {
                      left(seed: String!): Int!
                      right(seed: String!): Int!
                      frank(arg: String!): Payload!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val frank = schema.requireObjectField("Query", "frank")
                    val frankKey = ObjectEngineResult.GroundKey.of(frank, mapOf("arg" to "hi"))
                    val oneKey =
                        ObjectEngineResult.GroundKey.of(
                            schema.requireObjectField("Payload", "one"),
                            emptyMap(),
                        )
                    val twoKey =
                        ObjectEngineResult.GroundKey.of(
                            schema.requireObjectField("Payload", "two"),
                            emptyMap(),
                        )
                    mapOf(
                        schema.requireObjectField("Query", "left") to
                            fieldResolverOf(schema.fragmentFrom(leftFragment)) { input, _ ->
                                val payload =
                                    input.selectionValues().getValue(frankKey.field.name)
                                        as EngineObjectData.Sync
                                payload.selectionValues().getValue(oneKey.field.name)
                            },
                        schema.requireObjectField("Query", "right") to
                            fieldResolverOf(schema.fragmentFrom(rightFragment)) { input, _ ->
                                val payload =
                                    input.selectionValues().getValue(frankKey.field.name)
                                        as EngineObjectData.Sync
                                payload.selectionValues().getValue(twoKey.field.name)
                            },
                        frank to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Payload") {
                                    "one" setTo 3
                                    "two" setTo 5
                                }
                            },
                    )
                },
                variableProviders = { schema ->
                    val left = schema.requireObjectField("Query", "left")
                    val right = schema.requireObjectField("Query", "right")
                    mapOf(
                        Arguments.Variable.of(left, "seed") to schema.fromArgument(left, "seed"),
                        Arguments.Variable.of(right, "seed") to schema.fromArgument(right, "seed"),
                    )
                },
            )
        val world = testWorld.assumptions
        val operation = SharedOperationContext.create(world, resolverObserver = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: semantics.shared.ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                if (observation.field.name != "frank") return
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
        })
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
            world.fragmentFrom(
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
