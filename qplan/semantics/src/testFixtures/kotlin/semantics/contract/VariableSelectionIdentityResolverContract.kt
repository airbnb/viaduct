package semantics.contract

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import model.ObjectEngineResult
import model.merge
import model.objectOf
import model.operationSelectionsFrom
import model.requireObjectField
import model.requireQueryTypeDef
import model.testing.TestWorld
import semantics.correctresolution.CorrectnessResolverObserver
import semantics.correctresolution.correctResolution
import semantics.shared.ResolverInvocationObservation
import semantics.shared.instantiateBindings
import viaduct.graphql.schema.ViaductSchema

/**
 * Contract for coalescing selections with equal symbolic or grounded arguments.
 */
interface VariableSelectionIdentityResolverContract : ResolverContract {
    @Test
    fun `equal pre-grounded selections merge in fragments and external queries`() {
        val suppliedDemandFields = ConcurrentLinkedQueue<Set<String>>()
        val invocationObserver = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                val field = observation.field
                val demand = observation.suppliedDemand
                if (
                    field.containingDef.name == "Query" &&
                    field.name == "payload" &&
                    demand != null
                ) {
                    suppliedDemandFields +=
                        demand
                            .merge(field.type.baseTypeDef as ViaductSchema.Object)
                            .groundKeys()
                            .mapTo(linkedSetOf()) { key ->
                                key.field.name
                            }
                }
            }
        }
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      result: Int!
                        @resolver(
                          of: "payload(arg: 1) { one } payload(arg: 1) { two }"
                          result: "sum(payload.one, payload.two)"
                        )
                      payload(arg: Int!): Payload!
                        @resolver(result: {one: 3, two: 5})
                    }

                    type Payload {
                      one: Int!
                      two: Int!
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val resultKey = world.schema.contractKey("Query", "result")
        val resultSelections = world.operationSelectionsFrom("query { result }")

        val resolvedResult =
            resolveAndValidate(
                world,
                world.objectOf("Query"),
                resultSelections,
                resolverObserver = invocationObserver,
            )

        assertEquals(8, resolvedResult.getCell(resultKey).get())
        assertEquals(1, suppliedDemandFields.size)
        assertEquals(listOf(setOf("one", "two")), suppliedDemandFields.toList())

        val payloadKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "payload"),
                mapOf("arg" to 1),
            )
        val oneKey = world.schema.contractKey("Payload", "one")
        val twoKey = world.schema.contractKey("Payload", "two")
        val externalSelections =
            world.operationSelectionsFrom(
                """
                query {
                  payload(arg: 1) { one }
                  payload(arg: 1) { two }
                }
                """.trimIndent(),
            )

        val resolvedExternal =
            resolveAndValidate(
                world,
                world.objectOf("Query"),
                externalSelections,
                resolverObserver = invocationObserver,
            )
        val payload =
            assertIs<ObjectEngineResult>(
                resolvedExternal.getCell(payloadKey).get(),
            )

        assertEquals(3, payload.getCell(oneKey).get())
        assertEquals(5, payload.getCell(twoKey).get())
        assertEquals(2, suppliedDemandFields.size)
        assertEquals(
            listOf(setOf("one", "two"), setOf("one", "two")),
            suppliedDemandFields.toList(),
        )
    }

    @Test
    fun `equal symbolic selections coalesce independently of response aliases`() {
        val suppliedDemandFields = ConcurrentLinkedQueue<Set<String>>()
        val invocationObserver = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                val field = observation.field
                val demand = observation.suppliedDemand
                if (
                    field.containingDef.name == "Query" &&
                    field.name == "payload" &&
                    demand != null
                ) {
                    suppliedDemandFields +=
                        demand
                            .merge(field.type.baseTypeDef as ViaductSchema.Object)
                            .groundKeys()
                            .mapTo(linkedSetOf()) { key ->
                                key.field.name
                            }
                }
            }
        }
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      result(seed: Int!, other: Int!): Int!
                        @resolver(
                          of: "ground: payload(arg: 1) { one } seedValue: payload(arg: ${'$'}seed) { two } seedValue: payload(arg: ${'$'}seed) { two } otherValue: payload(arg: ${'$'}other) { one }"
                          result: "sum(ground.one, seedValue.two)"
                        )
                      payload(arg: Int!): Payload!
                        @resolver(result: {one: 3, two: 5})
                    }

                    type Payload {
                      one: Int!
                      two: Int!
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "result"),
                mapOf(
                    "seed" to 1,
                    "other" to 1,
                ),
            )
        val selections =
            world.operationSelectionsFrom(
                """query { result(seed: 1, other: 1) }""",
            )

        val resolution =
            resolveAndValidateObserved(world, world.objectOf("Query"), selections, resolverObserver = invocationObserver)
        val resolved = resolution.result

        assertEquals(8, resolved.getCell(resultKey).get())
        assertEquals(
            mapOf(
                setOf("one") to 2,
                setOf("two") to 1,
            ),
            suppliedDemandFields.groupingBy { fields -> fields }.eachCount(),
        )
        assertTrue(
            resolved.correctResolution(
                resolution.operation,
                selections
                    .merge(world.schema.requireQueryTypeDef())
                    .instantiateBindings(resolution.operation),
            ),
        )
    }

    @Test
    fun `a symbolic fromArgument key remains distinct from an equal grounded key`() {
        val suppliedDemandFields = ConcurrentLinkedQueue<Set<String>>()
        val invocationObserver = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                val field = observation.field
                val demand = observation.suppliedDemand
                if (
                    field.containingDef.name == "Nested" &&
                    field.name == "child" &&
                    demand != null
                ) {
                    suppliedDemandFields +=
                        demand
                            .merge(field.type.baseTypeDef as ViaductSchema.Object)
                            .groundKeys()
                            .mapTo(linkedSetOf()) { key ->
                                key.field.name
                            }
                }
            }
        }
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      root: Root! @resolver(result: {nested: {}})
                      result: Int!
                        @resolver(
                          of: "source root { driver(value: ${'$'}value) }"
                          pathVars: [{name: "value", path: ["source"]}]
                          result: "sum(root.driver)"
                        )
                      source: Int!
                        @resolver(of: "delay", result: "sum(delay)")
                      delay: Int! @resolver(result: 1)
                    }

                    type Root {
                      nested: Nested!
                      driver(value: Int!): Int!
                        @resolver(
                          of: "nested { child(value: ${'$'}value) { two } }"
                          result: "sum(nested.child.two)"
                        )
                    }

                    type Nested {
                      child(value: Int!): Payload!
                        @resolver(result: {one: 3, two: 5})
                    }

                    type Payload {
                      one: Int!
                      two: Int!
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val resultKey = world.schema.contractKey("Query", "result")
        val selections =
            world.operationSelectionsFrom(
                """
                query {
                  root {
                    nested {
                      child(value: 1) { one }
                    }
                  }
                  result
                }
                """.trimIndent(),
            )

        val resolved = resolveAndValidate(world, selections, resolverObserver = invocationObserver)

        assertEquals(5, resolved.getCell(resultKey).get())
        assertEquals(
            setOf(setOf("one"), setOf("two")),
            suppliedDemandFields.toSet(),
        )
    }
}
