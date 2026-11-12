package viaduct.engine.runtime2.contract

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.correctresolution.correctResolution
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.instantiateBindings
import viaduct.engine.runtime2.resolvers.instantiateBindings
import viaduct.engine.runtime2.schema.operationSelectionsFrom
import viaduct.graphql.schema.ViaductSchema

/**
 * Contract for coalescing selections with equal symbolic or grounded arguments.
 */
interface VariableSelectionIdentityResolverContract : ResolverContract {
    @Test
    fun `equal pre-grounded selections merge in resolver fragments`() {
        val (testWorld, invocationObserver, suppliedDemandFields) = preGroundedSelectionsFixture()
        val resultKey = testWorld.schema.contractKey("Query", "result")

        val resolved = resolveAndValidate(testWorld, "query { result }", resolutionObserver = invocationObserver)

        assertEquals(8, resolved.getCell(resultKey).get())
        assertEquals(1, suppliedDemandFields.size)
        assertEquals(listOf(setOf("one", "two")), suppliedDemandFields.toList())
    }

    @Test
    fun `equal pre-grounded selections merge in external queries`() {
        val (testWorld, invocationObserver, suppliedDemandFields) = preGroundedSelectionsFixture()
        val schema = testWorld.schema
        val payloadKey = ObjectEngineResult.GroundKey.of(schema.requireObjectField("Query", "payload"), mapOf("arg" to 1))
        val oneKey = schema.contractKey("Payload", "one")
        val twoKey = schema.contractKey("Payload", "two")

        val resolved = resolveAndValidate(
            testWorld,
            "query { payload(arg: 1) { one } payload(arg: 1) { two } }",
            resolutionObserver = invocationObserver,
        )
        val payload = assertIs<ObjectEngineResult>(resolved.getCell(payloadKey).get())

        assertEquals(3, payload.getCell(oneKey).get())
        assertEquals(5, payload.getCell(twoKey).get())
        assertEquals(1, suppliedDemandFields.size)
        assertEquals(listOf(setOf("one", "two")), suppliedDemandFields.toList())
    }

    private fun preGroundedSelectionsFixture(): PreGroundedSelectionsFixture {
        val suppliedDemandFields = ConcurrentLinkedQueue<Set<String>>()
        val invocationObserver = object : CorrectnessResolverObserver() {
            override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
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
                schemaSDL =
                    """
                    extend type Query {
                      result: Int!
                        @resolver(
                          of: "payload(arg: 1) { one } payload(arg: 1) { two }"
                          script: "(objectValue.payload == null ? 0 : objectValue.payload.one == null ? 0 : objectValue.payload.one) + (objectValue.payload == null ? 0 : objectValue.payload.two == null ? 0 : objectValue.payload.two)"
                        )
                      payload(arg: Int!): Payload!
                        @resolver(script: "{'one': 3, 'two': 5}")
                    }

                    type Payload {
                      one: Int!
                      two: Int!
                    }
                    """.trimIndent(),
            )
        return PreGroundedSelectionsFixture(testWorld, invocationObserver, suppliedDemandFields)
    }

    @Test
    fun `equal symbolic selections coalesce independently of response aliases`() {
        val suppliedDemandFields = ConcurrentLinkedQueue<Set<String>>()
        val invocationObserver = object : CorrectnessResolverObserver() {
            override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
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
                schemaSDL =
                    """
                    extend type Query {
                      result(seed: Int!, other: Int!): Int!
                        @resolver(
                          of: "ground: payload(arg: 1) { one } seedValue: payload(arg: ${'$'}seed) { two } seedValue: payload(arg: ${'$'}seed) { two } otherValue: payload(arg: ${'$'}other) { one }"
                          script: "(objectValue.ground == null ? 0 : objectValue.ground.one == null ? 0 : objectValue.ground.one) + (objectValue.seedValue == null ? 0 : objectValue.seedValue.two == null ? 0 : objectValue.seedValue.two)"
                        )
                      payload(arg: Int!): Payload!
                        @resolver(script: "{'one': 3, 'two': 5}")
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
            testWorld.schemas.operationSelectionsFrom(
                """query { result(seed: 1, other: 1) }""",
            )

        val resolution =
            resolveAndValidateObserved(world, world.objectOf("Query"), selections, resolutionObserver = invocationObserver)
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
            override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
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
                schemaSDL =
                    """
                    extend type Query {
                      root: Root! @resolver(script: "{'nested': {:}}")
                      result: Int!
                        @resolver(
                          of: "source root { driver(value: ${'$'}value) }"
                          pathVars: [{name: "value", path: ["source"]}]
                          script: "(objectValue.root == null ? 0 : objectValue.root.driver == null ? 0 : objectValue.root.driver)"
                        )
                      source: Int!
                        @resolver(of: "delay", script: "(objectValue.delay == null ? 0 : objectValue.delay)")
                      delay: Int! @resolver(script: "1")
                    }

                    type Root {
                      nested: Nested!
                      driver(value: Int!): Int!
                        @resolver(
                          of: "nested { child(value: ${'$'}value) { two } }"
                          script: "(objectValue.nested == null ? 0 : objectValue.nested.child == null ? 0 : objectValue.nested.child.two == null ? 0 : objectValue.nested.child.two)"
                        )
                    }

                    type Nested {
                      child(value: Int!): Payload!
                        @resolver(script: "{'one': 3, 'two': 5}")
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
            testWorld.schemas.operationSelectionsFrom(
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

        val resolved = resolveAndValidate(world, selections, resolutionObserver = invocationObserver)

        assertEquals(5, resolved.getCell(resultKey).get())
        assertEquals(
            setOf(setOf("one"), setOf("two")),
            suppliedDemandFields.toSet(),
        )
    }
}

private data class PreGroundedSelectionsFixture(
    val testWorld: TestWorld,
    val invocationObserver: CorrectnessResolverObserver,
    val suppliedDemandFields: ConcurrentLinkedQueue<Set<String>>,
)
