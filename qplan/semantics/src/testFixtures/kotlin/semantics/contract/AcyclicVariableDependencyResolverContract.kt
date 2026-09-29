package semantics.contract

import io.kotest.matchers.collections.shouldContainExactly
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import model.Arguments
import model.ObjectEngineResult
import model.requireObjectField
import model.testing.TestWorld
import semantics.correctresolution.CorrectnessResolverObserver
import semantics.shared.ResolverInvocationObservation

interface AcyclicVariableDependencyResolverContract : ResolverContract {
    @Test
    fun `accepts an acyclic path-variable dependency chain`() {
        val applicationArguments = ResolverApplicationArguments()
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      pa: Int! @resolver(result: 1)
                      b(value: Int!): Int! @resolver(result: "sum(${'$'}value)")
                      a(seed: Int!): Int!
                        @resolver(
                          of: "pa b(value: ${'$'}fromPa)"
                          pathVars: [{name: "fromPa", path: ["pa"]}]
                          result: "sum(b)"
                        )
                      q: Int! @resolver(of: "a(seed: 1)", result: "sum(a)")
                      p2: Int! @resolver(result: 1)
                      d(seed: Int!): Int!
                        @resolver(
                          of: "p2 a(seed: ${'$'}fromP2)"
                          pathVars: [{name: "fromP2", path: ["p2"]}]
                          result: "sum(a)"
                        )
                      c: Int!
                        @resolver(
                          of: "q d(seed: ${'$'}fromQ)"
                          pathVars: [{name: "fromQ", path: ["q"]}]
                          result: "sum(d)"
                        )
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val cKey = world.schema.contractKey("Query", "c")

        val resolved = resolveAndValidate(world, "query { c }", resolverObserver = applicationArguments)

        assertEquals(1, resolved.getCell(cKey).get())
        applicationArguments.assertDistinctArguments(
            world.schema.requireObjectField("Query", "a"),
            mapOf("seed" to 1),
        )
        applicationArguments.assertDistinctArguments(
            world.schema.requireObjectField("Query", "d"),
            mapOf("seed" to 1),
        )
    }

    @Test
    fun `accepts an acyclic mixed-variable dependency chain`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      outer: Int!
                        @resolver(
                          of: "q5 q2(value: ${'$'}fromQ5)"
                          pathVars: [{name: "fromQ5", path: ["q5"]}]
                          result: "sum(q2)"
                        )
                      q1(value: Int!): Int! @resolver(result: "sum(${'$'}value)")
                      q2(value: Int!): Int!
                        @resolver(
                          of: "q7(value: ${'$'}value)"
                          result: "sum(q7)"
                        )
                      q5: Int! @resolver(of: "q1(value: 1)", result: "sum(q1)")
                      q7(value: Int!): Int!
                        @resolver(
                          of: "q1(value: ${'$'}value)"
                          result: "sum(q1)"
                        )
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val outerKey = world.schema.contractKey("Query", "outer")

        val resolved: ObjectEngineResult =
            resolveAndValidate(world, "query { outer }")

        assertEquals(1, resolved.getCell(outerKey).get())
    }

    @Test
    fun `orders argument-bearing applications through a path-variable dependency`() {
        val argumentApplications =
            ConcurrentLinkedQueue<Pair<String, Arguments.Resolved>>()
        val invocationObserver = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                val field = observation.field
                val arguments = observation.arguments
                if (field.name == "first" || field.name == "consume") {
                    argumentApplications += field.name to arguments
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
                          of: "source consume(value: ${'$'}fromSource)"
                          pathVars: [{name: "fromSource", path: ["source"]}]
                          result: "sum(consume)"
                        )
                      source: Int!
                        @resolver(of: "first(value: 1)", result: "sum(first)")
                      first(value: Int!): Int!
                        @resolver(result: "sum(${'$'}value, ${'$'}value)")
                      consume(value: Int!): Int!
                        @resolver(result: "sum(${'$'}value, ${'$'}value)")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val resultKey = world.schema.contractKey("Query", "result")

        val resolved = resolveAndValidate(world, "query { result }", resolverObserver = invocationObserver)

        assertEquals(4, resolved.getCell(resultKey).get())
        argumentApplications.toList().shouldContainExactly(
            "first" to
                Arguments.Resolved.of(
                    world.schema.requireObjectField("Query", "first"),
                    mapOf("value" to 1),
                ),
            "consume" to
                Arguments.Resolved.of(
                    world.schema.requireObjectField("Query", "consume"),
                    mapOf("value" to 2),
                ),
        )
    }
}
